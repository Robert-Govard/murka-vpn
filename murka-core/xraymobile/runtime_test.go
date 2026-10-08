package xraymobile

import (
	"strings"
	"testing"
)

func TestStartProxiesAndStops(t *testing.T) {
	srv := target(t)
	port := freePort(t)
	r := New()
	if err := r.Start(socksConfig(port, "")); err != nil {
		t.Fatal(err)
	}
	if !r.IsRunning() {
		t.Fatal("IsRunning = false after Start")
	}
	if body, err := getVia(port, srv.URL); err != nil || body != "ok" {
		t.Fatalf("via xray: %q %v", body, err)
	}
	if err := r.Start(socksConfig(port, "")); err == nil {
		t.Fatal("second Start must fail while running")
	}
	if err := r.Stop(); err != nil {
		t.Fatal(err)
	}
	if err := r.Stop(); err != nil {
		t.Fatalf("Stop must be idempotent: %v", err)
	}
	if r.IsRunning() {
		t.Fatal("IsRunning = true after Stop")
	}
	if _, err := getVia(port, srv.URL); err == nil {
		t.Fatal("proxy still answers after Stop")
	}
	// Restart on the same port works.
	if err := r.Start(socksConfig(port, "")); err != nil {
		t.Fatalf("restart: %v", err)
	}
	defer r.Stop()
	if body, err := getVia(port, srv.URL); err != nil || body != "ok" {
		t.Fatalf("after restart: %q %v", body, err)
	}
}

func TestStartRejectsBadConfig(t *testing.T) {
	r := New()
	if err := r.Start(`{"outbounds": [`); err == nil {
		t.Fatal("want error for broken JSON")
	}
	if r.IsRunning() {
		t.Fatal("must not be running after failed Start")
	}
}

func TestVersion(t *testing.T) {
	if v := Version(); !strings.HasPrefix(v, "26.") {
		t.Fatalf("Version = %q", v)
	}
}
