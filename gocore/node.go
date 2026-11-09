// Network core of the Android client. This file is a verbatim copy of the
// network part of main.go from the commit recorded in UPSTREAM. Local changes
// are limited to the change hooks marked "android:". Keep signed JSON types
// and field order untouched: signatures cover Go's encoding/json output.
package gocore

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"

	libp2p "github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/event"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/protocol"
	"github.com/libp2p/go-libp2p/p2p/protocol/ping"
	"github.com/libp2p/go-libp2p/p2p/security/noise"
	"github.com/libp2p/go-libp2p/p2p/transport/websocket"
	ma "github.com/multiformats/go-multiaddr"
	manet "github.com/multiformats/go-multiaddr/net"
)

const (
	directoryProtocol protocol.ID = "/p2pmessenger/directory/1.0.0"
	messageProtocol   protocol.ID = "/p2pmessenger/message/1.0.0"
	maxFrame                      = 128 << 10
	maxHistory                    = 10000
	defaultPeerList               = "https://raw.githubusercontent.com/SolverNA/p2p-messenger-over-http/main/public-peers.txt"
	maxPublicPeers                = 128
)

type RouteBody struct {
	Domain    string   `json:"domain"`
	Peer      string   `json:"peer"`
	PublicKey []byte   `json:"public_key"`
	Addresses []string `json:"addresses"`
	Sequence  uint64   `json:"sequence"`
	Expires   int64    `json:"expires"`
}
type Route struct {
	Body      RouteBody `json:"body"`
	Signature []byte    `json:"signature"`
}
type MessageBody struct {
	Domain string `json:"domain"`
	ID     string `json:"id"`
	From   string `json:"from"`
	To     string `json:"to"`
	Text   string `json:"text"`
	Time   int64  `json:"time"`
}
type Message struct {
	Body      MessageBody `json:"body"`
	Signature []byte      `json:"signature"`
}
type Entry struct {
	Message Message `json:"message"`
	Status  string  `json:"status"`
	Error   string  `json:"error,omitempty"`
}
type DiskState struct {
	Sequence uint64  `json:"sequence"`
	Messages []Entry `json:"messages"`
}
type Request struct {
	Op     string `json:"op"`
	Peer   string `json:"peer,omitempty"`
	Hops   int    `json:"hops,omitempty"`
	Record *Route `json:"record,omitempty"`
}
type Response struct {
	Record *Route `json:"record,omitempty"`
	Error  string `json:"error,omitempty"`
}
type Ack struct {
	ID    string `json:"id"`
	Error string `json:"error,omitempty"`
}
type Node struct {
	h          host.Host
	key        crypto.PrivKey
	dir        string
	boots      []peer.AddrInfo
	discovered []peer.AddrInfo
	discovery  DiscoveryStatus
	mu         sync.Mutex
	state      DiskState
	routes     map[peer.ID]Route
	delivery   map[string]PeerDelivery
	reach      network.Reachability
	wake       chan struct{}
	sendWake   chan struct{}
	sem        chan struct{}
	changes    chan struct{}       // android: coalesced UI change signal
	onMessage  func(m Message)     // android: new incoming message, already persisted
}

func bytesOf(v any) []byte { b, _ := json.Marshal(v); return b }
func randomID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}
func atomicWrite(path string, data []byte) error {
	f, err := os.CreateTemp(filepath.Dir(path), ".write-*")
	if err != nil {
		return err
	}
	name := f.Name()
	defer os.Remove(name)
	if err = f.Chmod(0600); err == nil {
		_, err = f.Write(data)
	}
	if err == nil {
		err = f.Sync()
	}
	cerr := f.Close()
	if err == nil {
		err = cerr
	}
	if err != nil {
		return err
	}
	if err = os.Rename(name, path); err != nil {
		return err
	}
	// Windows cannot FlushFileBuffers on a read-only directory handle. The
	// temporary file itself has already been synced before replacing the target.
	if runtime.GOOS == "windows" {
		return nil
	}
	d, err := os.Open(filepath.Dir(path))
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}
func loadKey(dir string) (crypto.PrivKey, error) {
	path := filepath.Join(dir, "identity.key")
	b, err := os.ReadFile(path)
	if err == nil {
		return crypto.UnmarshalPrivateKey(b)
	}
	if !os.IsNotExist(err) {
		return nil, err
	}
	k, _, err := crypto.GenerateEd25519Key(rand.Reader)
	if err != nil {
		return nil, err
	}
	b, err = crypto.MarshalPrivateKey(k)
	if err != nil {
		return nil, err
	}
	return k, atomicWrite(path, b)
}

