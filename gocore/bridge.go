package gocore

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"time"

	"github.com/libp2p/go-libp2p/core/peer"
	ma "github.com/multiformats/go-multiaddr"
)

// Listener receives node events on Go-owned threads. Implementations must
// return quickly and may call back into this package.
type Listener interface {
	OnMessage(peer, id, text string, time int64)
	OnChanged()
}

type running struct {
	node    *Node
	cancel  context.CancelFunc
	workers sync.WaitGroup
}

var (
	lifecycle sync.Mutex // serializes Start, Stop and profile operations
	stateMu   sync.RWMutex
	current   *running
)

func active() *running {
	stateMu.RLock()
	defer stateMu.RUnlock()
	return current
}

func activeNode() (*Node, error) {
	r := active()
	if r == nil {
		return nil, errors.New("node is not running")
	}
	return r.node, nil
}

// Start launches the node for dataDir. configJSON has the fields "bootstrap"
// (comma-separated multiaddresses) and "peer_list" (auto, off or an HTTPS URL).
// Calling Start while a node is running is a no-op.
func Start(dataDir, configJSON string, l Listener) error {
	return startWith(dataDir, configJSON, l, mobileListenAddrs)
}

func startWith(dataDir, configJSON string, l Listener, listen []string) error {
	lifecycle.Lock()
	defer lifecycle.Unlock()
	if active() != nil {
		return nil
	}
	cfg := Config{PeerList: "auto"}
	if strings.TrimSpace(configJSON) != "" {
		if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
			return err
		}
	}
	n, source, err := newMobileNode(dataDir, cfg, listen)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithCancel(context.Background())
	r := &running{node: n, cancel: cancel}
	if l != nil {
		n.onMessage = func(m Message) { l.OnMessage(m.Body.From, m.Body.ID, m.Body.Text, m.Body.Time) }
	}
	r.workers.Add(3)
	go func() { defer r.workers.Done(); n.maintain(ctx) }()
	go func() { defer r.workers.Done(); n.sendLoop(ctx) }()
	go func() { defer r.workers.Done(); dispatchChanges(ctx, n, l) }()
	if source != "" {
		r.workers.Add(1)
		go func() { defer r.workers.Done(); n.discover(ctx, source) }()
	}
	stateMu.Lock()
	current = r
	stateMu.Unlock()
	return nil
}

// dispatchChanges calls OnChanged at most ten times a second, outside n.mu.
func dispatchChanges(ctx context.Context, n *Node, l Listener) {
	for {
		select {
		case <-ctx.Done():
			return
		case <-n.changes:
		}
		if l != nil {
			l.OnChanged()
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(100 * time.Millisecond):
		}
	}
}

// Stop shuts the node down and waits for its workers. The queue stays on disk.
func Stop() {
	lifecycle.Lock()
	defer lifecycle.Unlock()
	r := active()
	if r == nil {
		return
	}
	stateMu.Lock()
	current = nil
	stateMu.Unlock()
	r.cancel()
	r.workers.Wait()
	_ = r.node.h.Close()
}

// IsRunning reports whether a node is active in this process.
func IsRunning() bool { return active() != nil }

// LocalPeerID returns the Peer ID of the profile in dataDir, creating the
// identity on first use. It works whether or not the node is running.
func LocalPeerID(dataDir string) (string, error) {
	lifecycle.Lock()
	defer lifecycle.Unlock()
	if err := prepareDir(dataDir); err != nil {
		return "", err
	}
	k, err := loadKey(dataDir)
	if err != nil {
		return "", err
	}
	id, err := peer.IDFromPrivateKey(k)
	if err != nil {
		return "", err
	}
	return id.String(), nil
}

func toJSON(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return "{}"
	}
	return string(b)
}

// Status returns the node snapshot as JSON, without the message history.
func Status() (string, error) {
	n, err := activeNode()
	if err != nil {
		return "", err
	}
	s := n.snapshot()
	s.Messages = nil
	return toJSON(s), nil
}

// Send durably queues a message and returns its ID. It never waits for the network.
func Send(to, text string) (string, error) {
	n, err := activeNode()
	if err != nil {
		return "", err
	}
	return n.queue(to, text)
}

