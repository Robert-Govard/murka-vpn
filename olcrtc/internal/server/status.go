package server

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

// statusInterval is how often the status file is rewritten.
const statusInterval = 15 * time.Second

// StatusSnapshot is the JSON written to status.file for node tooling.
type StatusSnapshot struct {
	Updated   int64 `json:"updated"`   // unix seconds of this write
	Connected bool  `json:"connected"` // the room link can carry control traffic
	Since     int64 `json:"since"`     // unix seconds when Connected last changed
	Clients   int   `json:"clients"`   // peers with a finished handshake
}

type statusWriter struct {
	path string
	last StatusSnapshot
}

func (w *statusWriter) write(now time.Time, connected bool, clients int) error {
	snap := StatusSnapshot{Updated: now.Unix(), Connected: connected, Since: w.last.Since, Clients: clients}
	if w.last.Updated == 0 || connected != w.last.Connected {
		snap.Since = now.Unix()
	}
	w.last = snap
	data, err := json.Marshal(snap)
	if err != nil {
		return fmt.Errorf("marshal status: %w", err)
	}
	return writeFileAtomic(w.path, append(data, '\n'))
}

func writeFileAtomic(path string, data []byte) error {
	tmp, err := os.CreateTemp(filepath.Dir(path), ".status-*")
	if err != nil {
		return fmt.Errorf("status file: %w", err)
	}
	defer func() { _ = os.Remove(tmp.Name()) }()
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("status file: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("status file: %w", err)
	}
	if err := os.Chmod(tmp.Name(), 0o644); err != nil {
		return fmt.Errorf("status file: %w", err)
	}
	if err := os.Rename(tmp.Name(), path); err != nil {
		return fmt.Errorf("status file: %w", err)
	}
	return nil
}

// linkConnected reports whether the room link is up. Control readiness is
// preferred because CanSend also turns false under send backpressure.
func (s *Server) linkConnected() bool {
	if s.ln == nil {
		return false
	}
	if cp, ok := s.ln.(transport.ControlPlane); ok {
		return cp.ControlCanSend()
	}
	return s.ln.CanSend()
}

// readyPeers counts peers whose handshake has finished.
func (s *Server) readyPeers() int {
	s.sessMu.RLock()
	peers := make([]*peerSession, 0, len(s.peerSessions))
	for _, p := range s.peerSessions {
		peers = append(peers, p)
	}
	s.sessMu.RUnlock()
	n := 0
	for _, p := range peers {
		if p.sid() != "" {
			n++
		}
	}
	return n
}

// writeStatus rewrites path with the room state every statusInterval until
// the server stops.
func (s *Server) writeStatus(ctx context.Context, path string) {
	w := &statusWriter{path: path}
	tick := func(now time.Time) {
		if err := w.write(now, s.linkConnected(), s.readyPeers()); err != nil {
			logger.Warnf("%v", err)
		}
	}
	tick(time.Now())
	t := time.NewTicker(statusInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-s.done:
			return
		case now := <-t.C:
			tick(now)
		}
	}
}
