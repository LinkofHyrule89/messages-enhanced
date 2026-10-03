package webapp

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/rs/zerolog"
)

func testKeys(t *testing.T) (string, string) {
	t.Helper()
	k, err := ecdh.P256().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	auth := make([]byte, 16)
	_, _ = rand.Read(auth)
	return base64.RawURLEncoding.EncodeToString(k.PublicKey().Bytes()), base64.RawURLEncoding.EncodeToString(auth)
}

type sentPush struct {
	endpoint string
	n        notification
}

type fakeSource struct {
	mu   sync.Mutex
	conv PushConversation
	msgs []PushMessage
}

func (f *fakeSource) get(convID string, limit int) (PushConversation, []PushMessage, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	c := f.conv
	c.ID = convID
	return c, append([]PushMessage(nil), f.msgs...), nil
}

func newTestHub(t *testing.T, src *fakeSource) (*PushHub, *[]sentPush, *sync.Mutex, string) {
	t.Helper()
	dir := t.TempDir()
	h := NewPushHub()
	h.debounce = 5 * time.Millisecond
	var mu sync.Mutex
	var sent []sentPush
	status := http.StatusCreated
	h.send = func(ctx context.Context, sub *PushSubscription, payload []byte) (int, error) {
		var n notification
		_ = json.Unmarshal(payload, &n)
		mu.Lock()
		sent = append(sent, sentPush{sub.Endpoint, n})
		mu.Unlock()
		if strings.Contains(sub.Endpoint, "gone") {
			return http.StatusGone, nil
		}
		return status, nil
	}
	var source PushSource
	if src != nil {
		source = src.get
	}
	if err := h.Open(dir, source, zerolog.Nop()); err != nil {
		t.Fatal(err)
	}
	return h, &sent, &mu, dir
}

func waitPush(h *PushHub) {
	time.Sleep(60 * time.Millisecond)
	h.Wait()
}

func TestVAPIDKeysStoredPrivatelyAndReused(t *testing.T) {
	h, _, _, dir := newTestHub(t, nil)
	fi, err := os.Stat(filepath.Join(dir, pushVAPIDFile))
	if err != nil {
		t.Fatal(err)
	}
	if fi.Mode().Perm() != 0o600 {
		t.Fatalf("vapid file mode %v", fi.Mode().Perm())
	}
	if !validVAPIDPublic(h.PublicKey()) {
		t.Fatal("bad public key")
	}
	h2 := NewPushHub()
	if err := h2.Open(dir, nil, zerolog.Nop()); err != nil {
		t.Fatal(err)
	}
	if h2.PublicKey() != h.PublicKey() {
		t.Fatal("keys must persist across restarts")
	}
}

func TestSubscriptionStore(t *testing.T) {
	h, _, _, dir := newTestHub(t, nil)
	p, a := testKeys(t)
	yes := true
	if _, err := h.Subscribe(PushSubscription{Endpoint: "https://fcm.googleapis.com/fcm/send/abc", Keys: keysOf(p, a), Label: "Chrome on Android"}, &yes); err != nil {
		t.Fatal(err)
	}
	for _, bad := range []PushSubscription{
		{Endpoint: "http://fcm.googleapis.com/x", Keys: keysOf(p, a)},
		{Endpoint: "https://127.0.0.1/x", Keys: keysOf(p, a)},
		{Endpoint: "https://localhost/x", Keys: keysOf(p, a)},
		{Endpoint: "https://router/x", Keys: keysOf(p, a)},
		{Endpoint: "https://fcm.googleapis.com/x", Keys: keysOf("AAAA", a)},
		{Endpoint: "https://fcm.googleapis.com/x", Keys: keysOf(p, "AAAA")},
	} {
		if _, err := h.Subscribe(bad, nil); err == nil {
			t.Fatalf("accepted bad subscription %+v", bad.Endpoint)
		}
	}
	fi, _ := os.Stat(filepath.Join(dir, pushSubsFile))
	if fi == nil || fi.Mode().Perm() != 0o600 {
		t.Fatalf("subscriptions file perms: %v", fi)
	}
	h2 := NewPushHub()
	_ = h2.Open(dir, nil, zerolog.Nop())
	s, ok := h2.Get("https://fcm.googleapis.com/fcm/send/abc")
	if !ok || !s.HideText || s.Label != "Chrome on Android" {
		t.Fatalf("reloaded sub: %+v %v", s, ok)
	}
	// Refresh without hide_text keeps the stored preference.
	if s, _ := h2.Subscribe(PushSubscription{Endpoint: s.Endpoint, Keys: s.Keys}, nil); !s.HideText {
		t.Fatal("refresh lost hide_text")
	}
	if !h2.Unsubscribe(s.Endpoint) || h2.Count() != 0 {
		t.Fatal("unsubscribe")
	}
}

func keysOf(p, a string) (k struct {
	Auth   string `json:"auth"`
	P256dh string `json:"p256dh"`
}) {
	k.Auth, k.P256dh = a, p
	return
}

