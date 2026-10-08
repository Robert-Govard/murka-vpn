//go:build !darwin

package protect

import "errors"

// ErrBindUnsupported is returned where BindToInterface is not implemented.
var ErrBindUnsupported = errors.New("net.bind_interface is only supported on macOS")

// BindToInterface is only needed by the macOS desktop TUN.
func BindToInterface(string) error { return ErrBindUnsupported }
