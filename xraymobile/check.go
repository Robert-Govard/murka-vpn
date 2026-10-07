package xraymobile

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"time"
)

// Check runs a temporary Xray with the server config, fetches target
// through it and returns the response time in milliseconds.
func Check(configJSON, target string, timeoutMillis int) (int, error) {
	var cfg map[string]any
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return 0, fmt.Errorf("parse config: %w", err)
	}
	port, err := pickPort()
	if err != nil {
		return 0, err
	}
	cfg["inbounds"] = []any{map[string]any{
		"tag": "check-in", "listen": "127.0.0.1", "port": port, "protocol": "socks",
		"settings": map[string]any{"auth": "noauth", "udp": false},
	}}
	data, err := json.Marshal(cfg)
	if err != nil {
		return 0, err
	}
	inst, err := startInstance(string(data))
	if err != nil {
		return 0, err
	}
	defer inst.Close()

	proxy, _ := url.Parse(fmt.Sprintf("socks5://127.0.0.1:%d", port))
	client := &http.Client{
		Timeout:   time.Duration(timeoutMillis) * time.Millisecond,
		Transport: &http.Transport{Proxy: http.ProxyURL(proxy), DisableKeepAlives: true},
	}
	start := time.Now()
	resp, err := client.Get(target)
	if err != nil {
		return 0, fmt.Errorf("check %s: %w", target, err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode >= 500 {
		return 0, fmt.Errorf("check %s: HTTP %d", target, resp.StatusCode)
	}
	return int(time.Since(start).Milliseconds()), nil
}

func pickPort() (int, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, fmt.Errorf("pick port: %w", err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port, nil
}
