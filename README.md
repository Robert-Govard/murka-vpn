# murka-core

Go library for the Murka VPN app: both tunnel cores in one gomobile build.
Android can load only one Go runtime per app, so olcRTC and Xray are bound together.

| Package | What it is |
|---|---|
| `github.com/openlibrecommunity/olcrtc/mobile` | olcRTC (our fork in `../olcrtc`, branch `emergency/per-user-keys`), unchanged API: `Mobile.new()` → `Runtime` |
| `murka-core/xraymobile` | Xray-core 26.3.27 (`github.com/xtls/xray-core v1.260327.0`) |

`xraymobile` API:

- `New() *Runtime`; `Runtime.Start(configJSON) error`, `Stop() error`, `IsRunning() bool` — one Xray instance from a full JSON config.
- `SetProtector(SocketProtector)` — process-wide; `Protect(fd int) bool`, same contract as olcRTC. Hooks Xray's dialer (TCP) and listener (UDP). Xray only logs a refused `Protect` and keeps the socket.
- `SetAssetDir(dir)` — directory with `geoip.dat` and `geosite.dat`. Take both from the Xray-core release zip of the same version (26.3.27); Remnawave routing rules use `geosite:category-ru`, `geoip:ru`, `geoip:private`.
- `Check(configJSON, url, timeoutMillis) (int, error)` — replaces the config's inbounds with a SOCKS inbound on a free loopback port, fetches `url` through a temporary Xray, returns the time in ms.
- `Version() string`.

## Build

```bash
make test    # go vet + go test -race
make aar     # bin/murka-core.aar for Android (needs Android SDK + NDK, ANDROID_HOME)
make macos   # bin/MurkaCore.xcframework, smoke check that the API binds (needs Xcode)
```

`gomobile`/`gobind`: `go install golang.org/x/mobile/cmd/gomobile@latest golang.org/x/mobile/cmd/gobind@latest && gomobile init`.
The olcRTC module is taken from `../olcrtc` (`replace` in `go.mod`).
