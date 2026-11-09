package gocore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"time"

	libp2p "github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/peerstore"
	"github.com/libp2p/go-libp2p/p2p/host/autorelay"
	"github.com/libp2p/go-libp2p/p2p/security/noise"
	quic "github.com/libp2p/go-libp2p/p2p/transport/quic"
	"github.com/libp2p/go-libp2p/p2p/transport/tcp"
	"github.com/libp2p/go-libp2p/p2p/transport/websocket"
	ma "github.com/multiformats/go-multiaddr"
)

// mobileListenAddrs uses OS-assigned ports so that nothing can collide on a
// phone. WebSocket stays dial-only: phones reach public nodes over WS/WSS.
var mobileListenAddrs = []string{
	"/ip4/0.0.0.0/tcp/0",
	"/ip6/::/tcp/0",
	"/ip4/0.0.0.0/udp/0/quic-v1",
	"/ip6/::/udp/0/quic-v1",
}

func parseBootstrap(list string) ([]peer.AddrInfo, error) {
	var out []peer.AddrInfo
	for _, s := range strings.Split(list, ",") {
		s = strings.TrimSpace(s)
		if s == "" {
			continue
		}
		a, err := ma.NewMultiaddr(s)
		if err != nil {
			return nil, err
		}
		ai, err := peer.AddrInfoFromP2pAddr(a)
		if err != nil {
			return nil, err
		}
		out = append(out, *ai)
	}
	if len(out) > 16 {
		return nil, errors.New("at most 16 bootstrap nodes")
	}
	return out, nil
}

func prepareDir(dir string) error {
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	return os.Chmod(dir, 0700)
}

func loadState(n *Node) error {
	raw, err := os.ReadFile(filepath.Join(n.dir, "state.json"))
	if err == nil {
		if err = json.Unmarshal(raw, &n.state); err != nil {
			return fmt.Errorf("state.json: %w", err)
		}
	} else if !os.IsNotExist(err) {
		return err
	}
	if len(n.state.Messages) > maxHistory {
		return errors.New("state history exceeds limit")
	}
	raw, err = os.ReadFile(filepath.Join(n.dir, "sequence.json"))
	if err == nil {
		var seq uint64
		if err = json.Unmarshal(raw, &seq); err != nil {
			return fmt.Errorf("sequence.json: %w", err)
		}
		n.state.Sequence = max(n.state.Sequence, seq)
	} else if !os.IsNotExist(err) {
		return err
	}
	return nil
}

// newMobileNode is the mobile counterpart of run() in the desktop main.go:
// the same protocols and AutoRelay wiring, but without the relay and AutoNAT
// services, UPnP, fixed ports, the terminal UI and the control socket.
func newMobileNode(dir string, cfg Config, listen []string) (*Node, string, error) {
	if err := prepareDir(dir); err != nil {
		return nil, "", err
	}
	identity, err := loadKey(dir)
	if err != nil {
		return nil, "", err
	}
	n := &Node{key: identity, dir: dir, routes: map[peer.ID]Route{}, delivery: map[string]PeerDelivery{}, wake: make(chan struct{}, 1), sendWake: make(chan struct{}, 1), sem: make(chan struct{}, 64), changes: make(chan struct{}, 1), reach: network.ReachabilityUnknown}
	if err = loadState(n); err != nil {
		return nil, "", err
	}
	if n.boots, err = parseBootstrap(cfg.Bootstrap); err != nil {
		return nil, "", err
	}
	var hostRef atomic.Pointer[host.Host]
	opts := []libp2p.Option{
		libp2p.Identity(identity),
		libp2p.NoTransports,
		libp2p.Transport(websocket.New),
		libp2p.Transport(tcp.NewTCPTransport),
		libp2p.Transport(quic.NewTransport),
		libp2p.Security(noise.ID, noise.New),
		libp2p.EnableRelay(),
		libp2p.EnableHolePunching(),
		libp2p.UserAgent("p2pmessenger-android/0.1"),
		libp2p.ListenAddrStrings(listen...),
		libp2p.AddrsFactory(localAddrsFactory(&hostRef)),
	}
	// Phones sit behind NAT or CGNAT. AutoNAT often stays Unknown there (it needs
	// several independent servers), and only a private node reserves relay slots.
	switch cfg.Reachability {
	case "", "private":
		opts = append(opts, libp2p.ForceReachabilityPrivate())
		n.reach = network.ReachabilityPrivate
	case "auto":
	default:
		return nil, "", errors.New("invalid reachability")
	}
	source := peerListSource(cfg)
	if source != "" || len(n.boots) > 0 {
		if source == "" {
			opts = append(opts, libp2p.EnableAutoRelayWithStaticRelays(n.boots, autorelay.WithNumRelays(min(3, len(n.boots))), autorelay.WithBootDelay(3*time.Second), autorelay.WithBackoff(15*time.Second)))
		} else {
			opts = append(opts, libp2p.EnableAutoRelayWithPeerSource(func(ctx context.Context, num int) <-chan peer.AddrInfo {
				peers := n.bootstrapPeers()
				out := make(chan peer.AddrInfo, min(num, len(peers)))
				defer close(out)
				for _, p := range peers[:min(num, len(peers))] {
					select {
					case out <- p:
					case <-ctx.Done():
						return out
					}
				}
				return out
			}, autorelay.WithNumRelays(3), autorelay.WithMinCandidates(1), autorelay.WithMaxCandidates(16), autorelay.WithMinInterval(5*time.Second), autorelay.WithBootDelay(3*time.Second), autorelay.WithBackoff(15*time.Second)))
		}
	}
	if n.h, err = libp2p.New(opts...); err != nil {
		return nil, "", err
	}
	hostRef.Store(&n.h)
	for _, b := range n.boots {
		n.h.Peerstore().AddAddrs(b.ID, b.Addrs, peerstore.PermanentAddrTTL)
	}
	n.h.SetStreamHandler(directoryProtocol, n.bounded(n.handleDirectory))
	n.h.SetStreamHandler(messageProtocol, n.bounded(n.handleMessage))
	return n, source, nil
}
