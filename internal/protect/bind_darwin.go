//go:build darwin

package protect

import (
	"fmt"
	"net"

	"golang.org/x/sys/unix"
)

// BindToInterface pins every new socket to the named interface (IP_BOUND_IF), so
// the process keeps using the physical network while a desktop TUN owns the
// default route.
func BindToInterface(name string) error {
	iface, err := net.InterfaceByName(name)
	if err != nil {
		return fmt.Errorf("bind interface %q: %w", name, err)
	}
	index := iface.Index
	SetProtector(func(fd int) bool {
		// The socket is either IPv4 or IPv6; one of the two options applies.
		v4 := unix.SetsockoptInt(fd, unix.IPPROTO_IP, unix.IP_BOUND_IF, index)
		v6 := unix.SetsockoptInt(fd, unix.IPPROTO_IPV6, unix.IPV6_BOUND_IF, index)
		return v4 == nil || v6 == nil
	})
	return nil
}
