package xraymobile

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/xtls/xray-core/app/router"
	"google.golang.org/protobuf/proto"
)

const privateBlockRule = `,
  "routing": {"rules": [{"ip": ["geoip:private"], "outboundTag": "block"}]}`

// writeGeoIP writes a minimal geoip.dat with PRIVATE = 127.0.0.0/8.
func writeGeoIP(t *testing.T, dir string) {
	t.Helper()
	list := &router.GeoIPList{Entry: []*router.GeoIP{{
		CountryCode: "PRIVATE",
		Cidr:        []*router.CIDR{{Ip: []byte{127, 0, 0, 0}, Prefix: 8}},
	}}}
	b, err := proto.Marshal(list)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "geoip.dat"), b, 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestAssetDirIsUsed(t *testing.T) {
	srv := target(t)
	empty := t.TempDir()
	SetAssetDir(empty)
	t.Cleanup(func() { _ = os.Unsetenv("xray.location.asset") })

	r := New()
	if err := r.Start(socksConfig(freePort(t), privateBlockRule)); err == nil {
		_ = r.Stop()
		t.Fatal("start must fail without geoip.dat")
	}

	dir := t.TempDir()
	writeGeoIP(t, dir)
	SetAssetDir(dir)
	port := freePort(t)
	if err := r.Start(socksConfig(port, privateBlockRule)); err != nil {
		t.Fatal(err)
	}
	defer r.Stop()
	if _, err := getVia(port, srv.URL); err == nil {
		t.Fatal("127.0.0.1 must be blocked by geoip:private from our geoip.dat")
	}
}
