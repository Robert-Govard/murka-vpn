#!/bin/sh
# Builds HevSocks5Tunnel.xcframework (iOS device + simulator) for the packet tunnel
# extension. Skips the build when the framework is newer than the hev sources.
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/androidApp/src/main/jni/hev-socks5-tunnel"
OUT="$ROOT/sharedUI/build/generated/hev/ios"
FRAMEWORK="$OUT/HevSocks5Tunnel.xcframework"

if [ -d "$FRAMEWORK" ] && [ -z "$(find "$SRC" -newer "$FRAMEWORK" -type f -print -quit)" ]; then
    echo "HevSocks5Tunnel.xcframework is up to date"
    exit 0
fi

WORK="$OUT/work"
rm -rf "$WORK" "$FRAMEWORK"
mkdir -p "$WORK"

# $1 sdk, $2 arch, $3 min-version flag
build() {
    tree="$WORK/src-$1-$2"
    rsync -a --exclude .git "$SRC/" "$tree/"
    cc="xcrun --sdk $1 clang"
    make -C "$tree" PP="$cc" CC="$cc" \
        CFLAGS="-arch $2 $3 -Wno-error" LFLAGS="-arch $2 $3 -Wl,-Bsymbolic-functions" static >/dev/null
    mkdir -p "$WORK/$1-$2"
    libtool -static -o "$WORK/$1-$2/libhev-socks5-tunnel.a" \
        "$tree/bin/libhev-socks5-tunnel.a" \
        "$tree/third-part/lwip/bin/liblwip.a" \
        "$tree/third-part/yaml/bin/libyaml.a" \
        "$tree/third-part/hev-task-system/bin/libhev-task-system.a"
}

build iphoneos arm64 -mios-version-min=15.0
build iphonesimulator arm64 -mios-simulator-version-min=15.0

mkdir -p "$WORK/include"
cp "$SRC/src/hev-main.h" "$SRC/module.modulemap" "$WORK/include/"
xcodebuild -create-xcframework \
    -library "$WORK/iphoneos-arm64/libhev-socks5-tunnel.a" -headers "$WORK/include" \
    -library "$WORK/iphonesimulator-arm64/libhev-socks5-tunnel.a" -headers "$WORK/include" \
    -output "$FRAMEWORK"
rm -rf "$WORK"
