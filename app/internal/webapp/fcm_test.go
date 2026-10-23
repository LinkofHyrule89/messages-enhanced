package webapp

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

const testFCMToken = "fcm:test-token_0123456789abcdefghij"

func TestFCMDeviceGetsMessagesAndClearOnRead(t *testing.T) {
	src := &fakeSource{conv: PushConversation{Name: "Synthetic Group", IsGroup: true, Unread: 1}}
	h, sent, mu, _ := newTestHub(t, src)
	if _, err := h.Subscribe(PushSubscription{Endpoint: testFCMToken}, nil); err == nil {
		t.Fatal("fcm device must be refused when FCM isn't configured")
	}
	h.fcm = &fcmSender{}
	if _, err := h.Subscribe(PushSubscription{Endpoint: "fcm:bad token!"}, nil); err == nil {
		t.Fatal("bad token accepted")
	}
	if _, err := h.Subscribe(PushSubscription{Endpoint: testFCMToken}, nil); err != nil {
		t.Fatal(err)
	}
	p, a := testKeys(t)
	if _, err := h.Subscribe(PushSubscription{Endpoint: "https://push.example.com/x", Keys: keysOf(p, a)}, nil); err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	h.now = func() time.Time { return now }
	src.mu.Lock()
	src.msgs = []PushMessage{{ID: "m1", SenderName: "Sam", Body: "hi", TimestampMS: now.UnixMilli()}}
	src.mu.Unlock()
	h.MessagesChanged("c1")
	waitPush(h)
	mu.Lock()
	if len(*sent) != 2 || (*sent)[0].n.Type != "message" {
		t.Fatalf("want message to both devices, got %+v", *sent)
	}
	*sent = nil
	mu.Unlock()

	// Read elsewhere: unread drops to 0 -> only the app device gets "clear".
	src.mu.Lock()
	src.conv.Unread = 0
	src.mu.Unlock()
	now = now.Add(pushClearGrace + time.Second)
	h.ConversationsChanged()
	time.Sleep(1200 * time.Millisecond)
	h.Wait()
	mu.Lock()
	defer mu.Unlock()
	if len(*sent) != 1 || (*sent)[0].endpoint != testFCMToken || (*sent)[0].n.Type != "clear" || (*sent)[0].n.Tag != pushTag("c1") {
		t.Fatalf("want one clear to the app device, got %+v", *sent)
	}
}

func TestFCMSenderHTTP(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/token":
			_ = r.ParseForm()
			if !strings.HasPrefix(r.Form.Get("grant_type"), "urn:ietf") || strings.Count(r.Form.Get("assertion"), ".") != 2 {
				w.WriteHeader(400)
				return
			}
			_, _ = io.WriteString(w, `{"access_token":"at","expires_in":3600}`)
		case "/send":
			if r.Header.Get("Authorization") != "Bearer at" {
				w.WriteHeader(401)
				return
			}
			_ = json.NewDecoder(r.Body).Decode(&got)
			if strings.Contains(got["message"].(map[string]any)["token"].(string), "stale") {
				w.WriteHeader(404)
				_, _ = io.WriteString(w, `{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}`)
				return
			}
			_, _ = io.WriteString(w, `{"name":"x"}`)
		}
	}))
	defer srv.Close()
	f := &fcmSender{email: "sa@example.com", key: key, tokenURI: srv.URL + "/token", sendBase: srv.URL + "/send", http: srv.Client()}
	payload, _ := json.Marshal(notification{Title: "T", Body: "B", Tag: "conv-1", Conv: "c", TS: 42, Type: "message"})
	st, err := f.send(context.Background(), "tok", payload)
	if err != nil || st != 200 {
		t.Fatalf("send: %d %v", st, err)
	}
	data := got["message"].(map[string]any)["data"].(map[string]any)
	if data["title"] != "T" || data["ts"] != "42" || data["type"] != "message" {
		t.Fatalf("data %v", data)
	}
	if st, _ := f.send(context.Background(), "stale", payload); st != 404 {
		t.Fatalf("stale token should map to 404, got %d", st)
	}
}