// Caller holds n.mu. A delivery is acknowledged only after this succeeds.
func (n *Node) saveLocked() error {
	return atomicWrite(filepath.Join(n.dir, "state.json"), bytesOf(n.state))
}
func notify(ch chan struct{}) {
	select {
	case ch <- struct{}{}:
	default:
	}
}
func (n *Node) localRoute() (Route, error) {
	n.mu.Lock()
	defer n.mu.Unlock()
	seq := uint64(time.Now().UnixNano())
	if seq <= n.state.Sequence {
		seq = n.state.Sequence + 1
	}
	n.state.Sequence = seq
	// Address refreshes should write a tiny counter, not rewrite the entire chat
	// history every 30 seconds. Keep reading the old counter for compatibility.
	if err := atomicWrite(filepath.Join(n.dir, "sequence.json"), bytesOf(seq)); err != nil {
		return Route{}, err
	}
	pub, err := crypto.MarshalPublicKey(n.key.GetPublic())
	if err != nil {
		return Route{}, err
	}
	r := Route{Body: RouteBody{Domain: "p2pmessenger-route-v1", Peer: n.h.ID().String(), PublicKey: pub, Sequence: seq, Expires: time.Now().Add(3 * time.Minute).Unix()}}
	for _, a := range n.h.Addrs() {
		r.Body.Addresses = append(r.Body.Addresses, a.String())
	}
	if len(r.Body.Addresses) > 32 {
		r.Body.Addresses = r.Body.Addresses[:32]
	}
	sort.Strings(r.Body.Addresses)
	r.Signature, err = n.key.Sign(bytesOf(r.Body))
	if err == nil {
		n.routes[n.h.ID()] = r
	}
	return r, err
}
func validateRoute(r Route) (peer.AddrInfo, error) {
	b := r.Body
	if b.Domain != "p2pmessenger-route-v1" || b.Sequence == 0 || b.Expires <= time.Now().Unix() || b.Expires > time.Now().Add(5*time.Minute).Unix() || len(b.Addresses) > 32 || len(b.Addresses) == 0 {
		return peer.AddrInfo{}, errors.New("invalid/expired routing record")
	}
	k, err := crypto.UnmarshalPublicKey(b.PublicKey)
	if err != nil {
		return peer.AddrInfo{}, err
	}
	id, err := peer.IDFromPublicKey(k)
	if err != nil || id.String() != b.Peer {
		return peer.AddrInfo{}, errors.New("public key does not match Peer ID")
	}
	ok, err := k.Verify(bytesOf(b), r.Signature)
	if err != nil || !ok {
		return peer.AddrInfo{}, errors.New("invalid routing signature")
	}
	ai := peer.AddrInfo{ID: id}
	for _, s := range b.Addresses {
		if len(s) > 2048 {
			return ai, errors.New("address too long")
		}
		a, e := ma.NewMultiaddr(s)
		if e != nil {
			return ai, e
		}
		ai.Addrs = append(ai.Addrs, a)
	}
	return ai, nil
}
func (n *Node) remember(r Route) error {
	ai, err := validateRoute(r)
	if err != nil {
		return err
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	old, exists := n.routes[ai.ID]
	if exists && old.Body.Sequence >= r.Body.Sequence {
		return nil
	}
	if len(n.routes) >= 10000 {
		for id, v := range n.routes {
			if v.Body.Expires <= time.Now().Unix() {
				delete(n.routes, id)
			}
		}
		if len(n.routes) >= 10000 {
			return errors.New("directory is full")
		}
	}
	n.routes[ai.ID] = r
	n.h.Peerstore().AddAddrs(ai.ID, ai.Addrs, time.Until(time.Unix(r.Body.Expires, 0)))
	return nil
}
func (n *Node) cached(id peer.ID) *Route {
	n.mu.Lock()
	defer n.mu.Unlock()
	r, ok := n.routes[id]
	if !ok || r.Body.Expires <= time.Now().Unix() {
		return nil
	}
	return &r
}
func (n *Node) candidates(exclude peer.ID) []peer.ID {
	seen := map[peer.ID]bool{n.h.ID(): true, exclude: true}
	var out []peer.ID
	for _, b := range n.bootstrapPeers() {
		if !seen[b.ID] {
			out = append(out, b.ID)
			seen[b.ID] = true
		}
	}
	for _, id := range n.h.Network().Peers() {
		if len(out) >= 16 {
			break
		}
		if !seen[id] {
			out = append(out, id)
			seen[id] = true
		}
	}
	return out
}

// Manual bootstrap peers stay pinned. Discovery only supplies additional peers.
func (n *Node) bootstrapPeers() []peer.AddrInfo {
	n.mu.Lock()
	defer n.mu.Unlock()
	out := append([]peer.AddrInfo{}, n.boots...)
	seen := map[peer.ID]bool{}
	for _, p := range out {
		seen[p.ID] = true
	}
	for _, p := range n.discovered {
		if !seen[p.ID] && len(out) < 16 {
			out = append(out, p)
			seen[p.ID] = true
		}
	}
	return out
}

type DiscoveryPeer struct {
	Peer      string  `json:"peer"`
	RTTMillis float64 `json:"rtt_ms"`
}
type DiscoveryStatus struct {
	Source     string          `json:"source,omitempty"`
	Candidates int             `json:"candidates"`
	Selected   []DiscoveryPeer `json:"selected"`
	Updated    int64           `json:"updated,omitempty"`
	Cached     bool            `json:"cached"`
	Error      string          `json:"error,omitempty"`
}

// One WS/WSS multiaddress per line. Multiple addresses for one key are merged.
func parsePeerList(raw []byte, publicOnly bool) ([]peer.AddrInfo, error) {
	if len(raw) > 64<<10 {
		return nil, errors.New("peer list exceeds 64 KiB")
	}
	byID := map[peer.ID]int{}
	var out []peer.AddrInfo
	scanner := bufio.NewScanner(strings.NewReader(string(raw)))
	scanner.Buffer(make([]byte, 2048), 4096)
	line := 0
	for scanner.Scan() {
		line++
		s := strings.TrimSpace(strings.SplitN(scanner.Text(), "#", 2)[0])
		if s == "" {
			continue
		}
		a, err := ma.NewMultiaddr(s)
		if err != nil {
			return nil, fmt.Errorf("peer list line %d: invalid multiaddress", line)
		}
		ai, err := peer.AddrInfoFromP2pAddr(a)
		if err != nil || len(ai.Addrs) != 1 {
			return nil, fmt.Errorf("peer list line %d: expected address ending in /p2p/PEER_ID", line)
		}
		ps := ai.Addrs[0].Protocols()
		validHost := len(ps) > 0 && (ps[0].Code == ma.P_IP4 || ps[0].Code == ma.P_IP6 || ps[0].Code == ma.P_DNS || ps[0].Code == ma.P_DNS4 || ps[0].Code == ma.P_DNS6)
		validTransport := (len(ps) == 3 && ps[1].Code == ma.P_TCP && (ps[2].Code == ma.P_WS || ps[2].Code == ma.P_WSS)) || (len(ps) == 4 && ps[1].Code == ma.P_TCP && ps[2].Code == ma.P_TLS && ps[3].Code == ma.P_WS)
		port, _ := ai.Addrs[0].ValueForProtocol(ma.P_TCP)
		if !validHost || !validTransport || port == "0" {
			return nil, fmt.Errorf("peer list line %d: expected direct WS/WSS address with nonzero port", line)
		}
		if publicOnly && !manet.IsPublicAddr(ai.Addrs[0]) {
			return nil, fmt.Errorf("peer list line %d: address must be publicly routable", line)
		}
		if index, ok := byID[ai.ID]; ok {
			duplicate := false
			for _, existing := range out[index].Addrs {
				if existing.Equal(ai.Addrs[0]) {
					duplicate = true
				}
			}
			if !duplicate {
				if len(out[index].Addrs) == 8 {
					return nil, fmt.Errorf("peer list line %d: at most 8 addresses per peer", line)
				}
				out[index].Addrs = append(out[index].Addrs, ai.Addrs[0])
			}
		} else {
			if len(out) == maxPublicPeers {
				return nil, errors.New("peer list exceeds 128 peers")
			}
			byID[ai.ID] = len(out)
			out = append(out, *ai)
		}
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("peer list: %w", err)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID.String() < out[j].ID.String() })
	return out, nil
}

