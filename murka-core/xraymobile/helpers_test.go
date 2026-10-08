package xraymobile

import (
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"
)

// freePort returns a TCP port that was free a moment ago.
func freePort(t *testing.T) int {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}

// target is a local HTTP server that answers "ok".
func target(t *testing.T) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, "ok")
	}))
	t.Cleanup(srv.Close)
	return srv
}

// socksConfig is an Xray config with one SOCKS inbound and a direct outbound.
func socksConfig(port int, extra string) string {
	return fmt.Sprintf(`{
  "log": {"loglevel": "warning"},
  "inbounds": [{"listen": "127.0.0.1", "port": %d, "protocol": "socks", "settings": {"auth": "noauth", "udp": true}}],
  "outbounds": [{"protocol": "freedom", "tag": "direct"}, {"protocol": "blackhole", "tag": "block"}]%s
}`, port, extra)
}

// getVia fetches u through the SOCKS5 proxy on port.
func getVia(port int, u string) (string, error) {
	proxy, _ := url.Parse(fmt.Sprintf("socks5://127.0.0.1:%d", port))
	c := &http.Client{Timeout: 5 * time.Second, Transport: &http.Transport{Proxy: http.ProxyURL(proxy)}}
	resp, err := c.Get(u)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(resp.Body)
	return string(b), err
}
