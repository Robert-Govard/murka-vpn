package xraymobile

import (
	"strings"

	"golang.org/x/sys/unix"
)

const (
	sysprotoControl = 2 // SYSPROTO_CONTROL
	utunOptIfname   = 2 // UTUN_OPT_IFNAME
	maxScannedFD    = 1024
)

// TunFD finds the utun file descriptor that NetworkExtension opened for the
// packet tunnel, or returns -1. iOS does not expose it through public API.
func TunFD() int {
	for fd := 0; fd <= maxScannedFD; fd++ {
		name, err := unix.GetsockoptString(fd, sysprotoControl, utunOptIfname)
		if err == nil && strings.HasPrefix(name, "utun") {
			return fd
		}
	}
	return -1
}