func peerListSource(c Config) string {
	if c.PeerList == "off" {
		return ""
	}
	if c.PeerList == "" || c.PeerList == "auto" {
		if strings.TrimSpace(c.Bootstrap) != "" {
			return ""
		}
		return defaultPeerList
	}
	return c.PeerList
}

func readPeerList(ctx context.Context, source string) ([]byte, error) {
	var reader io.ReadCloser
	if strings.HasPrefix(source, "https://") || strings.HasPrefix(source, "http://") {
		checkURL := func(u *url.URL) error {
			ip := net.ParseIP(u.Hostname())
			if u.User != nil || (u.Scheme != "https" && !(u.Scheme == "http" && (u.Hostname() == "localhost" || ip != nil && ip.IsLoopback()))) {
				return errors.New("peer list URL must use HTTPS (HTTP is allowed only on loopback)")
			}
			return nil
		}
		u, err := url.Parse(source)
		if err != nil {
			return nil, err
		}
		if err = checkURL(u); err != nil {
			return nil, err
		}
		client := &http.Client{Timeout: 8 * time.Second, CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 5 {
				return errors.New("too many peer list redirects")
			}
			return checkURL(req.URL)
		}}
		req, err := http.NewRequestWithContext(ctx, "GET", source, nil)
		if err != nil {
			return nil, err
		}
		resp, err := client.Do(req)
		if err != nil {
			return nil, err
		}
		if resp.StatusCode != http.StatusOK {
			resp.Body.Close()
			return nil, fmt.Errorf("peer list HTTP status %d", resp.StatusCode)
		}
		reader = resp.Body
	} else {
		f, err := os.Open(source)
		if err != nil {
			return nil, err
		}
		reader = f
	}
	defer reader.Close()
	raw, err := io.ReadAll(io.LimitReader(reader, (64<<10)+1))
	if err != nil {
		return nil, err
	}
	if len(raw) > 64<<10 {
		return nil, errors.New("peer list exceeds 64 KiB")
	}
	return raw, nil
}

type measuredPeer struct {
	Info peer.AddrInfo
	RTT  time.Duration
}

