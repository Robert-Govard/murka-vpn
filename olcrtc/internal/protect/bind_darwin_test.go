//go:build darwin

package protect

import (
	"net"
	"testing"

	"golang.org/x/sys/unix"
)

func TestBindToInterfacePinsSockets(t *testing.T) {
	t.Cleanup(func() { SetProtector(nil) })
	lo, err := net.InterfaceByName("lo0")
	if err != nil {
		t.Fatal(err)
	}
	if err := BindToInterface("lo0"); err != nil {
		t.Fatalf("BindToInterface: %v", err)
	}

	conn, err := newDialer().Dial("udp4", "127.0.0.1:9")
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close()
	raw, err := conn.(*net.UDPConn).SyscallConn()
	if err != nil {
		t.Fatal(err)
	}
	var bound int
	var getErr error
	_ = raw.Control(func(fd uintptr) { bound, getErr = unix.GetsockoptInt(int(fd), unix.IPPROTO_IP, unix.IP_BOUND_IF) })
	if getErr != nil || bound != lo.Index {
		t.Fatalf("IP_BOUND_IF = %d (%v), want %d", bound, getErr, lo.Index)
	}
}

func TestBindToInterfaceRejectsUnknownName(t *testing.T) {
	t.Cleanup(func() { SetProtector(nil) })
	if err := BindToInterface("no-such-if0"); err == nil {
		t.Fatal("want error for unknown interface")
	}
}

func TestUtunIsTreatedAsTunnel(t *testing.T) {
	if !isTunInterface("utun7") {
		t.Fatal("utun7 must be excluded from ICE candidates")
	}
}
