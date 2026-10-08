package xraymobile

import (
	"fmt"
	"net"
	"testing"
)

// remnawaveLike mimics a Remnawave config: fixed inbounds that Check must not bind.
func remnawaveLike(inPort int) string {
	return fmt.Sprintf(`{
  "remarks": "test",
  "inbounds": [{"listen": "127.0.0.1", "port": %d, "protocol": "socks", "settings": {"udp": true}}],
  "outbounds": [{"protocol": "freedom", "tag": "proxy"}]
}`, inPort)
}

func TestCheckMeasuresThroughConfig(t *testing.T) {
	srv := target(t)
	inPort := freePort(t)
	ms, err := Check(remnawaveLike(inPort), srv.URL, 5000)
	if err != nil {
		t.Fatal(err)
	}
	if ms < 0 || ms > 5000 {
		t.Fatalf("latency %d ms", ms)
	}
	// The config's own inbound port stayed free.
	l, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", inPort))
	if err != nil {
		t.Fatalf("Check bound the config inbound port: %v", err)
	}
	_ = l.Close()
}

func TestCheckFailsForDeadTarget(t *testing.T) {
	dead := fmt.Sprintf("http://127.0.0.1:%d/", freePort(t))
	if _, err := Check(remnawaveLike(freePort(t)), dead, 3000); err == nil {
		t.Fatal("want error for unreachable target")
	}
}

func TestCheckRejectsBadJSON(t *testing.T) {
	if _, err := Check(`[`, "http://127.0.0.1/", 1000); err == nil {
		t.Fatal("want error for broken JSON")
	}
}