func choosePublicPeers(measured []measuredPeer, public bool) []measuredPeer {
	sort.Slice(measured, func(i, j int) bool {
		if measured[i].RTT == measured[j].RTT {
			return measured[i].Info.ID.String() < measured[j].Info.ID.String()
		}
		return measured[i].RTT < measured[j].RTT
	})
	if public && len(measured) > 3 {
		// One common reachable anchor helps public nodes bridge latency regions.
		anchor := 0
		for i := range measured {
			if measured[i].Info.ID.String() < measured[anchor].Info.ID.String() {
				anchor = i
			}
		}
		p := measured[anchor]
		copy(measured[1:anchor+1], measured[:anchor])
		measured[0] = p
	}
	return measured[:min(3, len(measured))]
}

// Probes use a temporary dial-only identity. Unselected candidates never become
// connections of the real node and never receive its signed route from a probe.
func probePublicPeers(ctx context.Context, peers []peer.AddrInfo) ([]measuredPeer, error) {
	if len(peers) == 0 {
		return nil, nil
	}
	h, err := libp2p.New(libp2p.NoListenAddrs, libp2p.NoTransports, libp2p.Transport(websocket.New), libp2p.Security(noise.ID, noise.New), libp2p.DisableRelay(), libp2p.UserAgent("p2pmessenger-probe/0.2"))
	if err != nil {
		return nil, err
	}
	defer h.Close()
	var wg sync.WaitGroup
	results := make(chan measuredPeer, len(peers))
	sem := make(chan struct{}, 4)
	for _, ai := range peers {
		wg.Add(1)
		go func(ai peer.AddrInfo) {
			defer wg.Done()
			select {
			case sem <- struct{}{}:
			case <-ctx.Done():
				return
			}
			defer func() { <-sem }()
			c, cancel := context.WithTimeout(ctx, 5*time.Second)
			defer cancel()
			if h.Connect(c, ai) != nil {
				return
			}
			select {
			case result, ok := <-ping.Ping(c, h, ai.ID):
				if ok && result.Error == nil {
					results <- measuredPeer{ai, result.RTT}
				}
			case <-c.Done():
			}
			h.Network().ClosePeer(ai.ID)
		}(ai)
	}
	wg.Wait()
	close(results)
	var out []measuredPeer
	for p := range results {
		out = append(out, p)
	}
	return out, nil
}

func (n *Node) selectRegistryPeers(ctx context.Context, peers []peer.AddrInfo, cursor *int) {
	batch := map[peer.ID]peer.AddrInfo{}
	n.mu.Lock()
	for _, ai := range n.discovered {
		for _, current := range peers {
			if current.ID == ai.ID {
				batch[ai.ID] = current
			}
		}
	}
	public := n.reach == network.ReachabilityPublic
	n.mu.Unlock()
	// Revisit common anchors and rotate a bounded sample through the full list.
	for _, ai := range peers[:min(3, len(peers))] {
		batch[ai.ID] = ai
	}
	for i := 0; i < min(8, len(peers)); i++ {
		ai := peers[(*cursor+i)%len(peers)]
		batch[ai.ID] = ai
	}
	if len(peers) > 0 {
		*cursor = (*cursor + 8) % len(peers)
	}
	var candidates []peer.AddrInfo
	for id, ai := range batch {
		if id != n.h.ID() {
			candidates = append(candidates, ai)
		}
	}
	measured, err := probePublicPeers(ctx, candidates)
	if ctx.Err() != nil {
		return
	}
	if err != nil {
		n.mu.Lock()
		n.discovery.Error = err.Error()
		n.mu.Unlock()
		return
	}
	selected := choosePublicPeers(measured, public)
	n.mu.Lock()
	previous := n.discovered
	n.discovered = nil
	n.discovery.Selected = nil
	for _, p := range selected {
		n.discovered = append(n.discovered, p.Info)
		n.discovery.Selected = append(n.discovery.Selected, DiscoveryPeer{p.Info.ID.String(), float64(p.RTT) / float64(time.Millisecond)})
	}
	n.mu.Unlock()
	for _, old := range previous {
		keep := false
		for _, ai := range n.bootstrapPeers() {
			if ai.ID == old.ID {
				keep = true
			}
		}
		// Keep existing relays and conversations; a registry is not a blocklist.
		for _, a := range n.h.Addrs() {
			if strings.Contains(a.String(), "/p2p/"+old.ID.String()+"/p2p-circuit") {
				keep = true
			}
		}
		for _, c := range n.h.Network().ConnsToPeer(old.ID) {
			for _, s := range c.GetStreams() {
				if s.Protocol() == messageProtocol {
					keep = true
				}
			}
		}
		if !keep {
			n.h.Network().ClosePeer(old.ID)
		}
	}
	notify(n.wake)
}