func TestNotifierIncomingOnlyDedupedAndPerDevicePrivacy(t *testing.T) {
	now := time.Now()
	src := &fakeSource{conv: PushConversation{Name: "Family", IsGroup: true}}
	h, sent, mu, _ := newTestHub(t, src)
	p, a := testKeys(t)
	yes := true
	_, _ = h.Subscribe(PushSubscription{Endpoint: "https://push.example.com/visible", Keys: keysOf(p, a)}, nil)
	_, _ = h.Subscribe(PushSubscription{Endpoint: "https://push.example.com/private", Keys: keysOf(p, a)}, &yes)

	// Own message: nothing.
	src.msgs = []PushMessage{{ID: "m1", Body: "on my way", FromMe: true, TimestampMS: now.UnixMilli()}}
	h.MessagesChanged("c1")
	waitPush(h)
	if len(*sent) != 0 {
		t.Fatalf("own message pushed: %+v", *sent)
	}
	// Old (backfilled) message: nothing.
	src.msgs = []PushMessage{{ID: "old", SenderName: "Ann", Body: "hi", TimestampMS: now.Add(-time.Hour).UnixMilli()}}
	h.MessagesChanged("c1")
	waitPush(h)
	if len(*sent) != 0 {
		t.Fatal("old message pushed")
	}
	// Empty conversation id (bulk refresh): ignored.
	h.MessagesChanged("")
	// New incoming: one push per device.
	src.msgs = []PushMessage{{ID: "m2", SenderName: "Ann", Body: "  dinner\n at 7? ", TimestampMS: now.UnixMilli()}, src.msgs[0]}
	h.MessagesChanged("c1")
	h.MessagesChanged("c1") // debounced
	waitPush(h)
	mu.Lock()
	if len(*sent) != 2 {
		t.Fatalf("want 2 pushes, got %+v", *sent)
	}
	for _, s := range *sent {
		if s.n.Title != "Ann · Family" || s.n.Tag != pushTag("c1") || s.n.URL != "/app/?c=c1" || s.n.Conv != "c1" {
			t.Fatalf("payload: %+v", s.n)
		}
		switch s.endpoint {
		case "https://push.example.com/visible":
			if s.n.Body != "dinner at 7?" {
				t.Fatalf("body %q", s.n.Body)
			}
		case "https://push.example.com/private":
			if s.n.Body != "New message" || strings.Contains(s.n.Body, "dinner") {
				t.Fatalf("hidden body leaked: %q", s.n.Body)
			}
		}
	}
	*sent = nil
	mu.Unlock()
	// Same message again (status update etc.): deduped.
	h.MessagesChanged("c1")
	waitPush(h)
	if len(*sent) != 0 {
		t.Fatal("duplicate push")
	}
	// Muted conversation: nothing.
	src.conv.NotificationMode = "muted"
	src.msgs = []PushMessage{{ID: "m3", SenderName: "Ann", Body: "x", TimestampMS: now.UnixMilli()}}
	h.MessagesChanged("c1")
	waitPush(h)
	if len(*sent) != 0 {
		t.Fatal("muted conversation pushed")
	}
}

func TestNotifierPrunesGoneSubscriptions(t *testing.T) {
	src := &fakeSource{msgs: []PushMessage{{ID: "m1", SenderNum: "+15551234567", Body: "", HasMedia: true, MimeType: "image/jpeg", TimestampMS: time.Now().UnixMilli()}}}
	h, sent, _, _ := newTestHub(t, src)
	p, a := testKeys(t)
	_, _ = h.Subscribe(PushSubscription{Endpoint: "https://push.example.com/gone", Keys: keysOf(p, a)}, nil)
	h.MessagesChanged("c9")
	waitPush(h)
	if len(*sent) != 1 || (*sent)[0].n.Body != "📷 Photo" || (*sent)[0].n.Title != "+15551234567" {
		t.Fatalf("sent %+v", *sent)
	}
	if h.Count() != 0 {
		t.Fatal("410 subscription should be removed")
	}
}

// The real sender (webpush-go) against a fake push service: VAPID auth,
// aes128gcm encryption headers, TTL/urgency.
func TestWebPushSendEncryptsAndSignsVAPID(t *testing.T) {
	var got http.Header
	var bodyLen int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.Header.Clone()
		var b bytes.Buffer
		_, _ = b.ReadFrom(r.Body)
		bodyLen = b.Len()
		w.WriteHeader(http.StatusCreated)
	}))
	defer srv.Close()
	h := NewPushHub()
	h.allowHTTP = true
	if err := h.Open(t.TempDir(), nil, zerolog.Nop()); err != nil {
		t.Fatal(err)
	}
	p, a := testKeys(t)
	if _, err := h.Subscribe(PushSubscription{Endpoint: srv.URL + "/push/1", Keys: keysOf(p, a)}, nil); err != nil {
		t.Fatal(err)
	}
	status, err := h.SendTest(srv.URL + "/push/1")
	if err != nil || status != http.StatusCreated {
		t.Fatalf("send: %d %v", status, err)
	}
	if !strings.HasPrefix(got.Get("Authorization"), "vapid t=") || !strings.Contains(got.Get("Authorization"), "k="+h.PublicKey()) {
		t.Fatalf("authorization header: %q", got.Get("Authorization"))
	}
	if got.Get("Content-Encoding") != "aes128gcm" || got.Get("TTL") == "" || got.Get("Urgency") != "high" {
		t.Fatalf("headers: %v", got)
	}
	if bodyLen < 100 {
		t.Fatalf("encrypted body too small: %d", bodyLen)
	}
	if s, _ := h.Get(srv.URL + "/push/1"); s.LastOKMS == 0 {
		t.Fatal("LastOKMS not recorded")
	}
}