// NetworkChanged tells the node that connectivity changed: pending retries
// become due immediately and addresses are republished.
func NetworkChanged() {
	r := active()
	if r == nil {
		return
	}
	n := r.node
	n.mu.Lock()
	for id, d := range n.delivery {
		d.NextAttempt = 0
		n.delivery[id] = d
	}
	n.mu.Unlock()
	notify(n.wake)
	notify(n.sendWake)
}

// Conversation summarises one chat for the conversation list.
type conversation struct {
	Peer      string `json:"peer"`
	LastText  string `json:"last_text"`
	LastTime  int64  `json:"last_time"`
	LastIndex int    `json:"last_index"`
	Outgoing  bool   `json:"outgoing"`
	Unread    int    `json:"unread"`
	Queued    int    `json:"queued"`
}

func otherParty(b MessageBody, self string) string {
	if b.From == self {
		return b.To
	}
	return b.From
}

// Conversations returns chats ordered by latest message. readMarksJSON maps a
// Peer ID to the highest history index the user has seen.
func Conversations(readMarksJSON string) (string, error) {
	n, err := activeNode()
	if err != nil {
		return "", err
	}
	marks := map[string]int{}
	if strings.TrimSpace(readMarksJSON) != "" {
		if err = json.Unmarshal([]byte(readMarksJSON), &marks); err != nil {
			return "", err
		}
	}
	self := n.h.ID().String()
	n.mu.Lock()
	byPeer := map[string]*conversation{}
	order := make([]*conversation, 0)
	for i := len(n.state.Messages) - 1; i >= 0; i-- {
		e := n.state.Messages[i]
		p := otherParty(e.Message.Body, self)
		if p == "" {
			continue
		}
		c := byPeer[p]
		if c == nil {
			c = &conversation{Peer: p, LastText: e.Message.Body.Text, LastTime: e.Message.Body.Time, LastIndex: i, Outgoing: e.Message.Body.From == self}
			byPeer[p] = c
			order = append(order, c)
		}
		if e.Status == "queued" {
			c.Queued++
		}
		if mark, seen := marks[p]; e.Status == "received" && (!seen || i > mark) {
			c.Unread++
		}
	}
	n.mu.Unlock()
	return toJSON(order), nil
}

type indexedEntry struct {
	Index int `json:"index"`
	Entry
}

type messagePage struct {
	Messages []indexedEntry `json:"messages"`
	HasMore  bool           `json:"has_more"`
}

// Messages returns up to limit messages of a chat, oldest first, ending just
// before history index beforeIndex. A negative beforeIndex means "latest".
func Messages(peerID string, limit, beforeIndex int) (string, error) {
	n, err := activeNode()
	if err != nil {
		return "", err
	}
	if limit <= 0 {
		limit = 100
	}
	self := n.h.ID().String()
	page := messagePage{Messages: []indexedEntry{}}
	n.mu.Lock()
	start := len(n.state.Messages) - 1
	if beforeIndex >= 0 && beforeIndex-1 < start {
		start = beforeIndex - 1
	}
	for i := start; i >= 0; i-- {
		e := n.state.Messages[i]
		if otherParty(e.Message.Body, self) != peerID {
			continue
		}
		if len(page.Messages) == limit {
			page.HasMore = true
			break
		}
		page.Messages = append(page.Messages, indexedEntry{Index: i, Entry: e})
	}
	n.mu.Unlock()
	for i, j := 0, len(page.Messages)-1; i < j; i, j = i+1, j-1 {
		page.Messages[i], page.Messages[j] = page.Messages[j], page.Messages[i]
	}
	return toJSON(page), nil
}

// ValidatePeerID accepts the same recipients as the node's send queue.
func ValidatePeerID(s string) error {
	id, err := peer.Decode(strings.TrimSpace(s))
	if err != nil {
		return errors.New("некорректный Peer ID")
	}
	if _, err = id.ExtractPublicKey(); err != nil {
		return errors.New("Peer ID должен содержать встроенный ключ Ed25519")
	}
	return nil
}

// ValidateBootstrap checks one multiaddress ending in /p2p/PEER_ID.
func ValidateBootstrap(s string) error {
	a, err := ma.NewMultiaddr(strings.TrimSpace(s))
	if err != nil {
		return errors.New("некорректный multiaddress")
	}
	if _, err = peer.AddrInfoFromP2pAddr(a); err != nil {
		return errors.New("адрес должен заканчиваться на /p2p/PEER_ID")
	}
	return nil
}
