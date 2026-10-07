package app

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/maxghenis/openmessage/internal/db"
)

func TestGrokSafeguards(t *testing.T) {
	a := newTestApp(t, &mockGMClient{})
	a.DataDir = t.TempDir()
	now := time.Now()
	msg := func(id, conv, body string, mine bool, age time.Duration) *db.Message {
		return &db.Message{MessageID: id, ConversationID: conv, Body: body, IsFromMe: mine, TimestampMS: now.Add(-age).UnixMilli()}
	}
	// Off by default.
	if st := a.GrokStatus(); st.Enabled || st.Trigger != "me" {
		t.Fatalf("defaults = %+v", st)
	}
	if ok, why := a.grokShouldReply(msg("m0", "c1", "@Grok hi", true, 0), now); ok || why != "disabled" {
		t.Fatalf("disabled: ok=%v why=%q", ok, why)
	}
	if _, err := a.SetGrokSettings(GrokSettings{Enabled: true, Trigger: "bogus"}); err != nil {
		t.Fatal(err)
	}
	t.Setenv("XAI_API_KEY", "")
	if ok, why := a.grokShouldReply(msg("m1", "c1", "@Grok hi", true, 0), now); ok || why != "no XAI_API_KEY" {
		t.Fatalf("no key: ok=%v why=%q", ok, why)
	}
	t.Setenv("XAI_API_KEY", "test-key")
	cases := []struct {
		m    *db.Message
		want string
	}{
		{msg("x1", "c1", "hello there", true, 0), "no mention"},
		{msg("x2", "c1", "email me@grok.com", true, 0), "no mention"},
		{msg("x3", "c1", GrokReplyPrefix+"@grok said hi", true, 0), "Grok's own message"},
		{msg("x4", "c1", "@grok old", true, 10*time.Minute), "not a live message"},
		{msg("tmp_123", "c1", "@grok placeholder", true, 0), "not a real message"},
		{msg("x5", "c1", "hey @Grok what's up", false, 0), "only you can trigger"},
	}
	for _, c := range cases {
		if ok, why := a.grokShouldReply(c.m, now); ok || why != c.want {
			t.Fatalf("%s: ok=%v why=%q want %q", c.m.MessageID, ok, why, c.want)
		}
	}
	if ok, _ := a.grokShouldReply(msg("y1", "c1", "@Grok summarize", true, 0), now); !ok {
		t.Fatal("own mention should trigger")
	}
	if ok, why := a.grokShouldReply(msg("y1", "c1", "@Grok summarize", true, 0), now); ok || why != "already handled" {
		t.Fatalf("same message twice: %v %q", ok, why)
	}
	if ok, why := a.grokShouldReply(msg("y2", "c1", "@Grok again", true, 0), now.Add(10*time.Second)); ok || why != "chat rate limit" {
		t.Fatalf("per-chat limit: %v %q", ok, why)
	}
	// Everyone mode; other chats; daily limit.
	if _, err := a.SetGrokSettings(GrokSettings{Enabled: true, Trigger: "everyone"}); err != nil {
		t.Fatal(err)
	}
	if ok, _ := a.grokShouldReply(msg("z0", "c2", "@grok hi", false, 0), now); !ok {
		t.Fatal("everyone mode: others should trigger")
	}
	for i := 0; i < grokDailyLimit; i++ {
		a.grokShouldReply(msg("d"+string(rune('a'+i)), "chat"+string(rune('a'+i)), "@grok", true, 0), now)
	}
	if ok, why := a.grokShouldReply(msg("last", "zz", "@grok", true, 0), now); ok || why != "daily limit" {
		t.Fatalf("daily limit: %v %q", ok, why)
	}
	// Settings persist.
	b := &App{DataDir: a.DataDir, Store: a.Store}
	if st := b.GrokStatus(); !st.Enabled || st.Trigger != "everyone" || !st.KeyConfigured {
		t.Fatalf("persisted = %+v", st)
	}
}

func TestGrokCompleteAndContext(t *testing.T) {
	var gotAuth string
	var gotBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"  Sure, 7pm works.  "}}]}`))
	}))
	defer srv.Close()
	t.Setenv("XAI_API_KEY", "k-123")
	t.Setenv("XAI_BASE_URL", srv.URL)
	a := newTestApp(t, &mockGMClient{})
	_ = a.Store.UpsertMessage(&db.Message{MessageID: "a", ConversationID: "c", Body: "dinner?", SenderName: "Ann", TimestampMS: 1})
	_ = a.Store.UpsertMessage(&db.Message{MessageID: "b", ConversationID: "c", Body: "@Grok pick a time", IsFromMe: true, TimestampMS: 2})
	ctx := a.grokContext(&db.Message{MessageID: "b", ConversationID: "c", Body: "@Grok pick a time", IsFromMe: true, TimestampMS: 2})
	if ctx != "Ann: dinner?\nMe: @Grok pick a time" {
		t.Fatalf("context = %q", ctx)
	}
	out, err := a.grokComplete(context.Background(), ctx)
	if err != nil || grokTrimReply(out) != "Sure, 7pm works." {
		t.Fatalf("out=%q err=%v", out, err)
	}
	if gotAuth != "Bearer k-123" || gotBody["model"] != grokDefaultModel {
		t.Fatalf("auth=%q body=%v", gotAuth, gotBody)
	}
	if long := grokTrimReply(strings.Repeat("x", 2000)); len([]rune(long)) != grokReplyMaxChars+1 {
		t.Fatalf("reply not capped: %d", len([]rune(long)))
	}
}
