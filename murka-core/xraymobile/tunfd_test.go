package xraymobile

import "testing"

func TestTunFDWithoutTunnel(t *testing.T) {
	if fd := TunFD(); fd != -1 {
		t.Fatalf("TunFD() = %d in a process without a utun socket, want -1", fd)
	}
}
