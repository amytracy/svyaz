#!/bin/sh
# Builds app/libs/gocore.aar from gocore/ with gomobile.
# -checklinkname=0: required by wlynxg/anet, which go-libp2p uses to list
# network interfaces on Android 11+, where netlink access is restricted.
set -eu
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
: "${ANDROID_NDK_HOME:=$ANDROID_HOME/ndk/29.0.14206865}"
: "${JAVA_HOME:=/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME ANDROID_NDK_HOME JAVA_HOME
PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$(/opt/homebrew/bin/go env GOPATH)/bin:$PATH"
export PATH
mkdir -p app/libs
cd gocore
exec gomobile bind \
  -target=android/arm64,android/amd64 \
  -androidapi 26 \
  -trimpath \
  -ldflags='-s -w -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384' \
  -o ../app/libs/gocore.aar \
  .
