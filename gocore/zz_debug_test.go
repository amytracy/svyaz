package gocore

import (
	"context"
	"fmt"
	"strings"
	"testing"
	"time"

	relay "github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/relay"
	ma "github.com/multiformats/go-multiaddr"
)

func TestZZ(t *testing.T) {
	hub := testNode(t)
	s, _ := relay.New(hub.h)
	defer s.Close()
	port, _ := hub.h.Addrs()[0].ValueForProtocol(ma.P_TCP)
	cfg := Config{Bootstrap: "/dns4/localhost/tcp/" + port + "/ws/p2p/" + hub.h.ID().String(), PeerList: "off"}
	n, _, err := newMobileNode(t.TempDir(), cfg, []string{"/ip4/127.0.0.1/tcp/0"})
	if err != nil { t.Fatal(err) }
	defer n.h.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go n.maintain(ctx)
	for i := 0; i < 12; i++ {
		time.Sleep(2 * time.Second)
		ps, _ := n.h.Peerstore().GetProtocols(hub.h.ID())
		var hopped bool
		for _, p := range ps { if strings.Contains(string(p), "relay/0.2.0/hop") { hopped = true } }
		fmt.Println(i, "addrs", n.h.Addrs(), "listen", n.h.Network().ListenAddresses(), "conns", len(n.h.Network().Conns()), "hop", hopped, "hub-addrs", n.h.Peerstore().Addrs(hub.h.ID()))
	}
}
