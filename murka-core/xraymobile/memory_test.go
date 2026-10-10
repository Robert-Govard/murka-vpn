package xraymobile

import (
	"runtime/debug"
	"testing"
)

func TestSetMemoryLimit(t *testing.T) {
	old := debug.SetMemoryLimit(-1)
	t.Cleanup(func() { debug.SetMemoryLimit(old) })

	SetMemoryLimit(30 << 20)
	if got := debug.SetMemoryLimit(-1); got != 30<<20 {
		t.Fatalf("memory limit = %d, want %d", got, 30<<20)
	}
	SetMemoryLimit(0) // ignored
	if got := debug.SetMemoryLimit(-1); got != 30<<20 {
		t.Fatalf("SetMemoryLimit(0) changed the limit to %d", got)
	}
}
