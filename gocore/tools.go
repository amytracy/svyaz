//go:build tools

// Keeps golang.org/x/mobile/bind in go.mod: gomobile bind imports it at build time.
package gocore

import _ "golang.org/x/mobile/bind"
