package gocore

import (
	"sync/atomic"

	"github.com/libp2p/go-libp2p/core/host"
	ma "github.com/multiformats/go-multiaddr"
	manet "github.com/multiformats/go-multiaddr/net"
	"github.com/wlynxg/anet"
)

// SetAndroidVersion tells anet which Android release it runs on. Call it
// before Start: Android 11+ needs its netlink workaround.
func SetAndroidVersion(release int) {
	if release > 0 {
		anet.SetAndroidVersion(uint(release))
	}
}

func interfaceMultiaddrs() []ma.Multiaddr {
	addrs, err := anet.InterfaceAddrs()
	if err != nil {
		return nil
	}
	out := make([]ma.Multiaddr, 0, len(addrs))
	for _, a := range addrs {
		m, err := manet.FromNetAddr(a)
		if err != nil || manet.IsIPLoopback(m) || manet.IsIP6LinkLocal(m) {
			continue
		}
		out = append(out, m)
	}
	return out
}

func isDirectListenAddr(a ma.Multiaddr) bool {
	if _, err := a.ValueForProtocol(ma.P_CIRCUIT); err == nil {
		return false
	}
	return manet.IsIPUnspecified(a)
}

// expandListenAddrs turns wildcard listeners into addresses of real interfaces.
func expandListenAddrs(listen, interfaces []ma.Multiaddr) []ma.Multiaddr {
	var out []ma.Multiaddr
	for _, l := range listen {
		if !isDirectListenAddr(l) {
			continue
		}
		resolved, err := manet.ResolveUnspecifiedAddress(l, interfaces)
		if err == nil {
			out = append(out, resolved...)
		}
	}
	return out
}

func mergeAddrs(base, extra []ma.Multiaddr) []ma.Multiaddr {
	out := append([]ma.Multiaddr{}, base...)
	for _, e := range extra {
		known := false
		for _, a := range out {
			if a.Equal(e) {
				known = true
				break
			}
		}
		if !known {
			out = append(out, e)
		}
	}
	return out
}

// localAddrsFactory works around net.InterfaceAddrs failing on Android 11+:
// go-libp2p then drops every wildcard listener and the node has no direct
// addresses to publish or to offer for hole punching.
func localAddrsFactory(ref *atomic.Pointer[host.Host]) func([]ma.Multiaddr) []ma.Multiaddr {
	return func(addrs []ma.Multiaddr) []ma.Multiaddr {
		h := ref.Load()
		if h == nil {
			return addrs
		}
		return mergeAddrs(addrs, expandListenAddrs((*h).Network().ListenAddresses(), interfaceMultiaddrs()))
	}
}