func (n *Node) discover(ctx context.Context, source string) {
	// Explicit custom lists may describe a private test network. The community
	// registry must contain publicly routable addresses only, including in cache.
	publicOnly := source == defaultPeerList
	var cached struct {
		Source string `json:"source"`
		Text   []byte `json:"text"`
	}
	var peers []peer.AddrInfo
	cache := filepath.Join(n.dir, "peer-list-cache.json")
	if raw, err := os.ReadFile(cache); err == nil && json.Unmarshal(raw, &cached) == nil && cached.Source == source {
		if list, err := parsePeerList(cached.Text, publicOnly); err == nil {
			peers = list
		}
	}
	n.mu.Lock()
	n.discovery = DiscoveryStatus{Source: source, Candidates: len(peers), Cached: len(peers) > 0}
	n.mu.Unlock()
	refresh := time.Time{}
	cursor := 0
	tick := time.NewTicker(30 * time.Second)
	defer tick.Stop()
	for {
		if !time.Now().Before(refresh) {
			raw, err := readPeerList(ctx, source)
			var list []peer.AddrInfo
			if err == nil {
				list, err = parsePeerList(raw, publicOnly)
			}
			n.mu.Lock()
			if err != nil {
				n.discovery.Error = err.Error()
				n.discovery.Cached = len(peers) > 0
				refresh = time.Now().Add(30 * time.Second)
			} else {
				peers = list
				n.discovery.Candidates = len(peers)
				n.discovery.Updated = time.Now().UnixMilli()
				n.discovery.Cached = false
				n.discovery.Error = ""
				refresh = time.Now().Add(5 * time.Minute)
			}
			n.mu.Unlock()
			if err == nil {
				cached.Source, cached.Text = source, raw
				if err := atomicWrite(cache, bytesOf(cached)); err != nil {
					log.Printf("peer list cache: %v", err)
				}
			}
		}
		n.selectRegistryPeers(ctx, peers, &cursor)
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
	}
}
func (n *Node) rpc(ctx context.Context, id peer.ID, req Request) (Response, error) {
	var res Response
	ctx = network.WithAllowLimitedConn(ctx, "directory via relay")
	s, err := n.h.NewStream(ctx, id, directoryProtocol)
	if err != nil {
		return res, err
	}
	defer s.Close()
	deadline := time.Now().Add(5 * time.Second)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}
	_ = s.SetDeadline(deadline)
	if err = json.NewEncoder(s).Encode(req); err != nil {
		_ = s.Reset()
		return res, err
	}
	if err = json.NewDecoder(io.LimitReader(s, maxFrame)).Decode(&res); err != nil {
		_ = s.Reset()
		return res, err
	}
	if res.Error != "" {
		return res, errors.New(res.Error)
	}
	return res, nil
}
func (n *Node) lookup(ctx context.Context, id peer.ID, hops int, exclude peer.ID) (*Route, error) {
	if r := n.cached(id); r != nil {
		return r, nil
	}
	if hops < 0 {
		return nil, errors.New("peer not found")
	}
	peers := n.candidates(exclude)
	// Parallel bounded lookup: one unreachable directory must not block the others.
	results := make(chan *Route, len(peers))
	child, cancel := context.WithCancel(ctx)
	defer cancel()
	for _, p := range peers {
		go func(p peer.ID) {
			res, err := n.rpc(child, p, Request{Op: "find", Peer: id.String(), Hops: hops})
			if err == nil && res.Record != nil && res.Record.Body.Peer == id.String() && n.remember(*res.Record) == nil {
				results <- n.cached(id)
			} else {
				results <- nil
			}
		}(p)
	}
	for range peers {
		select {
		case r := <-results:
			if r != nil {
				return r, nil
			}
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	return nil, errors.New("peer not found; check bootstrap nodes and recipient availability")
}
func (n *Node) bounded(handler func(network.Stream)) func(network.Stream) {
	return func(s network.Stream) {
		select {
		case n.sem <- struct{}{}:
			defer func() { <-n.sem }()
			handler(s)
		default:
			_ = s.Reset()
		}
	}
}
func (n *Node) handleDirectory(s network.Stream) {
	defer s.Close()
	_ = s.SetDeadline(time.Now().Add(8 * time.Second))
	var q Request
	if err := json.NewDecoder(io.LimitReader(s, maxFrame)).Decode(&q); err != nil {
		_ = s.Reset()
		return
	}
	var res Response
	switch q.Op {
	case "register":
		if q.Record == nil || q.Record.Body.Peer != s.Conn().RemotePeer().String() {
			res.Error = "registration identity mismatch"
		} else if err := n.remember(*q.Record); err != nil {
			res.Error = err.Error()
		}
	case "find":
		id, err := peer.Decode(q.Peer)
		if err != nil || q.Hops < 0 || q.Hops > 1 {
			res.Error = "invalid lookup"
			break
		}
		res.Record = n.cached(id)
		if res.Record == nil && q.Hops > 0 {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			res.Record, _ = n.lookup(ctx, id, 0, s.Conn().RemotePeer())
			cancel()
		}
	default:
		res.Error = "unknown operation"
	}
	_ = json.NewEncoder(s).Encode(res)
}
func validateMessage(m Message, remote peer.ID, self peer.ID) error {
	b := m.Body
	if b.Domain != "p2pmessenger-message-v1" || b.From != remote.String() || b.To != self.String() || len(b.Text) == 0 || len(b.Text) > 16000 || len(b.ID) != 32 {
		return errors.New("invalid message")
	}
	if _, err := hex.DecodeString(b.ID); err != nil {
		return err
	}
	k, err := remote.ExtractPublicKey()
	if err != nil {
		return err
	}
	ok, err := k.Verify(bytesOf(b), m.Signature)
	if err != nil || !ok {
		return errors.New("invalid message signature")
	}
	return nil
}
func (n *Node) handleMessage(s network.Stream) {
	defer s.Close()
	_ = s.SetDeadline(time.Now().Add(12 * time.Second))
	var m Message
	if err := json.NewDecoder(io.LimitReader(s, maxFrame)).Decode(&m); err != nil {
		_ = s.Reset()
		return
	}
	ack := Ack{ID: m.Body.ID}
	if err := validateMessage(m, s.Conn().RemotePeer(), n.h.ID()); err != nil {
		ack.Error = err.Error()
	} else {
		n.mu.Lock()
		found := false
		for _, e := range n.state.Messages {
			if e.Status == "received" && e.Message.Body.From == m.Body.From && e.Message.Body.ID == m.Body.ID {
				found = true
				if string(bytesOf(e.Message)) != string(bytesOf(m)) {
					ack.Error = "message ID collision"
				}
				break
			}
		}
		if !found {
			if len(n.state.Messages) >= maxHistory {
				ack.Error = "recipient history is full"
			} else {
				n.state.Messages = append(n.state.Messages, Entry{Message: m, Status: "received"})
				if err := n.saveLocked(); err != nil {
					n.state.Messages = n.state.Messages[:len(n.state.Messages)-1]
					ack.Error = "cannot persist message"
					log.Print(err)
				}
			}
		}
		n.mu.Unlock()
		if !found && ack.Error == "" {
			log.Printf("message from %s: %s", m.Body.From, m.Body.ID)
			notify(n.changes) // android
			if n.onMessage != nil {
				go n.onMessage(m) // android: must not delay the ACK
			}
		}
	}
	_ = json.NewEncoder(s).Encode(ack)
}
func (n *Node) queue(to, text string) (string, error) {
	id, err := peer.Decode(strings.TrimSpace(to))
	if err != nil {
		return "", errors.New("invalid recipient Peer ID")
	}
	if id == n.h.ID() {
		return "", errors.New("choose another peer")
	}
	if _, err = id.ExtractPublicKey(); err != nil {
		return "", errors.New("recipient must have an inline Ed25519 public key")
	}
	if len(strings.TrimSpace(text)) == 0 || len(text) > 16000 {
		return "", errors.New("message must contain 1–16000 bytes")
	}
	m := Message{Body: MessageBody{Domain: "p2pmessenger-message-v1", ID: randomID(), From: n.h.ID().String(), To: id.String(), Text: text, Time: time.Now().UnixMilli()}}
	m.Signature, err = n.key.Sign(bytesOf(m.Body))
	if err != nil {
		return "", err
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	if len(n.state.Messages) >= maxHistory {
		return "", errors.New("history is full")
	}
	pending := 0
	for _, e := range n.state.Messages {
		if e.Status == "queued" {
			pending++
		}
	}
	if pending >= 1000 {
		return "", errors.New("outbox is full")
	}
	n.state.Messages = append(n.state.Messages, Entry{Message: m, Status: "queued"})
	if err = n.saveLocked(); err != nil {
		n.state.Messages = n.state.Messages[:len(n.state.Messages)-1]
		return "", err
	}
	notify(n.sendWake)
	notify(n.changes) // android
	return m.Body.ID, nil
}
func (n *Node) deliver(ctx context.Context, m Message) (result error) {
	id, err := peer.Decode(m.Body.To)
	if err != nil {
		return err
	}
	phase := "looking_up"
	n.deliveryPhase(m.Body.To, phase)
	defer func() {
		n.mu.Lock()
		defer n.mu.Unlock()
		d := n.delivery[m.Body.To]
		if result != nil {
			// Refetch registration on retry, including after a network change.
			delete(n.routes, id)
			d.Failures++
			d.Status = map[string]string{"looking_up": "unavailable", "connecting": "connect_failed", "sending": "delivery_failed", "rejected": "rejected"}[phase]
			d.Detail = result.Error()
			d.NextAttempt = time.Now().Add(retryDelay(d.Failures)).UnixMilli()
		} else {
			d = PeerDelivery{Status: "connected"}
		}
		n.delivery[m.Body.To] = d
	}()
	// Refresh routing even when a stale direct address remains in the peerstore.
	if len(n.h.Network().ConnsToPeer(id)) == 0 {
		r, e := n.lookup(ctx, id, 1, "")
		if e != nil {
			return e
		}
		phase = "connecting"
		n.deliveryPhase(m.Body.To, phase)
		ai, e := validateRoute(*r)
		if e != nil {
			return e
		}
		// Give direct routes a brief first attempt, then permit circuit relay fallback.
		direct, cancel := context.WithTimeout(network.WithForceDirectDial(ctx, "prefer direct chat"), 3*time.Second)
		err = n.h.Connect(direct, ai)
		cancel()
		if err != nil {
			if err = n.h.Connect(ctx, ai); err != nil {
				return err
			}
		}
	}
	phase = "connecting"
	n.deliveryPhase(m.Body.To, phase)
	s, err := n.h.NewStream(network.WithAllowLimitedConn(ctx, "encrypted chat through relay"), id, messageProtocol)
	if err != nil {
		return err
	}
	defer s.Close()
	phase = "sending"
	n.deliveryPhase(m.Body.To, phase)
	deadline := time.Now().Add(10 * time.Second)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}
	_ = s.SetDeadline(deadline)
	stopCancel := context.AfterFunc(ctx, func() { _ = s.Reset() })
	defer stopCancel()
	if err = json.NewEncoder(s).Encode(m); err != nil {
		_ = s.Reset()
		return err
	}
	var ack Ack
	if err = json.NewDecoder(io.LimitReader(s, 4096)).Decode(&ack); err != nil {
		_ = s.Reset()
		return err
	}
	if ack.ID != m.Body.ID {
		return errors.New("invalid acknowledgement")
	}
	if ack.Error != "" {
		phase = "rejected"
		return errors.New(ack.Error)
	}
	return nil
}
func (n *Node) sendLoop(ctx context.Context) {
	tick := time.NewTicker(time.Second)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		case <-n.sendWake:
		}
		n.mu.Lock()
		var pending []Message
		for _, e := range n.state.Messages {
			if e.Status == "queued" && n.delivery[e.Message.Body.To].NextAttempt <= time.Now().UnixMilli() {
				pending = append(pending, e.Message)
			}
		}
		n.mu.Unlock()
		// Preserve per-recipient order, while allowing other recipients to progress.
		byPeer := map[string][]Message{}
		for _, m := range pending {
			byPeer[m.Body.To] = append(byPeer[m.Body.To], m)
		}
		slots := make(chan struct{}, 4)
		var wg sync.WaitGroup
		for _, list := range byPeer {
			select {
			case slots <- struct{}{}:
			case <-ctx.Done():
				wg.Wait()
				return
			}
			wg.Add(1)
			go func(list []Message) {
				defer wg.Done()
				defer func() { <-slots }()
				for _, m := range list {
					attempt, cancel := context.WithTimeout(ctx, 18*time.Second)
					err := n.deliver(attempt, m)
					cancel()
					n.mu.Lock()
					for i := range n.state.Messages {
						e := &n.state.Messages[i]
						if e.Message.Body.ID == m.Body.ID && e.Status == "queued" {
							old := *e
							if err == nil {
								e.Status = "delivered"
								e.Error = ""
							} else {
								e.Error = err.Error()
							}
							if e.Status != old.Status || e.Error != old.Error {
								if saveErr := n.saveLocked(); saveErr != nil {
									*e = old
									log.Print(saveErr)
								}
							}
							break
						}
					}
					n.mu.Unlock()
					notify(n.changes) // android
					if err != nil {
						break
					}
				}
			}(list)
		}
		wg.Wait()
	}
}
func (n *Node) publish(ctx context.Context) {
	r, err := n.localRoute()
	if err != nil {
		log.Printf("publish: %v", err)
		return
	}
	var wg sync.WaitGroup
	for _, p := range n.candidates("") {
		wg.Add(1)
		go func(p peer.ID) {
			defer wg.Done()
			c, cancel := context.WithTimeout(ctx, 5*time.Second)
			defer cancel()
			_, _ = n.rpc(c, p, Request{Op: "register", Record: &r})
		}(p)
	}
	wg.Wait()
}
func (n *Node) maintain(ctx context.Context) {
	sub, err := n.h.EventBus().Subscribe([]interface{}{new(event.EvtLocalAddressesUpdated), new(event.EvtLocalReachabilityChanged), new(event.EvtPeerConnectednessChanged)})
	if err != nil {
		log.Print(err)
		return
	}
	defer sub.Close()
	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case e, ok := <-sub.Out():
				if !ok {
					return
				}
				if r, ok := e.(event.EvtLocalReachabilityChanged); ok {
					n.mu.Lock()
					n.reach = r.Reachability
					n.mu.Unlock()
					log.Printf("reachability: %s", r.Reachability)
				}
				notify(n.wake)
				notify(n.changes) // android
			}
		}
	}()
	reconnect := time.NewTicker(5 * time.Second)
	defer reconnect.Stop()
	heartbeat := time.NewTicker(20 * time.Second)
	defer heartbeat.Stop()
	publish := time.NewTicker(30 * time.Second)
	defer publish.Stop()
	connect := func() {
		var wg sync.WaitGroup
		for _, b := range n.bootstrapPeers() {
			if len(n.h.Network().ConnsToPeer(b.ID)) > 0 {
				continue
			}
			wg.Add(1)
			go func(b peer.AddrInfo) {
				defer wg.Done()
				c, cancel := context.WithTimeout(ctx, 4*time.Second)
				defer cancel()
				if err := n.h.Connect(c, b); err != nil {
					log.Printf("bootstrap %s: %v", b.ID, err)
				}
			}(b)
		}
		wg.Wait()
	}
	connect()
	n.publish(ctx)
	for {
		select {
		case <-ctx.Done():
			return
		case <-reconnect.C:
			connect()
		case <-publish.C:
			n.publish(ctx)
		case <-n.wake:
			// Coalesce interface, relay address and connection changes.
			timer := time.NewTimer(300 * time.Millisecond)
			select {
			case <-ctx.Done():
				timer.Stop()
				return
			case <-timer.C:
			}
			connect()
			n.publish(ctx)
			notify(n.sendWake)
		case <-heartbeat.C:
			for _, p := range n.h.Network().Peers() {
				go func(p peer.ID) {
					c, cancel := context.WithTimeout(network.WithAllowLimitedConn(ctx, "keepalive"), 8*time.Second)
					defer cancel()
					select {
					case <-ping.Ping(c, n.h, p):
					case <-c.Done():
					}
				}(p)
			}
		}
	}
}

