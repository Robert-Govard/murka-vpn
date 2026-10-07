package xraymobile

import (
	"fmt"
	"sync"
	"sync/atomic"
	"syscall"

	"github.com/xtls/xray-core/transport/internet"
)

// SocketProtector keeps a socket out of the VPN tunnel (Android
// VpnService.protect). Same contract as olcRTC's mobile.SocketProtector.
type SocketProtector interface {
	Protect(fd int) bool
}

type protectorBox struct{ p SocketProtector }

var (
	protector    atomic.Pointer[protectorBox]
	registerOnce sync.Once
	registerErr  error
)

// SetProtector sets the process-wide socket protector; nil disables it.
func SetProtector(p SocketProtector) {
	if p == nil {
		protector.Store(nil)
		return
	}
	protector.Store(&protectorBox{p: p})
}

// installController hooks Xray's system dialer and listener once per process.
// Outbound TCP goes through the dialer, outbound UDP through the listener.
// Xray logs a controller error and keeps the socket, so a refused Protect
// is reported in the log rather than failing the connection.
func installController() error {
	registerOnce.Do(func() {
		if err := internet.RegisterDialerController(protectConn); err != nil {
			registerErr = err
			return
		}
		registerErr = internet.RegisterListenerController(protectConn)
	})
	return registerErr
}

func protectConn(network, address string, conn syscall.RawConn) error {
	box := protector.Load()
	if box == nil {
		return nil
	}
	ok := false
	if err := conn.Control(func(fd uintptr) { ok = box.p.Protect(int(fd)) }); err != nil {
		return fmt.Errorf("protect %s %s: %w", network, address, err)
	}
	if !ok {
		return fmt.Errorf("protect %s %s: refused", network, address)
	}
	return nil
}
