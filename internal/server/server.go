// Package server implements the olcrtc tunnel server logic.
package server

import (
	"context"
	crand "crypto/rand"
	"errors"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/xtaci/smux"

	"github.com/openlibrecommunity/olcrtc/internal/control"
	"github.com/openlibrecommunity/olcrtc/internal/crypto"
	"github.com/openlibrecommunity/olcrtc/internal/handshake"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/muxconn"
	"github.com/openlibrecommunity/olcrtc/internal/runtime"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/tunnelcore"
)

const connectCommand = "connect"

var (
	ErrKeyRequired         = runtime.ErrKeyRequired
	ErrKeySize             = runtime.ErrKeySize
	ErrSocks5AuthFailed    = errors.New("SOCKS5 auth failed")
	ErrSocks5ConnectFailed = errors.New("SOCKS5 connect failed")
	ErrInvalidTarget       = errors.New("invalid connect target")
	// ErrKeysFileNeedsPeerTransport is returned when crypto.keys_file is used
	// with a transport that does not route traffic per peer.
	ErrKeysFileNeedsPeerTransport = errors.New("crypto.keys_file requires a per-peer transport such as vp8channel")
)

// SessionOpenFunc is called after a successful handshake.
type SessionOpenFunc func(sessionID, deviceID string, claims map[string]any)

// SessionCloseFunc is called when a session is torn down.
type SessionCloseFunc func(sessionID, reason string)

// TrafficFunc is called once per tunnel stream after both copy loops finish.
type TrafficFunc func(sessionID, addr string, bytesIn, bytesOut uint64)

// HealthFunc is called when the server control health snapshot changes.
type HealthFunc func(control.Status)

// Server handles incoming tunnel connections and proxies their traffic.
type Server struct {
	baseCtx context.Context //nolint:containedctx // server-lifetime context for reconnect goroutines
	ln      transport.Transport
	peerLn  transport.PeerTransport
	keys    *crypto.KeySet
	ring    *KeyRing // non-nil in crypto.keys_file mode
	pair    *tunnelcore.SessionPair
	conn    *muxconn.Conn

	controlConn *muxconn.Conn
	session     *smux.Session
	controlSess *smux.Session
	controlStrm *smux.Stream
	controlStop context.CancelFunc
	sessMu      sync.RWMutex

	peerSessions map[string]*peerSession
	unknownMu    sync.Mutex
	unknownPeers map[string]time.Time // peerID -> last failed key lookup (ring mode)
	// peerLimitWarn rate-limits the peer-cap warning.
	peerLimitWarn atomic.Int64
	peersMu       sync.Mutex
	peerStats     map[string]peerStat
	reinstallMu   sync.Mutex
	wg            sync.WaitGroup
	authHook      handshake.AuthFunc
	onOpen        SessionOpenFunc
	onClose       SessionCloseFunc
	onTraffic     TrafficFunc
	deviceID      string
	sessionID     string

	dnsServer      string
	resolver       *net.Resolver
	socksProxyAddr string
	socksProxyPort int
	socksProxyUser string
	socksProxyPass string
	liveness       control.Config
	health         *runtime.HealthTracker
	state          stateGate
	done           chan struct{}
	doneOnce       sync.Once
}

// Config holds runtime configuration for [Run].
type Config struct {
	Transport        string
	Provider         string
	RoomURL          string
	ChannelID        string
	KeyHex           string
	KeysFile         string
	DNSServer        string
	Resolver         *net.Resolver
	SOCKSProxyAddr   string
	SOCKSProxyPort   int
	SOCKSProxyUser   string
	SOCKSProxyPass   string
	TransportOptions transport.Options
	Engine           string
	URL              string
	Token            string
	ProviderToken    string
	Liveness         control.Config
	Traffic          transport.TrafficConfig
	AuthHook         handshake.AuthFunc
	OnSessionOpen    SessionOpenFunc
	OnSessionClose   SessionCloseFunc
	OnTraffic        TrafficFunc
	OnHealth         HealthFunc
}

// setupServerKeys returns the key set for the broadcast link and, in
// keys_file mode, the per-user ring. In ring mode the broadcast link gets a
// random throwaway key, so only peer-routed clients holding a ring key can
// reach the tunnel.
func setupServerKeys(cfg Config) (*crypto.KeySet, *KeyRing, error) {
	if cfg.KeysFile == "" {
		keys, err := tunnelcore.SetupKeySet(cfg.KeyHex, crypto.Server)
		if err != nil {
			return nil, nil, fmt.Errorf("setup key set: %w", err)
		}
		return keys, nil, nil
	}
	ring, err := LoadKeyRing(cfg.KeysFile)
	if err != nil {
		return nil, nil, err
	}
	var throwaway [32]byte
	if _, err := crand.Read(throwaway[:]); err != nil {
		return nil, nil, fmt.Errorf("throwaway key: %w", err)
	}
	keys, err := crypto.NewKeySet(throwaway[:], crypto.Server)
	if err != nil {
		return nil, nil, fmt.Errorf("setup key set: %w", err)
	}
	return keys, ring, nil
}

// Run starts the server with the given configuration.
func Run(ctx context.Context, cfg Config) error {
	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	keys, ring, err := setupServerKeys(cfg)
	if err != nil {
		return err
	}
	hook := cfg.AuthHook
	if hook == nil {
		hook = defaultAuthHook
	}
	onOpen := cfg.OnSessionOpen
	if onOpen == nil {
		onOpen = func(string, string, map[string]any) {}
	}
	onClose := cfg.OnSessionClose
	if onClose == nil {
		onClose = func(string, string) {}
	}
	onTraffic := cfg.OnTraffic
	if onTraffic == nil {
		onTraffic = func(string, string, uint64, uint64) {}
	}
	s := &Server{
		keys: keys, ring: ring, unknownPeers: make(map[string]time.Time), authHook: hook, onOpen: onOpen, onClose: onClose, onTraffic: onTraffic,
		dnsServer: cfg.DNSServer, resolver: tunnelcore.Resolver(cfg.Resolver, cfg.DNSServer),
		socksProxyAddr: cfg.SOCKSProxyAddr, socksProxyPort: cfg.SOCKSProxyPort,
		socksProxyUser: cfg.SOCKSProxyUser, socksProxyPass: cfg.SOCKSProxyPass,
		liveness: cfg.Liveness, health: runtime.NewHealthTracker(cfg.OnHealth),
		peerSessions: make(map[string]*peerSession), peerStats: make(map[string]peerStat),
		done: make(chan struct{}),
	}
	defer func() {
		s.shutdown()
		s.wg.Wait()
	}()
	if err := s.bringUpLink(runCtx, cfg, cancel); err != nil {
		return err
	}
	if s.ring != nil {
		if s.peerLn == nil {
			return ErrKeysFileNeedsPeerTransport
		}
		logger.Infof("keys file mode: %d key(s) from %s", s.ring.Len(), cfg.KeysFile)
		s.goTracked(func() { s.watchKeyRing(runCtx) })
	}
	go func() {
		<-runCtx.Done()
		s.closeSession()
	}()
	s.serve(runCtx)
	return nil
}
