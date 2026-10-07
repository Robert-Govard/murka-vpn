package server

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

func readStatus(t *testing.T, path string) StatusSnapshot {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var s StatusSnapshot
	if err := json.Unmarshal(b, &s); err != nil {
		t.Fatalf("%v: %q", err, b)
	}
	return s
}

func TestStatusWriterTracksSince(t *testing.T) {
	path := filepath.Join(t.TempDir(), "status.json")
	w := &statusWriter{path: path}
	t0 := time.Unix(1000, 0)
	steps := []struct {
		at        time.Time
		connected bool
		clients   int
		since     int64
	}{
		{t0, true, 2, 1000},
		{t0.Add(15 * time.Second), true, 3, 1000},
		{t0.Add(30 * time.Second), false, 0, 1030},
		{t0.Add(45 * time.Second), false, 0, 1030},
	}
	for i, st := range steps {
		if err := w.write(st.at, st.connected, st.clients); err != nil {
			t.Fatal(err)
		}
		got := readStatus(t, path)
		want := StatusSnapshot{Updated: st.at.Unix(), Connected: st.connected, Since: st.since, Clients: st.clients}
		if got != want {
			t.Fatalf("step %d: got %+v want %+v", i, got, want)
		}
	}
	if left, _ := filepath.Glob(filepath.Join(filepath.Dir(path), ".status-*")); len(left) != 0 {
		t.Fatalf("temp files left: %v", left)
	}
}

type fakeLink struct {
	transport.Transport
	canSend bool
}

func (f fakeLink) CanSend() bool { return f.canSend }

type fakeControlLink struct {
	fakeLink
	controlReady bool
}

func (f fakeControlLink) ControlSend([]byte) error      { return nil }
func (f fakeControlLink) SetControlOnData(func([]byte)) {}
func (f fakeControlLink) ControlCanSend() bool          { return f.controlReady }

func TestLinkConnected(t *testing.T) {
	cases := []struct {
		name string
		ln   transport.Transport
		want bool
	}{
		{"no link", nil, false},
		{"plain ready", fakeLink{canSend: true}, true},
		{"plain busy", fakeLink{canSend: false}, false},
		// Control readiness wins: CanSend also drops under send backpressure.
		{"control ready, data busy", fakeControlLink{fakeLink{canSend: false}, true}, true},
		{"control down", fakeControlLink{fakeLink{canSend: true}, false}, false},
	}
	for _, c := range cases {
		s := &Server{ln: c.ln}
		if got := s.linkConnected(); got != c.want {
			t.Errorf("%s: got %v want %v", c.name, got, c.want)
		}
	}
}

func TestReadyPeersCountsFinishedHandshakes(t *testing.T) {
	done := newPeerSession("a", false)
	done.sessionID = "s1"
	pending := newPeerSession("b", true)
	s := &Server{peerSessions: map[string]*peerSession{"a": done, "b": pending}}
	if got := s.readyPeers(); got != 1 {
		t.Fatalf("readyPeers = %d, want 1", got)
	}
}
