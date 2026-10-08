package xraymobile

import (
	"sync/atomic"
	"testing"
)

type countingProtector struct {
	calls atomic.Int32
	allow bool
}

func (p *countingProtector) Protect(fd int) bool {
	if fd <= 0 {
		return false
	}
	p.calls.Add(1)
	return p.allow
}

func TestProtectorSeesOutboundSockets(t *testing.T) {
	srv := target(t)
	p := &countingProtector{allow: true}
	SetProtector(p)
	t.Cleanup(func() { SetProtector(nil) })

	port := freePort(t)
	r := New()
	if err := r.Start(socksConfig(port, "")); err != nil {
		t.Fatal(err)
	}
	defer r.Stop()
	// The inbound listener may already have been protected; only growth
	// after a request proves the outbound socket went through Protect.
	before := p.calls.Load()
	if body, err := getVia(port, srv.URL); err != nil || body != "ok" {
		t.Fatalf("via xray: %q %v", body, err)
	}
	if p.calls.Load() <= before {
		t.Fatal("Protect was not called for the outbound socket")
	}
}

// Xray only logs controller errors ("failed to apply external controller")
// and keeps dialing, so a refused Protect must not break the proxy.
func TestRefusedProtectIsNotFatal(t *testing.T) {
	srv := target(t)
	SetProtector(&countingProtector{allow: false})
	t.Cleanup(func() { SetProtector(nil) })

	port := freePort(t)
	r := New()
	if err := r.Start(socksConfig(port, "")); err != nil {
		t.Fatal(err)
	}
	defer r.Stop()
	if body, err := getVia(port, srv.URL); err != nil || body != "ok" {
		t.Fatalf("via xray with refused protect: %q %v", body, err)
	}
}