type PeerDelivery struct {
	Status      string `json:"status"`
	Detail      string `json:"detail,omitempty"`
	Failures    int    `json:"failures,omitempty"`
	NextAttempt int64  `json:"next_attempt,omitempty"`
}

func retryDelay(failures int) time.Duration {
	return min(time.Minute, 5*time.Second<<min(4, max(0, failures-1)))
}
func (n *Node) deliveryPhase(id, phase string) {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.delivery == nil {
		n.delivery = map[string]PeerDelivery{}
	}
	d := n.delivery[id]
	d.Status = phase
	d.NextAttempt = 0
	n.delivery[id] = d
	notify(n.changes) // android
}
func peerDeliveryLabel(d PeerDelivery) string {
	label := map[string]string{"looking_up": "ищем собеседника", "connecting": "подключаемся", "sending": "отправляем", "connected": "на связи", "unavailable": "не в сети / недоступен", "connect_failed": "не удалось подключиться", "delivery_failed": "сбой доставки", "rejected": "получатель отклонил сообщение", "queued": "ожидает отправки"}[d.Status]
	if label == "" {
		label = "нет активного соединения"
	}
	if d.NextAttempt > 0 {
		seconds := max(int64(0), (d.NextAttempt-time.Now().UnixMilli()+999)/1000)
		label += fmt.Sprintf(" · повтор через %d с", seconds)
	}
	return label
}

