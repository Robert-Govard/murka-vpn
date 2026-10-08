package server

import (
	"path/filepath"
	"testing"
	"time"
)

func ringTestServer(t *testing.T, keys ...string) (*Server, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "room.keys")
	writeKeysFile(t, path, keys...)
	ring, err := LoadKeyRing(path)
	if err != nil {
		t.Fatal(err)
	}
	link := &peerControlRoutingStub{}
	s := reconnectTestServer(t, &link.peerRoutingStub)
	s.ln, s.peerLn = link, link
	s.ring = ring
	s.unknownPeers = map[string]time.Time{}
	t.Cleanup(s.closeSession)
	return s, path
}

func TestRingBindsPeerToItsOwnKey(t *testing.T) {
	s, _ := ringTestServer(t, ringKeyA, ringKeyB)
	s.onPeerControlData("peer-a", clientRecord(t, ringKeyA, true))
	s.onPeerControlData("peer-b", clientRecord(t, ringKeyB, true))

	s.sessMu.RLock()
	a, b := s.peerSessions["peer-a"], s.peerSessions["peer-b"]
	s.sessMu.RUnlock()
	if a == nil || b == nil {
		t.Fatalf("peer sessions not created: a=%v b=%v", a, b)
	}
	if a.keys == nil || b.keys == nil || a.keys == b.keys {
		t.Fatalf("peers not bound to distinct keys: a=%p b=%p", a.keys, b.keys)
	}
}

func TestRingIgnoresUnknownKey(t *testing.T) {
	s, _ := ringTestServer(t, ringKeyA)
	s.onPeerControlData("stranger", clientRecord(t, ringKeyC, true))
	s.onPeerData("stranger", clientRecord(t, ringKeyC, false))

	s.sessMu.RLock()
	n := len(s.peerSessions)
	s.sessMu.RUnlock()
	if n != 0 {
		t.Fatalf("peer sessions = %d, want 0 for an unknown key", n)
	}
}

func TestRingRevokesRemovedKey(t *testing.T) {
	s, path := ringTestServer(t, ringKeyA, ringKeyB)
	s.onPeerControlData("peer-a", clientRecord(t, ringKeyA, true))
	s.onPeerControlData("peer-b", clientRecord(t, ringKeyB, true))

	writeKeysFile(t, path, ringKeyA)
	removed, err := s.ring.Reload()
	if err != nil {
		t.Fatal(err)
	}
	if closed := s.revokePeers(removed); closed != 1 {
		t.Fatalf("revokePeers closed %d sessions, want 1", closed)
	}
	s.sessMu.RLock()
	_, aAlive := s.peerSessions["peer-a"]
	_, bAlive := s.peerSessions["peer-b"]
	s.sessMu.RUnlock()
	if !aAlive || bAlive {
		t.Fatalf("after revoke: peer-a alive=%v (want true), peer-b alive=%v (want false)", aAlive, bAlive)
	}
}
