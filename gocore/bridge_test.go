package gocore

import (
	"archive/zip"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	relay "github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/relay"
	ma "github.com/multiformats/go-multiaddr"
	manet "github.com/multiformats/go-multiaddr/net"
)

type recorder struct {
	mu       sync.Mutex
	messages []string
	changed  int
}

func (r *recorder) OnMessage(peer, id, text string, time int64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.messages = append(r.messages, text)
}
func (r *recorder) OnChanged() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.changed++
}

func waitFor(t *testing.T, what string, ok func() bool) {
	t.Helper()
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		if ok() {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("timeout waiting for " + what)
}

// A desktop-configured test node talks to the mobile node through the bridge.
func TestBridgeSendReceiveAndPaging(t *testing.T) {
	hub := testNode(t)
	dir := t.TempDir()
	rec := &recorder{}
	bootstrap := hub.h.Addrs()[0].String() + "/p2p/" + hub.h.ID().String()
	cfg := `{"bootstrap":"` + bootstrap + `","peer_list":"off"}`
	if err := startWith(dir, cfg, rec, []string{"/ip4/127.0.0.1/tcp/0"}); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(Stop)
	if err := startWith(dir, cfg, rec, []string{"/ip4/127.0.0.1/tcp/0"}); err != nil {
		t.Fatal("second Start must be a no-op", err)
	}
	self, err := LocalPeerID(dir)
	if err != nil {
		t.Fatal(err)
	}
	var status Snapshot
	waitFor(t, "bootstrap connection", func() bool {
		raw, err := Status()
		if err != nil || json.Unmarshal([]byte(raw), &status) != nil {
			return false
		}
		return len(status.Connections) > 0
	})
	if status.Peer != self || status.Messages != nil {
		t.Fatalf("bad status: %+v", status)
	}
	if _, err = Send(self, "self"); err == nil {
		t.Fatal("sending to self accepted")
	}
	remote := hub.h.ID().String()
	for _, text := range []string{"один", "два", "три"} {
		if _, err = Send(remote, text); err != nil {
			t.Fatal(err)
		}
	}
	waitFor(t, "delivery", func() bool {
		hub.mu.Lock()
		defer hub.mu.Unlock()
		return len(hub.state.Messages) == 3
	})
	if _, err = hub.queue(self, "ответ"); err != nil {
		t.Fatal(err)
	}
	hubDeliver(t, hub, hub.state.Messages[3].Message)
	waitFor(t, "incoming callback", func() bool {
		rec.mu.Lock()
		defer rec.mu.Unlock()
		return len(rec.messages) == 1 && rec.messages[0] == "ответ"
	})
	var page messagePage
	raw, err := Messages(remote, 2, -1)
	if err != nil || json.Unmarshal([]byte(raw), &page) != nil {
		t.Fatal(err)
	}
	if !page.HasMore || len(page.Messages) != 2 || page.Messages[1].Message.Body.Text != "ответ" {
		t.Fatalf("bad latest page: %+v", page)
	}
	before := page.Messages[0].Index
	raw, _ = Messages(remote, 10, before)
	page = messagePage{}
	if json.Unmarshal([]byte(raw), &page) != nil || page.HasMore || len(page.Messages) != 2 || page.Messages[0].Message.Body.Text != "один" {
		t.Fatalf("bad older page: %+v", page)
	}
	var list []conversation
	raw, _ = Conversations("")
	if json.Unmarshal([]byte(raw), &list) != nil || len(list) != 1 || list[0].Unread != 1 || list[0].LastText != "ответ" {
		t.Fatalf("bad conversations: %s", raw)
	}
	marks, _ := json.Marshal(map[string]int{remote: list[0].LastIndex})
	raw, _ = Conversations(string(marks))
	list = nil
	if json.Unmarshal([]byte(raw), &list) != nil || list[0].Unread != 0 {
		t.Fatalf("read mark ignored: %s", raw)
	}
	Stop()
	if IsRunning() {
		t.Fatal("still running")
	}
	if _, err = Status(); err == nil {
		t.Fatal("status of a stopped node")
	}
}

func hubDeliver(t *testing.T, hub *Node, m Message) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := hub.deliver(ctx, m); err != nil {
		t.Fatal(err)
	}
}

func TestWildcardListenersExpandToInterfaces(t *testing.T) {
	listen := []ma.Multiaddr{
		ma.StringCast("/ip4/0.0.0.0/tcp/4100"),
		ma.StringCast("/ip4/0.0.0.0/udp/4100/quic-v1"),
		ma.StringCast("/p2p-circuit"),
		ma.StringCast("/ip4/10.0.0.9/tcp/4200"),
	}
	got := expandListenAddrs(listen, []ma.Multiaddr{ma.StringCast("/ip4/192.168.1.5"), ma.StringCast("/ip6/2001:db8::5")})
	want := []string{"/ip4/192.168.1.5/tcp/4100", "/ip4/192.168.1.5/udp/4100/quic-v1"}
	if len(got) != len(want) {
		t.Fatalf("got %v", got)
	}
	for i := range want {
		if got[i].String() != want[i] {
			t.Fatalf("got %v", got)
		}
	}
	base := []ma.Multiaddr{got[0]}
	if merged := mergeAddrs(base, got); len(merged) != 2 {
		t.Fatalf("duplicates kept: %v", merged)
	}
	for _, a := range interfaceMultiaddrs() {
		if manet.IsIPLoopback(a) || manet.IsIP6LinkLocal(a) {
			t.Fatalf("unusable interface address: %s", a)
		}
	}
}