type Connection struct {
	Peer    string `json:"peer"`
	Address string `json:"address"`
	Relay   bool   `json:"relay"`
}
type Snapshot struct {
	Peer         string                  `json:"peer"`
	Reachability string                  `json:"reachability"`
	Addresses    []string                `json:"addresses"`
	Connections  []Connection            `json:"connections"`
	Queued       int                     `json:"queued"`
	Messages     []Entry                 `json:"messages"`
	Peers        map[string]PeerDelivery `json:"peers"`
	Discovery    DiscoveryStatus         `json:"discovery"`
}

func (n *Node) snapshot() Snapshot {
	n.mu.Lock()
	s := Snapshot{Peer: n.h.ID().String(), Reachability: n.reach.String(), Messages: append([]Entry{}, n.state.Messages...), Peers: map[string]PeerDelivery{}, Discovery: n.discovery}
	s.Discovery.Selected = append([]DiscoveryPeer{}, n.discovery.Selected...)
	for id, d := range n.delivery {
		// A successful past delivery is not proof that the peer is still online.
		if d.Status != "connected" {
			s.Peers[id] = d
		}
	}
	n.mu.Unlock()
	for _, e := range s.Messages {
		if e.Status == "queued" {
			s.Queued++
			if _, ok := s.Peers[e.Message.Body.To]; !ok {
				s.Peers[e.Message.Body.To] = PeerDelivery{Status: "queued", Detail: e.Error}
			}
		}
	}
	for _, a := range n.h.Addrs() {
		s.Addresses = append(s.Addresses, a.String()+"/p2p/"+s.Peer)
	}
	for _, c := range n.h.Network().Conns() {
		s.Connections = append(s.Connections, Connection{c.RemotePeer().String(), c.RemoteMultiaddr().String(), c.Stat().Limited})
		if d, ok := s.Peers[c.RemotePeer().String()]; !ok || d.Status == "queued" {
			s.Peers[c.RemotePeer().String()] = PeerDelivery{Status: "connected"}
		}
	}
	return s
}

type Config struct {
	Port         int    `json:"port"`
	DirectPort   int    `json:"direct_port"`
	Bootstrap    string `json:"bootstrap"`
	Announce     string `json:"announce"`
	Reachability string `json:"reachability"`
	TLSCert      string `json:"tls_cert"`
	TLSKey       string `json:"tls_key"`
	IPFamily     string `json:"ip_family"`
	PeerList     string `json:"peer_list"`
}
