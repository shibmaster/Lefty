#!/usr/bin/env bash
# Builds the WireGuard bridge (wireguard-go + gVisor netstack) into an Android AAR with gomobile and
# publishes it to the project-local Maven repo wgbridge/repo, where Gradle picks it up.
#
# Needs: Go (default ~/.local/go), gomobile + gobind (`go install golang.org/x/mobile/cmd/gomobile@latest
# golang.org/x/mobile/cmd/gobind@latest`), and the Android SDK/NDK (ANDROID_HOME, NDK 29 by default).
# Only arm64 and x86_64 (emulator) are built; other devices report the tunnel as unsupported.
set -euo pipefail

cd "$(dirname "$0")"
export PATH="${GOROOT_BIN:-$HOME/.local/go/bin}:$HOME/go/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/29.0.14206865}"
# gVisor is heavy to compile; two parallel jobs keep peak RAM manageable.
export GOFLAGS="${GOFLAGS:--p=2}"

GROUP_PATH="ai/shibmaster/lefty"
ARTIFACT="wgbridge"
VERSION="1.0.0"
OUT="repo/$GROUP_PATH/$ARTIFACT/$VERSION"

mkdir -p build "$OUT"
gomobile bind \
  -target=android/arm64,android/amd64 \
  -androidapi 26 \
  -javapkg ai.shibmaster.lefty \
  -ldflags="-s -w" \
  -trimpath \
  -o "build/$ARTIFACT.aar" \
  .

cp "build/$ARTIFACT.aar" "$OUT/$ARTIFACT-$VERSION.aar"
cat > "$OUT/$ARTIFACT-$VERSION.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>ai.shibmaster.lefty</groupId>
  <artifactId>$ARTIFACT</artifactId>
  <version>$VERSION</version>
  <packaging>aar</packaging>
</project>
POM
echo "Published $OUT/$ARTIFACT-$VERSION.aar ($(du -h "$OUT/$ARTIFACT-$VERSION.aar" | cut -f1))"
