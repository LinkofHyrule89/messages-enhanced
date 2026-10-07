package app

import (
	"context"
	"encoding/json"
	"errors"
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
	for _, k := range []string{"GROK_TIMEZONE", "GROK_USER_NAME", "GROK_USER_LOCATION"} {
		t.Setenv(k, "")
	}
	var gotAuth, gotPath string
	var gotBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth, gotPath = r.Header.Get("Authorization"), r.URL.Path
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		_, _ = w.Write([]byte(`{"output":[{"type":"web_search_call"},{"type":"message","content":[{"type":"output_text","text":"  **Sure**, 7pm works [[1]](https://ex.com/a).  ","annotations":[{"type":"url_citation","url":"https://ex.com/a"}]}]}]}`))
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
	ans, err := a.grokComplete(context.Background(), ctx, false)
	if err != nil || grokTrimReply(ans.Text) != "Sure, 7pm works." || ans.Source != "https://ex.com/a" || ans.Searches != 1 {
		t.Fatalf("ans=%+v err=%v", ans, err)
	}
	if gotAuth != "Bearer k-123" || gotPath != "/responses" || gotBody["model"] != grokDefaultModel {
		t.Fatalf("auth=%q path=%q body=%v", gotAuth, gotPath, gotBody)
	}
	tools, _ := json.Marshal(gotBody["tools"])
	if !strings.Contains(string(tools), `"web_search"`) || !strings.Contains(string(tools), `"x_search"`) {
		t.Fatalf("tools = %s", tools)
	}
	input, _ := json.Marshal(gotBody["input"])
	if !strings.Contains(string(input), "Right now it is") || !strings.Contains(string(input), "the phone's owner") || strings.Contains(string(input), "lives in") {
		t.Fatalf("system prompt lacks date/location: %s", input)
	}
	if long := grokTrimReply(strings.Repeat("x", 2000)); len([]rune(long)) != grokReplyMaxChars+1 {
		t.Fatalf("reply not capped: %d", len([]rune(long)))
	}
}

func TestGrokSystemPromptDate(t *testing.T) {
	t.Setenv("GROK_TIMEZONE", "America/New_York")
	t.Setenv("GROK_USER_NAME", "Sam")
	t.Setenv("GROK_USER_LOCATION", "Springfield")
	now := time.Date(2026, 10, 8, 3, 30, 0, 0, time.UTC) // 11:30 PM EDT on Oct 7
	p := grokSystemPrompt(now, false)
	for _, want := range []string{"Wednesday, October 7, 2026, 11:30 PM EDT (America/New_York time)", "from Sam, who lives in the Springfield area", "near me"} {
		if !strings.Contains(p, want) {
			t.Fatalf("prompt lacks %q: %q", want, p)
		}
	}
	if ip := grokSystemPrompt(now, true); !strings.Contains(ip, "IMAGE: https://commons.wikimedia.org/wiki/File:") || strings.Contains(ip, "no citations or URLs") {
		t.Fatalf("image prompt = %q", ip)
	}
}

func TestGrokPlainTextAndImageExtract(t *testing.T) {
	in := "## Movies\n- **Dune 3** (Dec 18) [1]\n- *Avatar* see [site](https://x.com/y)\n\n\n\nMore at https://example.com/z."
	if got := grokPlainText(in); got != "Movies\n• Dune 3 (Dec 18)\n• Avatar see site\n\nMore at." {
		t.Fatalf("plain = %q", got)
	}
	text, img := grokExtractImage("A red panda in a tree.\nIMAGE: https://upload.example.org/panda.jpg")
	if text != "A red panda in a tree." || img != "https://upload.example.org/panda.jpg" {
		t.Fatalf("text=%q img=%q", text, img)
	}
	if _, img := grokExtractImage("x\nIMAGE: http://127.0.0.1/a.png"); img != "" {
		t.Fatalf("loopback image URL accepted: %q", img)
	}
	if _, img := grokExtractImage("look ![p](https://cdn.example.com/p.webp)"); img != "https://cdn.example.com/p.webp" {
		t.Fatalf("markdown image not found: %q", img)
	}
	for _, u := range []string{"file:///etc/passwd", "ftp://a/b.png", "https://user:pw@a.com/x.png", "https://10.0.0.5/x.png", "https://[::1]/x.png", "https://a.com:8080/x.png", "https://100.100.100.100/x", "http://metadata.google.internal/x", "https://169.254.169.254/latest"} {
		if grokImageURLAllowed(u) == nil {
			t.Fatalf("%s should be refused", u)
		}
	}
	if !grokWantsImage("@Grok show me a picture of a red panda") || grokWantsImage("@Grok what movies are out") {
		t.Fatal("grokWantsImage")
	}
}

