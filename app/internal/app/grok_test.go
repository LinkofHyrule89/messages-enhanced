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
	if _, ok, why := a.grokShouldReply(msg("m0", "c1", "@Grok hi", true, 0), now); ok || why != "disabled" {
		t.Fatalf("disabled: ok=%v why=%q", ok, why)
	}
	if _, err := a.SetGrokSettings(GrokSettings{Enabled: true, Trigger: "bogus"}); err != nil {
		t.Fatal(err)
	}
	t.Setenv("XAI_API_KEY", "")
	if _, ok, why := a.grokShouldReply(msg("m1", "c1", "@Grok hi", true, 0), now); ok || why != "no XAI_API_KEY" {
		t.Fatalf("no key: ok=%v why=%q", ok, why)
	}
	t.Setenv("XAI_API_KEY", "test-key")
	cases := []struct {
		m    *db.Message
		want string
	}{
		{msg("x1", "c1", "hello there", true, 0), "no mention"},
		{msg("x2", "c1", "email me@grok.com", true, 0), "no mention"},
		{msg("x3", "c1", GrokReplyPrefix+"@grok said hi", true, 0), "a bot's own message"},
		{msg("x4", "c1", "@grok old", true, 10*time.Minute), "not a live message"},
		{msg("tmp_123", "c1", "@grok placeholder", true, 0), "not a real message"},
		{msg("x5", "c1", "hey @Grok what's up", false, 0), "only you can trigger"},
	}
	for _, c := range cases {
		if _, ok, why := a.grokShouldReply(c.m, now); ok || why != c.want {
			t.Fatalf("%s: ok=%v why=%q want %q", c.m.MessageID, ok, why, c.want)
		}
	}
	if _, ok, _ := a.grokShouldReply(msg("y1", "c1", "@Grok summarize", true, 0), now); !ok {
		t.Fatal("own mention should trigger")
	}
	if _, ok, why := a.grokShouldReply(msg("y1", "c1", "@Grok summarize", true, 0), now); ok || why != "already handled" {
		t.Fatalf("same message twice: %v %q", ok, why)
	}
	if _, ok, why := a.grokShouldReply(msg("y2", "c1", "@Grok again", true, 0), now.Add(10*time.Second)); ok || why != "chat rate limit" {
		t.Fatalf("per-chat limit: %v %q", ok, why)
	}
	// Everyone mode; other chats; daily limit.
	if _, err := a.SetGrokSettings(GrokSettings{Enabled: true, Trigger: "everyone"}); err != nil {
		t.Fatal(err)
	}
	if _, ok, _ := a.grokShouldReply(msg("z0", "c2", "@grok hi", false, 0), now); !ok {
		t.Fatal("everyone mode: others should trigger")
	}
	for i := 0; i < grokDailyLimit; i++ {
		a.grokShouldReply(msg("d"+string(rune('a'+i)), "chat"+string(rune('a'+i)), "@grok", true, 0), now)
	}
	if _, ok, why := a.grokShouldReply(msg("last", "zz", "@grok", true, 0), now); ok || why != "daily limit" {
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
	if !strings.Contains(p, "Tomorrow is Thursday, October 8; this weekend is Saturday, October 10 to Sunday, October 11.") {
		t.Fatalf("date hints: %q", p)
	}
	for in, want := range map[string]string{"2026-10-10": "Saturday, October 10 to Sunday, October 11", "2026-10-11": "Saturday, October 10 to Sunday, October 11", "2026-10-12": "Saturday, October 17 to Sunday, October 18"} {
		d, _ := time.Parse("2006-01-02", in)
		if h := grokDateHints(d.Add(12 * time.Hour)); !strings.Contains(h, want) {
			t.Fatalf("%s: %q", in, h)
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

func TestGroqTriggerAndLimits(t *testing.T) {
	a := newTestApp(t, &mockGMClient{})
	a.DataDir = t.TempDir()
	now := time.Now()
	msg := func(id, conv, body string) *db.Message {
		return &db.Message{MessageID: id, ConversationID: conv, Body: body, IsFromMe: true, TimestampMS: now.UnixMilli()}
	}
	for body, want := range map[string]string{
		"@Groq hi": botGroq, "@groq hi": botGroq, "hey @GROQ?": botGroq, "@Grok hi": botGrok,
		"@Groq vs @Grok": botGroq, "@Grok vs @Groq": botGrok, "@Groqy hi": "", "a@groq.com": "", "@Grokq": "", "nothing": "",
	} {
		if got := botMentioned(body); got != want {
			t.Fatalf("botMentioned(%q) = %q, want %q", body, got, want)
		}
	}
	t.Setenv("XAI_API_KEY", "x-key")
	t.Setenv("GROQ_API_KEY", "")
	if st := a.GrokStatus(); st.GroqEnabled || st.GroqKeyConfigured || st.GroqDailyLimit != groqDailyLimit || !st.GroqSearch {
		t.Fatalf("groq defaults = %+v", st)
	}
	// @Grok on, @Groq off: each toggle is separate.
	if _, err := a.SetGrokSettings(GrokSettings{Enabled: true, Trigger: "me"}); err != nil {
		t.Fatal(err)
	}
	if bot, ok, why := a.grokShouldReply(msg("q1", "c1", "@Groq hi"), now); bot != botGroq || ok || why != "disabled" {
		t.Fatalf("groq off: %s %v %q", bot, ok, why)
	}
	if _, err := a.SetGrokSettings(GrokSettings{Enabled: true, Trigger: "me", GroqEnabled: true}); err != nil {
		t.Fatal(err)
	}
	if _, ok, why := a.grokShouldReply(msg("q2", "c1", "@Groq hi"), now); ok || why != "no GROQ_API_KEY" {
		t.Fatalf("groq no key: %v %q", ok, why)
	}
	t.Setenv("GROQ_API_KEY", "gsk-test")
	for _, own := range []string{GroqReplyPrefix + "@Grok said", GrokReplyPrefix + "@Groq said"} {
		if _, ok, why := a.grokShouldReply(msg("own"+own[:3], "c1", own), now); ok || why != "a bot's own message" {
			t.Fatalf("own %q: %v %q", own, ok, why)
		}
	}
	// Separate limits: a Grok reply doesn't block Groq in the same chat.
	if bot, ok, _ := a.grokShouldReply(msg("q3", "c1", "@Grok hi"), now); bot != botGrok || !ok {
		t.Fatal("grok should reply")
	}
	if bot, ok, _ := a.grokShouldReply(msg("q4", "c1", "@Groq hi"), now); bot != botGroq || !ok {
		t.Fatal("groq should reply")
	}
	if _, ok, why := a.grokShouldReply(msg("q5", "c1", "@Groq again"), now.Add(5*time.Second)); ok || why != "chat rate limit" {
		t.Fatalf("groq chat limit: %v %q", ok, why)
	}
	// A 429 pauses @Groq only.
	a.grok().mu.Lock()
	a.grok().groqLim.pausedUntil = now.Add(time.Minute)
	a.grok().mu.Unlock()
	if _, ok, why := a.grokShouldReply(msg("q6", "c2", "@Groq hi"), now); ok || why != "provider rate limit" {
		t.Fatalf("paused: %v %q", ok, why)
	}
	if _, ok, _ := a.grokShouldReply(msg("q7", "c2", "@Grok hi"), now); !ok {
		t.Fatal("grok unaffected by groq pause")
	}
	if st := a.GrokStatus(); st.GroqRepliesToday != 1 || st.RepliesToday != 2 {
		t.Fatalf("counts = %+v", st)
	}
	if got := grokLine(&db.Message{Body: GroqReplyPrefix + "hi", IsFromMe: true}); got != "Groq: hi" {
		t.Fatalf("grokLine = %q", got)
	}
	if d := groqRetryAfter("7"); d != 7*time.Second {
		t.Fatalf("retry-after = %v", d)
	}
	if d := groqRetryAfter(""); d != groqDefaultBackoff {
		t.Fatalf("retry-after default = %v", d)
	}
}

func TestGroqComplete(t *testing.T) {
	var gotPath, gotAuth string
	var gotBody map[string]any
	status, retryAfter, calls := 200, "40", 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath, gotAuth = r.URL.Path, r.Header.Get("Authorization")
		gotBody = nil
		calls++
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		if code := status; code != 200 {
			if retryAfter == "1" {
				status = 200 // the retry succeeds
			}
			w.Header().Set("Retry-After", retryAfter)
			w.WriteHeader(code)
			_, _ = w.Write([]byte(`{"error":{"message":"rate limited"}}`))
			return
		}
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"Tomorrow in Springfield: sunny, high **83\u202f°F** 【2†L6-L10】.","executed_tools":[{"type":"search"},{"type":"open"}]}}],"usage":{"total_tokens":3700}}`))
	}))
	defer srv.Close()
	t.Setenv("MESSAGES_GROQ_BASE_URL", srv.URL+"/openai/v1")
	t.Setenv("GROQ_API_KEY", "gsk-123")
	t.Setenv("GROQ_CHAT_MODEL", "")
	a := &App{}
	ans, err := a.groqComplete(context.Background(), "Me: @Groq weather tomorrow?", false)
	if err != nil {
		t.Fatal(err)
	}
	if got := grokTrimReply(ans.Text); got != "Tomorrow in Springfield: sunny, high 83 °F." || ans.Searches != 2 || ans.Tokens != 3700 {
		t.Fatalf("ans = %+v (%q)", ans, got)
	}
	tools, _ := json.Marshal(gotBody["tools"])
	if gotPath != "/openai/v1/chat/completions" || gotAuth != "Bearer gsk-123" || gotBody["model"] != groqDefaultModel ||
		string(tools) != `[{"type":"browser_search"}]` || gotBody["tool_choice"] != "auto" || gotBody["reasoning_effort"] != "low" {
		t.Fatalf("path=%s auth=%s body=%v", gotPath, gotAuth, gotBody)
	}
	msgs, _ := json.Marshal(gotBody["messages"])
	if !strings.Contains(string(msgs), "You are Groq") || !strings.Contains(string(msgs), "search the web first") || !strings.Contains(string(msgs), "Right now it is") {
		t.Fatalf("prompt = %s", msgs)
	}
	// Pictures must search.
	if _, err := a.groqComplete(context.Background(), "Me: @Groq picture of a fox", true); err != nil || gotBody["tool_choice"] != "required" {
		t.Fatalf("image: err=%v body=%v", err, gotBody)
	}
	// A model without browser search: no tools, and it says it can't look things up.
	t.Setenv("GROQ_CHAT_MODEL", "qwen/qwen3.8-27b")
	if _, err := a.groqComplete(context.Background(), "Me: @Groq news?", false); err != nil {
		t.Fatal(err)
	}
	msgs, _ = json.Marshal(gotBody["messages"])
	if _, has := gotBody["tools"]; has || gotBody["reasoning_effort"] != nil || !strings.Contains(string(msgs), "can't look up live information") {
		t.Fatalf("no-search body = %v", gotBody)
	}
	// 429: a short Retry-After is waited out once (here it's too long), then
	// @Groq pauses; the key never shows in the error.
	status, retryAfter, calls = 429, "1", 0
	if _, err := a.groqComplete(context.Background(), "Me: @Groq hi", false); err != nil || calls != 2 {
		t.Fatalf("short 429 retry: err=%v calls=%d", err, calls)
	}
	status, retryAfter, calls = 429, "40", 0
	_, err = a.groqComplete(context.Background(), "Me: @Groq hi", false)
	if err == nil || calls != 1 || strings.Contains(grokSafeErr(err), "gsk-123") {
		t.Fatalf("429 err = %v calls=%d", err, calls)
	}
	if until := a.grok().groqLim.pausedUntil; time.Until(until) < 30*time.Second {
		t.Fatalf("pause = %v", time.Until(until))
	}
}
