package server

import (
	"bufio"
	"bytes"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/crypto"
	"github.com/openlibrecommunity/olcrtc/internal/muxconn"
	"github.com/openlibrecommunity/olcrtc/internal/tunnelcore"
)

// ErrKeysFileInvalid is returned when crypto.keys_file contains a malformed key.
var ErrKeysFileInvalid = errors.New("invalid key in keys file")

// KeyRing holds one server KeySet per key listed in crypto.keys_file. It lets
// a single room serve many users, each with their own key, and drops a user
// by removing their key from the file.
type KeyRing struct {
	path    string
	mu      sync.RWMutex
	sets    map[string]*crypto.KeySet // lowercase key hex -> key set
	modTime time.Time
	size    int64
}

// LoadKeyRing reads path and returns a ring with its keys.
func LoadKeyRing(path string) (*KeyRing, error) {
	r := &KeyRing{path: path, sets: map[string]*crypto.KeySet{}}
	if _, err := r.Reload(); err != nil {
		return nil, err
	}
	return r, nil
}

// Reload re-reads the file when its mtime or size changed. Keys still present
// keep their existing KeySet, so replay windows and send counters survive.
// It returns the key sets that were removed. On error the previous keys stay.
func (r *KeyRing) Reload() ([]*crypto.KeySet, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	info, err := os.Stat(r.path)
	if err != nil {
		return nil, fmt.Errorf("stat keys file: %w", err)
	}
	if !r.modTime.IsZero() && info.ModTime().Equal(r.modTime) && info.Size() == r.size {
		return nil, nil
	}
	keys, err := parseKeysFile(r.path)
	if err != nil {
		return nil, err
	}
	next := make(map[string]*crypto.KeySet, len(keys))
	for _, k := range keys {
		if ks, ok := r.sets[k]; ok {
			next[k] = ks
			continue
		}
		ks, err := tunnelcore.SetupKeySet(k, crypto.Server)
		if err != nil {
			return nil, fmt.Errorf("keys file %s: %w", r.path, err)
		}
		next[k] = ks
	}
	var removed []*crypto.KeySet
	for k, ks := range r.sets {
		if _, ok := next[k]; !ok {
			removed = append(removed, ks)
		}
	}
	r.sets, r.modTime, r.size = next, info.ModTime(), info.Size()
	return removed, nil
}

// Resolve returns the key set whose key authenticates record (a control
// record if control is true), or nil when no key matches.
func (r *KeyRing) Resolve(record []byte, control bool) *crypto.KeySet {
	aad := muxconn.RecordAAD(control)
	r.mu.RLock()
	defer r.mu.RUnlock()
	for _, ks := range r.sets {
		if ks.Authenticate(record, aad) {
			return ks
		}
	}
	return nil
}

// Len returns the number of loaded keys.
func (r *KeyRing) Len() int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return len(r.sets)
}

func parseKeysFile(path string) ([]string, error) {
	data, err := os.ReadFile(path) // #nosec G304 -- operator-supplied path from the server config
	if err != nil {
		return nil, fmt.Errorf("read keys file: %w", err)
	}
	var keys []string
	seen := map[string]bool{}
	sc := bufio.NewScanner(bytes.NewReader(data))
	for line := 1; sc.Scan(); line++ {
		k := strings.ToLower(strings.TrimSpace(sc.Text()))
		if k == "" || strings.HasPrefix(k, "#") {
			continue
		}
		if raw, err := hex.DecodeString(k); err != nil || len(raw) != 32 {
			return nil, fmt.Errorf("%w: %s line %d", ErrKeysFileInvalid, path, line)
		}
		if !seen[k] {
			seen[k] = true
			keys = append(keys, k)
		}
	}
	if err := sc.Err(); err != nil {
		return nil, fmt.Errorf("scan keys file: %w", err)
	}
	return keys, nil
}
