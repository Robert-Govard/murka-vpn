#!/usr/bin/env bash
# Downloads geoip.dat and geosite.dat for Xray into the Android assets.
# Remnawave routing rules use geosite:category-ru, geoip:ru, geoip:private.
set -euo pipefail

XRAY_VERSION="${XRAY_VERSION:-v26.3.27}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/androidApp/src/main/assets/xray"
ZIP="Xray-linux-64.zip"
URL="https://github.com/XTLS/Xray-core/releases/download/$XRAY_VERSION/$ZIP"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

curl -fsSL -o "$tmp/$ZIP" "$URL"
curl -fsSL -o "$tmp/$ZIP.dgst" "$URL.dgst"

want="$(grep -i '^SHA2-256' "$tmp/$ZIP.dgst" | sed -E 's/.*= *//' | tr 'A-F' 'a-f')"
if command -v sha256sum >/dev/null 2>&1; then
  got="$(sha256sum "$tmp/$ZIP" | cut -d' ' -f1)"
else
  got="$(shasum -a 256 "$tmp/$ZIP" | cut -d' ' -f1)"
fi
if [ -z "$want" ] || [ "$want" != "$got" ]; then
  echo "checksum mismatch for $ZIP: want '$want', got '$got'" >&2
  exit 1
fi

mkdir -p "$DEST"
unzip -o -q -j "$tmp/$ZIP" geoip.dat geosite.dat -d "$DEST"
echo "$XRAY_VERSION" > "$DEST/VERSION"
ls -l "$DEST"
