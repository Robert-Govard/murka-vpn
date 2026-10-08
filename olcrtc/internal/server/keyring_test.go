package server

import (
	"encoding/hex"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	cryptopkg "github.com/openlibrecommunity/olcrtc/internal/crypto"
	"github.com/openlibrecommunity/olcrtc/internal/muxconn"
)

const (
	ringKeyA = "1111111111111111111111111111111111111111111111111111111111111111"
	ringKeyB = "2222222222222222222222222222222222222222222222222222222222222222"
	ringKeyC = "3333333333333333333333333333333333333333333333333333333333333333"
)

// keysFileClock gives every rewrite a strictly later mtime, so Reload sees a
// change even when the new content has the same size as the old one.
var keysFileClock atomic.Int64

func writeKeysFile(t *testing.T, path string, keys ...string) {
	t.Helper()
	body := "# test keys\n\n" + strings.Join(keys, "\n") + "\n"
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatalf("write keys: %v", err)
	}
	future := time.Now().Add(time.Duration(keysFileClock.Add(1)) * time.Minute)
	if err := os.Chtimes(path, future, future); err != nil {
		t.Fatalf("chtimes: %v", err)
	}
}

func clientRecord(t *testing.T, keyHex string, control bool) []byte {
	t.Helper()
	psk, err := hex.DecodeString(keyHex)
	if err != nil {
		t.Fatal(err)
	}
	ks, err := cryptopkg.NewKeySet(psk, cryptopkg.Client)
	if err != nil {
		t.Fatal(err)
	}
	rec, err := ks.SealInto(nil, []byte("hello"), muxconn.RecordAAD(control))
	if err != nil {
		t.Fatal(err)
	}
	return rec
}

func TestKeyRingResolvesByRecord(t *testing.T) {
	path := filepath.Join(t.TempDir(), "keys")
	writeKeysFile(t, path, ringKeyA, ringKeyB)
	ring, err := LoadKeyRing(path)
	if err != nil {
		t.Fatalf("LoadKeyRing: %v", err)
	}
	if ring.Len() != 2 {
		t.Fatalf("Len = %d, want 2", ring.Len())
	}
	a := ring.Resolve(clientRecord(t, ringKeyA, true), true)
	b := ring.Resolve(clientRecord(t, ringKeyB, true), true)
	if a == nil || b == nil || a == b {
		t.Fatalf("Resolve: a=%p b=%p, want two distinct key sets", a, b)
	}
	if ring.Resolve(clientRecord(t, ringKeyC, true), true) != nil {
		t.Fatal("Resolve accepted a key that is not in the file")
	}
	if ring.Resolve(clientRecord(t, ringKeyA, true), false) != nil {
		t.Fatal("Resolve ignored the control/data aad split")
	}
}

func TestKeyRingReloadKeepsSurvivorsAndReportsRemoved(t *testing.T) {
	path := filepath.Join(t.TempDir(), "keys")
	writeKeysFile(t, path, ringKeyA, ringKeyB)
	ring, err := LoadKeyRing(path)
	if err != nil {
		t.Fatal(err)
	}
	aBefore := ring.Resolve(clientRecord(t, ringKeyA, true), true)
	bBefore := ring.Resolve(clientRecord(t, ringKeyB, true), true)

	removed, err := ring.Reload()
	if err != nil || len(removed) != 0 {
		t.Fatalf("Reload without change: removed=%d err=%v", len(removed), err)
	}

	writeKeysFile(t, path, ringKeyA, ringKeyC)
	removed, err = ring.Reload()
	if err != nil {
		t.Fatalf("Reload: %v", err)
	}
	if len(removed) != 1 || removed[0] != bBefore {
		t.Fatalf("removed = %v, want only key B's set", removed)
	}
	if got := ring.Resolve(clientRecord(t, ringKeyA, true), true); got != aBefore {
		t.Fatal("surviving key got a new KeySet; replay state would be lost")
	}
	if ring.Resolve(clientRecord(t, ringKeyC, true), true) == nil {
		t.Fatal("added key was not loaded")
	}
}

func TestKeyRingBadFileKeepsPreviousKeys(t *testing.T) {
	path := filepath.Join(t.TempDir(), "keys")
	writeKeysFile(t, path, ringKeyA)
	ring, err := LoadKeyRing(path)
	if err != nil {
		t.Fatal(err)
	}
	writeKeysFile(t, path, ringKeyA, "not-hex")
	if _, err := ring.Reload(); err == nil {
		t.Fatal("Reload accepted a malformed key")
	}
	if ring.Resolve(clientRecord(t, ringKeyA, true), true) == nil {
		t.Fatal("a failed reload dropped the previous keys")
	}
}

func TestKeyRingEmptyFileAndMissingFile(t *testing.T) {
	dir := t.TempDir()
	empty := filepath.Join(dir, "empty")
	writeKeysFile(t, empty)
	ring, err := LoadKeyRing(empty)
	if err != nil || ring.Len() != 0 {
		t.Fatalf("empty file: ring=%v err=%v", ring, err)
	}
	if _, err := LoadKeyRing(filepath.Join(dir, "missing")); err == nil {
		t.Fatal("LoadKeyRing accepted a missing file")
	}
}