func TestGrokImageCandidates(t *testing.T) {
	// Wrong hash folders (as the model guesses them) are recomputed: md5("Red_Panda.JPG") starts c6.
	thumb := "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c6/Red_Panda.JPG/1280px-Red_Panda.JPG"
	orig := "https://upload.wikimedia.org/wikipedia/commons/c/c6/Red_Panda.JPG"
	for _, in := range []string{
		"https://upload.wikimedia.org/wikipedia/commons/0/0c/Red_Panda.JPG",
		"https://upload.wikimedia.org/wikipedia/commons/thumb/0/0c/Red_Panda.JPG/640px-Red_Panda.JPG",
		"https://commons.wikimedia.org/wiki/File:Red_Panda.JPG",
		"https://commons.wikimedia.org/wiki/File:red%20Panda.JPG",
	} {
		if got := grokImageCandidates(in); len(got) != 2 || got[0] != thumb || got[1] != orig {
			t.Fatalf("%s -> %v", in, got)
		}
	}
	if c := grokImageCandidates("https://example.com/a.jpg"); len(c) != 1 || c[0] != "https://example.com/a.jpg" {
		t.Fatalf("non-Commons -> %v", c)
	}
	if c := grokImageCandidates("https://commons.wikimedia.org/wiki/File:Map.svg"); len(c) != 1 {
		t.Fatalf("svg -> %v", c)
	}
}

func TestFetchGrokImage(t *testing.T) {
	png := []byte{0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/ok.png":
			w.Header().Set("Content-Type", "image/png")
			_, _ = w.Write(png)
		case "/html":
			w.Header().Set("Content-Type", "text/html")
			_, _ = w.Write([]byte("<html>"))
		case "/fake.jpg":
			w.Header().Set("Content-Type", "image/jpeg")
			_, _ = w.Write([]byte("not really a jpeg"))
		case "/huge.jpg":
			w.Header().Set("Content-Type", "image/jpeg")
			_, _ = w.Write(append([]byte{0xFF, 0xD8, 0xFF}, make([]byte, grokImageMaxBytes)...))
		}
	}))
	defer srv.Close()
	// Loopback is refused by default (SSRF guard, checked on the dialed IP).
	if _, _, _, err := fetchGrokImage(context.Background(), srv.URL+"/ok.png"); err == nil {
		t.Fatal("loopback download must be blocked")
	}
	grokImageAllowPrivate = true
	defer func() { grokImageAllowPrivate = false }()
	data, mime, name, err := fetchGrokImage(context.Background(), srv.URL+"/ok.png")
	if err != nil || mime != "image/png" || name != "grok-image.png" || len(data) != len(png) {
		t.Fatalf("ok.png: mime=%q name=%q err=%v", mime, name, err)
	}
	for _, p := range []string{"/html", "/fake.jpg", "/huge.jpg", "/missing"} {
		if _, _, _, err := fetchGrokImage(context.Background(), srv.URL+p); err == nil {
			t.Fatalf("%s should be rejected", p)
		}
	}
}

func TestGrokSafeErrRedactsKeyLike(t *testing.T) {
	got := grokSafeErr(errors.New("xAI API error: HTTP 400: Incorrect API key provided: xai-AbCd****wxyz"))
	if strings.Contains(got, "AbCd") || !strings.Contains(got, "[key]") {
		t.Fatalf("got %q", got)
	}
}