func TestPushAPIAuthAndOrigin(t *testing.T) {
	hub := NewPushHub()
	if err := hub.Open(t.TempDir(), nil, zerolog.Nop()); err != nil {
		t.Fatal(err)
	}
	var tested []string
	hub.send = func(ctx context.Context, sub *PushSubscription, payload []byte) (int, error) {
		tested = append(tested, string(payload))
		return 201, nil
	}
	h, _, _ := newTestServer(t, func(c *Config, d *Deps) { d.Push = hub })
	p, a := testKeys(t)
	sub := `{"endpoint":"https://fcm.googleapis.com/fcm/send/xyz","keys":{"p256dh":"` + p + `","auth":"` + a + `"}}`

	// Not logged in: 401 everywhere.
	for _, path := range []string{"/api/app/push/config", "/api/app/push/subscribe", "/api/app/push/unsubscribe", "/api/app/push/test", "/api/app/push/settings"} {
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost, "http://car.example"+path, strings.NewReader(sub)))
		if rr.Code != http.StatusUnauthorized {
			t.Fatalf("%s without login: %d", path, rr.Code)
		}
	}
	c := login(t, h)
	do := func(method, path, body, origin string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(method, "http://car.example"+path, strings.NewReader(body))
		req.AddCookie(c)
		req.Header.Set("Content-Type", "application/json")
		if origin != "" {
			req.Header.Set("Origin", origin)
		}
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, req)
		return rr
	}
	rr := do("GET", "/api/app/push/config", "", "")
	var cfg map[string]any
	_ = json.Unmarshal(rr.Body.Bytes(), &cfg)
	if cfg["available"] != true || cfg["public_key"] != hub.PublicKey() {
		t.Fatalf("config: %s", rr.Body.String())
	}
	if strings.Contains(rr.Body.String(), "private") {
		t.Fatal("config must not expose the private key")
	}
	// Cross-origin write rejected.
	if rr := do("POST", "/api/app/push/subscribe", sub, "https://evil.example"); rr.Code != http.StatusForbidden {
		t.Fatalf("cross-origin subscribe: %d", rr.Code)
	}
	if hub.Count() != 0 {
		t.Fatal("cross-origin subscribe stored")
	}
	if rr := do("POST", "/api/app/push/subscribe", sub, "http://car.example"); rr.Code != 200 {
		t.Fatalf("subscribe: %d %s", rr.Code, rr.Body.String())
	}
	if hub.Count() != 1 {
		t.Fatal("not stored")
	}
	if got, _ := hub.Get("https://fcm.googleapis.com/fcm/send/xyz"); got == nil || got.Origin != "http://car.example" {
		t.Fatalf("subscription origin not stored: %+v", got)
	}
	if rr := do("POST", "/api/app/push/settings", `{"endpoint":"https://fcm.googleapis.com/fcm/send/xyz","hide_text":true}`, "http://car.example"); rr.Code != 200 {
		t.Fatalf("settings: %d", rr.Code)
	}
	if rr := do("POST", "/api/app/push/test", `{"endpoint":"https://fcm.googleapis.com/fcm/send/xyz"}`, "http://car.example"); rr.Code != 200 || len(tested) != 1 || !strings.Contains(tested[0], "text hidden") {
		t.Fatalf("test push: %d %v", rr.Code, tested)
	}
	if rr := do("POST", "/api/app/push/test", `{"endpoint":"https://fcm.googleapis.com/fcm/send/other"}`, "http://car.example"); rr.Code != 404 {
		t.Fatalf("test unknown device: %d", rr.Code)
	}
	if rr := do("GET", "/api/app/push/subscribe", "", ""); rr.Code != 405 {
		t.Fatalf("GET subscribe: %d", rr.Code)
	}
	if rr := do("POST", "/api/app/push/unsubscribe", `{"endpoint":"https://fcm.googleapis.com/fcm/send/xyz"}`, "http://car.example"); rr.Code != 200 || hub.Count() != 0 {
		t.Fatalf("unsubscribe: %d", rr.Code)
	}
}

func TestPushConfigWithoutHub(t *testing.T) {
	h, _, _ := newTestServer(t, nil)
	c := login(t, h)
	req := httptest.NewRequest("GET", "http://car.example/api/app/push/config", nil)
	req.AddCookie(c)
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	if rr.Code != 200 || !strings.Contains(rr.Body.String(), `"available":false`) {
		t.Fatalf("%d %s", rr.Code, rr.Body.String())
	}
}

func TestDeviceLabel(t *testing.T) {
	if got := deviceLabel("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"); got != "Chrome on Android" {
		t.Fatal(got)
	}
}