// Mobile nodes must reserve a relay slot on their bootstrap node and deliver.
func TestMobileNodesReserveRelayAndDeliver(t *testing.T) {
	hub := testNode(t)
	service, err := relay.New(hub.h)
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close()
	// A DNS name, like upstream's smoke test: AutoRelay publishes no circuit
	// addresses for a relay that is known only by private IP addresses.
	port, err := hub.h.Addrs()[0].ValueForProtocol(ma.P_TCP)
	if err != nil {
		t.Fatal(err)
	}
	cfg := Config{Bootstrap: "/dns4/localhost/tcp/" + port + "/ws/p2p/" + hub.h.ID().String(), PeerList: "off"}
	start := func() *Node {
		n, _, err := newMobileNode(t.TempDir(), cfg, []string{"/ip4/127.0.0.1/tcp/0"})
		if err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithCancel(context.Background())
		var workers sync.WaitGroup
		workers.Add(2)
		go func() { defer workers.Done(); n.maintain(ctx) }()
		go func() { defer workers.Done(); n.sendLoop(ctx) }()
		t.Cleanup(func() { cancel(); workers.Wait(); n.h.Close() })
		return n
	}
	a, b := start(), start()
	hasCircuit := func(n *Node) bool {
		for _, addr := range n.h.Addrs() {
			if strings.Contains(addr.String(), "/p2p-circuit") {
				return true
			}
		}
		return false
	}
	waitFor(t, "relay reservations", func() bool { return hasCircuit(a) && hasCircuit(b) })
	if _, err = a.queue(b.h.ID().String(), "через сеть мобильных узлов"); err != nil {
		t.Fatal(err)
	}
	waitFor(t, "delivery", func() bool {
		b.mu.Lock()
		defer b.mu.Unlock()
		return len(b.state.Messages) == 1
	})
}

func TestValidation(t *testing.T) {
	n := testNode(t)
	id := n.h.ID().String()
	if err := ValidatePeerID(" " + id + "\n"); err != nil {
		t.Fatal(err)
	}
	if ValidatePeerID("not-a-peer") == nil {
		t.Fatal("bad Peer ID accepted")
	}
	if err := ValidateBootstrap("/ip4/192.168.1.2/tcp/8081/ws/p2p/" + id); err != nil {
		t.Fatal(err)
	}
	for _, s := range []string{"", "/ip4/1.2.3.4/tcp/80/ws", "garbage"} {
		if ValidateBootstrap(s) == nil {
			t.Fatal("bad bootstrap accepted: " + s)
		}
	}
	if _, err := parseBootstrap(strings.Repeat("/ip4/1.2.3.4/tcp/80/ws/p2p/"+id+",", 17)); err == nil {
		t.Fatal("more than 16 bootstrap nodes accepted")
	}
}

func TestProfileRoundTrip(t *testing.T) {
	a := t.TempDir()
	id, err := LocalPeerID(a)
	if err != nil {
		t.Fatal(err)
	}
	state := []byte(`{"sequence":5,"messages":[{"message":{"body":{"domain":"x","id":"a","from":"f","to":"t","text":"сообщение","time":1},"signature":"AQ=="},"status":"received"}]}`)
	if err = atomicWrite(filepath.Join(a, "state.json"), state); err != nil {
		t.Fatal(err)
	}
	if err = atomicWrite(filepath.Join(a, "sequence.json"), []byte("7")); err != nil {
		t.Fatal(err)
	}
	archive := filepath.Join(t.TempDir(), "profile.zip")
	if err = ExportProfile(a, archive); err != nil {
		t.Fatal(err)
	}
	b := t.TempDir()
	if _, err = LocalPeerID(b); err != nil {
		t.Fatal(err)
	}
	if err = ImportProfile(b, archive); err != nil {
		t.Fatal(err)
	}
	got, err := LocalPeerID(b)
	if err != nil || got != id {
		t.Fatalf("identity not transferred: %s != %s (%v)", got, id, err)
	}
	raw, err := os.ReadFile(filepath.Join(b, "state.json"))
	if err != nil || string(raw) != string(state) {
		t.Fatal("history not transferred", err)
	}
}

func writeZip(t *testing.T, files map[string]string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "bad.zip")
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	z := zip.NewWriter(f)
	for name, body := range files {
		w, err := z.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		w.Write([]byte(body))
	}
	if err = z.Close(); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestImportRejectsBadArchivesWithoutChanges(t *testing.T) {
	dir := t.TempDir()
	id, err := LocalPeerID(dir)
	if err != nil {
		t.Fatal(err)
	}
	notZip := filepath.Join(t.TempDir(), "x.zip")
	os.WriteFile(notZip, []byte("nope"), 0600)
	for name, path := range map[string]string{
		"not a zip":   notZip,
		"missing key": writeZip(t, map[string]string{"state.json": "{}"}),
		"bad key":     writeZip(t, map[string]string{"identity.key": "garbage"}),
	} {
		if ImportProfile(dir, path) == nil {
			t.Fatal("accepted: " + name)
		}
	}
	if got, _ := LocalPeerID(dir); got != id {
		t.Fatal("failed import changed the identity")
	}
	// Paths other than the known profile files must be ignored, never written.
	valid, _ := os.ReadFile(filepath.Join(dir, "identity.key"))
	slip := writeZip(t, map[string]string{"identity.key": string(valid), "../../evil": "x"})
	if err = ImportProfile(dir, slip); err != nil {
		t.Fatal(err)
	}
	if _, err = os.Stat(filepath.Join(filepath.Dir(filepath.Dir(dir)), "evil")); !os.IsNotExist(err) {
		t.Fatal("archive wrote outside the profile")
	}
}
