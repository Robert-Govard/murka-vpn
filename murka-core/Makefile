GOMOBILE ?= $(HOME)/go/bin/gomobile
PKGS := github.com/openlibrecommunity/olcrtc/mobile ./xraymobile
LDFLAGS := -s -w -checklinkname=0

test:
	go vet ./... && go test -race ./...

# Android AAR with both cores (needs Android SDK + NDK, ANDROID_HOME set).
aar:
	$(GOMOBILE) bind -target=android/arm,android/arm64,android/amd64 -androidapi 21 \
		-ldflags "$(LDFLAGS)" -o bin/murka-core.aar $(PKGS)

# Smoke check that the API binds (needs Xcode).
macos:
	$(GOMOBILE) bind -target=macos -ldflags "$(LDFLAGS)" -o bin/MurkaCore.xcframework $(PKGS)

.PHONY: test aar macos
