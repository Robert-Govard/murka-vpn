package xraymobile

import "runtime/debug"

// SetMemoryLimit sets the Go runtime's soft memory limit in bytes. The iOS
// packet tunnel extension is killed past ~50 MB, so it keeps Go well below that.
// Values <= 0 are ignored.
func SetMemoryLimit(bytes int64) {
	if bytes > 0 {
		debug.SetMemoryLimit(bytes)
	}
}

// FreeOSMemory returns freed heap to the OS, e.g. after a core restart.
func FreeOSMemory() { debug.FreeOSMemory() }
