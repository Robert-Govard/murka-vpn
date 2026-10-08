// Package xraymobile runs Xray-core inside the Murka VPN app. It is bound
// with gomobile next to olcRTC's mobile package.
package xraymobile

import (
	"errors"
	"fmt"
	"os"
	"strings"
	"sync"

	"github.com/xtls/xray-core/common/platform"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	_ "github.com/xtls/xray-core/main/distro/all"
)

// ErrRunning is returned by Start when the runtime already runs.
var ErrRunning = errors.New("xray is already running")

// Runtime owns one Xray instance.
type Runtime struct {
	mu       sync.Mutex
	instance *core.Instance
}

func New() *Runtime { return &Runtime{} }

// Start runs Xray with a full JSON config.
func (r *Runtime) Start(configJSON string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.instance != nil {
		return ErrRunning
	}
	inst, err := startInstance(configJSON)
	if err != nil {
		return err
	}
	r.instance = inst
	return nil
}

// Stop closes the running instance. Stopping a stopped runtime is a no-op.
func (r *Runtime) Stop() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.instance == nil {
		return nil
	}
	err := r.instance.Close()
	r.instance = nil
	if err != nil {
		return fmt.Errorf("stop xray: %w", err)
	}
	return nil
}

func (r *Runtime) IsRunning() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.instance != nil
}

// Version returns the Xray-core version, e.g. "26.3.27".
func Version() string { return core.Version() }

// SetAssetDir points Xray at the directory with geoip.dat and geosite.dat.
func SetAssetDir(dir string) {
	_ = os.Setenv(platform.AssetLocation, dir)
}

func startInstance(configJSON string) (*core.Instance, error) {
	if err := installController(); err != nil {
		return nil, fmt.Errorf("install socket protector: %w", err)
	}
	cfg, err := serial.LoadJSONConfig(strings.NewReader(configJSON))
	if err != nil {
		return nil, fmt.Errorf("load xray config: %w", err)
	}
	inst, err := core.New(cfg)
	if err != nil {
		return nil, fmt.Errorf("create xray: %w", err)
	}
	if err := inst.Start(); err != nil {
		_ = inst.Close()
		return nil, fmt.Errorf("start xray: %w", err)
	}
	return inst, nil
}
