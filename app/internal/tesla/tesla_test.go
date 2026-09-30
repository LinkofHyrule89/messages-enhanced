package tesla

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/rs/zerolog"
)

const testSecret = "correct-horse-battery-staple-42"

type innerRecorder struct {
	hits     atomic.Int32
	lastHost atomic.Value
	lastOrig atomic.Value
}

func (i *innerRecorder) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	i.hits.Add(1)
	i.lastHost.Store(r.Host)
	i.lastOrig.Store(r.Header.Get("Origin"))
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write([]byte(`[]`))
}

func newTestServer(t *testing.T, mutate func(*Config, *Deps)) (http.Handler, *Server, *innerRecorder) {
	t.Helper()
	cfg := Config{
		Secret:        testSecret,
		SessionTTL:    time.Hour,
		CookieSecure:  "auto",
		STTProvider:   "fake",
		MaxAudioBytes: 1 << 20,
		MaxRecordSecs: 60,
	}
	inner := &innerRecorder{}
	deps := Deps{DataDir: t.TempDir(), SessionPath: "unused", Inner: inner, InnerHost: "127.0.0.1:7007", Logger: zerolog.Nop()}
	if mutate != nil {
		mutate(&cfg, &deps)
	}
	h, s, err := NewHandler(cfg, deps)
	if err != nil {
		t.Fatal(err)
	}
	return h, s, inner
}

func login(t *testing.T, h http.Handler) *http.Cookie {
	t.Helper()
	form := url.Values{"secret": {testSecret}, "next": {"/tesla/"}}
	req := httptest.NewRequest(http.MethodPost, "http://car.example/login", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	if rr.Code != http.StatusSeeOther {
		t.Fatalf("login status = %d body=%s", rr.Code, rr.Body.String())
	}
	for _, c := range rr.Result().Cookies() {
		if c.Name == SessionCookieName {
			if !c.HttpOnly {
				t.Fatal("session cookie must be HttpOnly")
			}
			if c.SameSite != http.SameSiteLaxMode {
				t.Fatalf("SameSite = %v", c.SameSite)
			}
			return c
		}
	}
	t.Fatal("no session cookie set")
	return nil
}

// ---------- auth ----------

func TestAuthRedirectsPagesAndRejectsAPIWithoutLogin(t *testing.T) {
	h, _, inner := newTestServer(t, nil)

	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "http://car.example/tesla/", nil))
	if rr.Code != http.StatusFound || !strings.HasPrefix(rr.Header().Get("Location"), "/login?next=") {
		t.Fatalf("page without login: %d %q", rr.Code, rr.Header().Get("Location"))
	}

	for _, path := range []string{"/api/conversations", "/api/transcribe", "/api/tesla/pairing", "/api/events"} {
		rr = httptest.NewRecorder()
		h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "http://car.example"+path, nil))
		if rr.Code != http.StatusUnauthorized {
			t.Fatalf("%s without login: got %d", path, rr.Code)
		}
	}
	if inner.hits.Load() != 0 {
		t.Fatal("unauthenticated request reached OpenMessage handler")
	}
	// public endpoints
	for _, path := range []string{"/login", "/healthz", "/tesla/app.css"} {
		rr = httptest.NewRecorder()
		h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "http://car.example"+path, nil))
		if rr.Code != 200 {
			t.Fatalf("%s should be public, got %d", path, rr.Code)
		}
	}
}

func TestAuthWrongSecretAndRateLimit(t *testing.T) {
	h, _, _ := newTestServer(t, nil)
	post := func(secret string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodPost, "http://car.example/login", strings.NewReader(url.Values{"secret": {secret}}.Encode()))
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		req.RemoteAddr = "203.0.113.9:5555"
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, req)
		return rr
	}
	for i := 0; i < loginMaxFails; i++ {
		rr := post("nope")
		if rr.Code != http.StatusUnauthorized {
			t.Fatalf("attempt %d: %d", i, rr.Code)
		}
		for _, c := range rr.Result().Cookies() {
			if c.Name == SessionCookieName && c.Value != "" {
				t.Fatal("cookie issued for wrong secret")
			}
		}
	}
	if rr := post(testSecret); rr.Code != http.StatusTooManyRequests {
		t.Fatalf("expected rate limit, got %d", rr.Code)
	}
}

func TestAuthCookieGrantsAccessAndProxiesAsLoopback(t *testing.T) {
	h, _, inner := newTestServer(t, nil)
	c := login(t, h)

	req := httptest.NewRequest(http.MethodGet, "http://car.example/api/conversations", nil)
	req.Header.Set("Origin", "https://car.example")
	req.AddCookie(c)
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	if rr.Code != 200 || inner.hits.Load() != 1 {
		t.Fatalf("authed API: %d hits=%d", rr.Code, inner.hits.Load())
	}
	if got := inner.lastHost.Load().(string); got != "127.0.0.1:7007" {
		t.Fatalf("inner Host = %q, want loopback", got)
	}
	if got := inner.lastOrig.Load().(string); got != "" {
		t.Fatalf("Origin should be stripped before inner handler, got %q", got)
	}

	// Root redirects to the Tesla UI.
	req = httptest.NewRequest(http.MethodGet, "http://car.example/", nil)
	req.AddCookie(c)
	rr = httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	if rr.Code != http.StatusFound || rr.Header().Get("Location") != "/tesla/" {
		t.Fatalf("root: %d %s", rr.Code, rr.Header().Get("Location"))
	}
}

func TestAuthRejectsCrossOriginWrites(t *testing.T) {
	h, _, inner := newTestServer(t, nil)
	c := login(t, h)
	req := httptest.NewRequest(http.MethodPost, "http://car.example/api/send", strings.NewReader(`{}`))
	req.Header.Set("Origin", "https://evil.example")
	req.AddCookie(c)
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	if rr.Code != http.StatusForbidden || inner.hits.Load() != 0 {
		t.Fatalf("cross-origin POST: %d hits=%d", rr.Code, inner.hits.Load())
	}
	req = httptest.NewRequest(http.MethodPost, "http://car.example/api/send", strings.NewReader(`{}`))
	req.Header.Set("Origin", "http://car.example")
	req.AddCookie(c)
	rr = httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	if rr.Code != 200 || inner.hits.Load() != 1 {
		t.Fatalf("same-origin POST: %d hits=%d", rr.Code, inner.hits.Load())
	}
}

func TestSessionTokenTamperAndExpiry(t *testing.T) {
	a := NewAuth(testSecret, time.Hour, "auto")
	tok := a.mint()
	if !a.Valid(tok) {
		t.Fatal("fresh token invalid")
	}
	if a.Valid(tok[:len(tok)-2] + "AA") {
		t.Fatal("tampered token accepted")
	}
	if NewAuth("a-different-secret-value", time.Hour, "auto").Valid(tok) {
		t.Fatal("token valid under another secret")
	}
	a.now = func() time.Time { return time.Now().Add(2 * time.Hour) }
	if a.Valid(tok) {
		t.Fatal("expired token accepted")
	}
}

func TestSafeNext(t *testing.T) {
	for in, want := range map[string]string{"": "/tesla/", "/tesla/#pair": "/tesla/#pair", "//evil.com": "/tesla/", "https://evil.com": "/tesla/", "/\\evil": "/tesla/", "/login": "/tesla/"} {
		if got := safeNext(in); got != want {
			t.Errorf("safeNext(%q) = %q want %q", in, got, want)
		}
	}
}

// ---------- STT handler (fake provider) ----------

func authedReq(t *testing.T, h http.Handler, c *http.Cookie, method, path, ct string, body io.Reader) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, "http://car.example"+path, body)
	if ct != "" {
		req.Header.Set("Content-Type", ct)
	}
	req.Header.Set("Origin", "http://car.example")
	req.AddCookie(c)
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	return rr
}

func TestTranscribeRawBodyWithFakeProvider(t *testing.T) {
	h, _, inner := newTestServer(t, func(c *Config, _ *Deps) { c.FakeTranscript = "hello from the car" })
	c := login(t, h)
	rr := authedReq(t, h, c, http.MethodPost, "/api/transcribe", "audio/webm;codecs=opus", bytes.NewReader(bytes.Repeat([]byte{1}, 4000)))
	if rr.Code != 200 {
		t.Fatalf("status %d: %s", rr.Code, rr.Body.String())
	}
	var out struct {
		Text     string `json:"text"`
		Provider string `json:"provider"`
	}
	_ = json.Unmarshal(rr.Body.Bytes(), &out)
	if out.Text != "hello from the car" || out.Provider != "fake" {
		t.Fatalf("unexpected response %+v", out)
	}
	if inner.hits.Load() != 0 {
		t.Fatal("transcribe must not reach OpenMessage (and must not send anything)")
	}
}

func TestTranscribeMultipart(t *testing.T) {
	h, _, _ := newTestServer(t, nil)
	c := login(t, h)
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	hdr := map[string][]string{"Content-Disposition": {`form-data; name="file"; filename="a.ogg"`}, "Content-Type": {"audio/ogg"}}
	part, _ := mw.CreatePart(hdr)
	part.Write(bytes.Repeat([]byte{2}, 1234))
	mw.Close()
	rr := authedReq(t, h, c, http.MethodPost, "/api/transcribe", mw.FormDataContentType(), &buf)
	if rr.Code != 200 || !strings.Contains(rr.Body.String(), "1234 bytes audio/ogg") {
		t.Fatalf("multipart: %d %s", rr.Code, rr.Body.String())
	}
}

func TestTranscribeRejections(t *testing.T) {
	h, _, _ := newTestServer(t, func(c *Config, _ *Deps) { c.MaxAudioBytes = 1000 })
	c := login(t, h)
	cases := []struct {
		name, method, ct string
		body             []byte
		want             int
	}{
		{"empty", http.MethodPost, "audio/webm", nil, 400},
		{"too large", http.MethodPost, "audio/webm", bytes.Repeat([]byte{1}, 5000), 413},
		{"wrong type", http.MethodPost, "text/plain", []byte("hello"), 415},
		{"GET", http.MethodGet, "", nil, 405},
	}
	for _, tc := range cases {
		rr := authedReq(t, h, c, tc.method, "/api/transcribe", tc.ct, bytes.NewReader(tc.body))
		if rr.Code != tc.want {
			t.Errorf("%s: got %d want %d (%s)", tc.name, rr.Code, tc.want, rr.Body.String())
		}
	}
}

func TestTranscribeDisabledProvider(t *testing.T) {
	h, _, _ := newTestServer(t, func(c *Config, _ *Deps) { c.STTProvider = "none" })
	c := login(t, h)
	rr := authedReq(t, h, c, http.MethodPost, "/api/transcribe", "audio/webm", bytes.NewReader([]byte("abc")))
	if rr.Code != http.StatusServiceUnavailable {
		t.Fatalf("got %d", rr.Code)
	}
}

type errTranscriber struct{}

func (errTranscriber) Name() string { return "broken" }
func (errTranscriber) Transcribe(context.Context, []byte, string) (string, error) {
	return "", io.ErrUnexpectedEOF
}

func TestTranscribeProviderError(t *testing.T) {
	h, _, _ := newTestServer(t, func(_ *Config, d *Deps) { d.Transcriber = errTranscriber{} })
	c := login(t, h)
	rr := authedReq(t, h, c, http.MethodPost, "/api/transcribe", "audio/webm", bytes.NewReader([]byte("abc")))
	if rr.Code != http.StatusBadGateway {
		t.Fatalf("got %d", rr.Code)
	}
}

func TestOpenAICompatibleProviderRequestShape(t *testing.T) {
	var gotAuth, gotModel, gotFileName, gotLang string
	var gotLen int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		if err := r.ParseMultipartForm(1 << 20); err != nil {
			t.Error(err)
		}
		gotModel = r.FormValue("model")
		gotLang = r.FormValue("language")
		f, hdr, err := r.FormFile("file")
		if err == nil {
			b, _ := io.ReadAll(f)
			gotLen = len(b)
			gotFileName = hdr.Filename
		}
		_, _ = w.Write([]byte(`{"text":"  see you soon "}`))
	}))
	defer srv.Close()
	tr, err := NewTranscriber(Config{STTProvider: "openai", OpenAIKey: "sk-test", OpenAIBaseURL: srv.URL, STTLanguage: "en"})
	if err != nil {
		t.Fatal(err)
	}
	text, err := tr.Transcribe(context.Background(), []byte("0123456789"), "audio/webm;codecs=opus")
	if err != nil {
		t.Fatal(err)
	}
	if text != "see you soon" || gotAuth != "Bearer sk-test" || gotModel != "gpt-4o-mini-transcribe" || gotFileName != "clip.webm" || gotLen != 10 || gotLang != "en" {
		t.Fatalf("text=%q auth=%q model=%q file=%q len=%d lang=%q", text, gotAuth, gotModel, gotFileName, gotLen, gotLang)
	}
}

func TestProviderSelection(t *testing.T) {
	if _, err := NewTranscriber(Config{STTProvider: "openai"}); err == nil {
		t.Error("openai without key should fail")
	}
	if _, err := NewTranscriber(Config{STTProvider: "groq"}); err == nil {
		t.Error("groq without key should fail")
	}
	if tr, _ := NewTranscriber(Config{STTProvider: "groq", GroqKey: "k"}); tr.Name() != "groq:whisper-large-v3-turbo" {
		t.Errorf("groq name %s", tr.Name())
	}
	if tr, _ := NewTranscriber(Config{STTProvider: "openai", OpenAIKey: "k", OpenAIModel: "whisper-1"}); tr.Name() != "openai:whisper-1" {
		t.Errorf("openai name %s", tr.Name())
	}
	if _, err := NewTranscriber(Config{STTProvider: "bogus"}); err == nil {
		t.Error("unknown provider should fail")
	}
}

func TestSTTModeFromEnv(t *testing.T) {
	t.Setenv("TESLA_SECRET", testSecret)
	cases := map[string]string{"": "auto", "AUTO": "auto", "builtin": "builtin", "browser": "builtin", " server ": "server"}
	for in, want := range cases {
		t.Setenv("TESLA_STT_MODE", in)
		c, err := ConfigFromEnv()
		if err != nil || c.STTMode != want {
			t.Errorf("TESLA_STT_MODE=%q: got %q, %v; want %q", in, c.STTMode, err, want)
		}
	}
	t.Setenv("TESLA_STT_MODE", "whisper")
	if _, err := ConfigFromEnv(); err == nil {
		t.Error("unknown TESLA_STT_MODE should be rejected")
	}
}

func TestProviderLabel(t *testing.T) {
	for in, want := range map[string]string{"groq:whisper-large-v3-turbo": "Groq", "openai:gpt-4o-mini-transcribe": "OpenAI", "fake": "Fake STT", "none": "none", "custom": "custom"} {
		if got := ProviderLabel(in); got != want {
			t.Errorf("ProviderLabel(%q) = %q, want %q", in, got, want)
		}
	}
}

func getConfig(t *testing.T, mutate func(*Config, *Deps)) map[string]any {
	t.Helper()
	h, _, _ := newTestServer(t, mutate)
	c := login(t, h)
	rr := authedReq(t, h, c, http.MethodGet, "/api/tesla/config", "", nil)
	if rr.Code != 200 {
		t.Fatalf("config status %d", rr.Code)
	}
	var out map[string]any
	if err := json.Unmarshal(rr.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	return out
}

func TestConfigEndpointReportsSTTMode(t *testing.T) {
	out := getConfig(t, nil) // mode unset in a hand-built Config means auto
	if out["stt_mode"] != "auto" || out["stt_enabled"] != true || out["stt_label"] != "Fake STT" {
		t.Fatalf("default config: %v", out)
	}
	out = getConfig(t, func(c *Config, _ *Deps) { c.STTMode = "server"; c.STTProvider = "none" })
	if out["stt_mode"] != "server" || out["stt_enabled"] != false || out["stt_provider"] != "none" {
		t.Fatalf("server/none config: %v", out)
	}
	out = getConfig(t, func(c *Config, _ *Deps) { c.STTMode = "builtin" })
	if out["stt_mode"] != "builtin" || out["stt_enabled"] != false {
		t.Fatalf("builtin config should report server STT off: %v", out)
	}
}

func TestTranscribeDisabledInBuiltinMode(t *testing.T) {
	h, _, _ := newTestServer(t, func(c *Config, _ *Deps) { c.STTMode = "builtin" })
	c := login(t, h)
	rr := authedReq(t, h, c, http.MethodPost, "/api/transcribe", "audio/webm", bytes.NewReader(bytes.Repeat([]byte{1}, 500)))
	if rr.Code != http.StatusServiceUnavailable || !strings.Contains(rr.Body.String(), "TESLA_STT_MODE=builtin") {
		t.Fatalf("got %d %s", rr.Code, rr.Body.String())
	}
}

func TestTranscribeResponseIncludesLabel(t *testing.T) {
	h, _, _ := newTestServer(t, func(c *Config, _ *Deps) { c.STTMode = "server" })
	c := login(t, h)
	rr := authedReq(t, h, c, http.MethodPost, "/api/transcribe", "audio/ogg", bytes.NewReader(bytes.Repeat([]byte{1}, 500)))
	if rr.Code != 200 || !strings.Contains(rr.Body.String(), `"label":"Fake STT"`) {
		t.Fatalf("got %d %s", rr.Code, rr.Body.String())
	}
}

// ---------- cookies ----------

func TestCookieVaultEncryptedAndPrivate(t *testing.T) {
	dir := t.TempDir()
	v := NewCookieVault(dir, testSecret)
	in := map[string]string{"SID": "sid-value-SECRET", "HSID": "h", "SSID": "s", "OSID": "o", "APISID": "a", "SAPISID": "sa"}
	if err := v.Save(in); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, VaultFile)
	st, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if st.Mode().Perm() != 0o600 {
		t.Fatalf("vault mode %v", st.Mode().Perm())
	}
	raw, _ := os.ReadFile(path)
	if bytes.Contains(raw, []byte("sid-value-SECRET")) || bytes.Contains(raw, []byte("SAPISID")) {
		t.Fatal("vault is not encrypted")
	}
	out, _, err := v.Load()
	if err != nil || out["SID"] != "sid-value-SECRET" {
		t.Fatalf("load: %v %v", err, out)
	}
	if _, _, err := NewCookieVault(dir, "another-secret-entirely").Load(); err == nil {
		t.Fatal("decrypted with wrong secret")
	}
}

func TestParseGoogleCookies(t *testing.T) {
	c, missing, err := ParseGoogleCookies(`{"SID":"1","HSID":"2","SSID":"3","OSID":"4","APISID":"5","SAPISID":"6","junk":"x"}`)
	if err != nil || len(missing) != 0 || c["junk"] != "" || c["SAPISID"] != "6" {
		t.Fatalf("json: %v %v %v", c, missing, err)
	}
	_, missing, _ = ParseGoogleCookies("Cookie: SID=1; HSID=2; SSID=3")
	if strings.Join(missing, ",") != "OSID,APISID,SAPISID" {
		t.Fatalf("header missing = %v", missing)
	}
	c, missing, err = ParseGoogleCookies(`curl 'https://messages.google.com/web/config' -H 'cookie: SID=1; HSID=2; SSID=3; OSID=4; APISID=5; SAPISID=6; __Secure-1PSIDTS=7'`)
	if err != nil || len(missing) != 0 || c["__Secure-1PSIDTS"] != "7" {
		t.Fatalf("curl: %v %v %v", c, missing, err)
	}
}

func TestAdminCookiesPageNeverEchoesValues(t *testing.T) {
	h, s, _ := newTestServer(t, nil)
	c := login(t, h)
	form := url.Values{"cookies": {`{"SID":"TOPSECRETVALUE","HSID":"2","SSID":"3","OSID":"4","APISID":"5","SAPISID":"6"}`}}
	rr := authedReq(t, h, c, http.MethodPost, "/admin/cookies", "application/x-www-form-urlencoded", strings.NewReader(form.Encode()))
	if rr.Code != 200 {
		t.Fatalf("status %d: %s", rr.Code, rr.Body.String())
	}
	if strings.Contains(rr.Body.String(), "TOPSECRETVALUE") {
		t.Fatal("cookie value echoed back")
	}
	if got, _, err := s.Vault().Load(); err != nil || got["SID"] != "TOPSECRETVALUE" {
		t.Fatal("cookies not stored")
	}
	form = url.Values{"cookies": {`SID=1; HSID=2`}}
	rr = authedReq(t, h, c, http.MethodPost, "/admin/cookies", "application/x-www-form-urlencoded", strings.NewReader(form.Encode()))
	if rr.Code != 400 || !strings.Contains(rr.Body.String(), "Missing required cookies") {
		t.Fatalf("partial cookies: %d", rr.Code)
	}
}

// ---------- pairing ----------

func TestFakePairingShowsEmojiWithoutNetwork(t *testing.T) {
	var ran atomic.Bool
	h, s, _ := newTestServer(t, func(c *Config, d *Deps) {
		c.FakePairing = "🦊"
		d.PairingRunner = func(context.Context, map[string]string, func(string)) error { ran.Store(true); return nil }
	})
	c := login(t, h)
	rr := authedReq(t, h, c, http.MethodPost, "/api/tesla/pairing/start", "", nil)
	if rr.Code != http.StatusAccepted {
		t.Fatalf("start: %d %s", rr.Code, rr.Body.String())
	}
	deadline := time.Now().Add(3 * time.Second)
	for s.Pairer().Status().State != PairShowEmoji && time.Now().Before(deadline) {
		time.Sleep(50 * time.Millisecond)
	}
	st := s.Pairer().Status()
	if st.State != PairShowEmoji || st.Emoji != "🦊" || !strings.HasSuffix(st.EmojiSVG, "/1f98a/emoji.svg") {
		t.Fatalf("status %+v", st)
	}
	if ran.Load() {
		t.Fatal("real pairing runner must not run in fake mode")
	}
	authedReq(t, h, c, http.MethodPost, "/api/tesla/pairing/cancel", "", nil)
	if s.Pairer().Status().State != PairIdle {
		t.Fatal("cancel did not reset")
	}
}

func TestRealPairingFlowUsesVaultCookies(t *testing.T) {
	var gotSID string
	reconnected := make(chan struct{}, 1)
	h, s, _ := newTestServer(t, func(_ *Config, d *Deps) {
		d.PairingRunner = func(_ context.Context, cookies map[string]string, onEmoji func(string)) error {
			gotSID = cookies["SID"]
			onEmoji("🐙")
			time.Sleep(100 * time.Millisecond)
			return nil
		}
		d.Reconnect = func() error { reconnected <- struct{}{}; return nil }
	})
	c := login(t, h)
	// No cookies yet -> error.
	if rr := authedReq(t, h, c, http.MethodPost, "/api/tesla/pairing/start", "", nil); rr.Code != http.StatusConflict {
		t.Fatalf("start without cookies: %d", rr.Code)
	}
	_ = s.Vault().Save(map[string]string{"SID": "abc", "HSID": "2", "SSID": "3", "OSID": "4", "APISID": "5", "SAPISID": "6"})
	if rr := authedReq(t, h, c, http.MethodPost, "/api/tesla/pairing/start", "", nil); rr.Code != http.StatusAccepted {
		t.Fatalf("start: %d", rr.Code)
	}
	select {
	case <-reconnected:
	case <-time.After(3 * time.Second):
		t.Fatal("reconnect not called")
	}
	time.Sleep(20 * time.Millisecond)
	if st := s.Pairer().Status(); st.State != PairSuccess || gotSID != "abc" {
		t.Fatalf("state %+v sid=%q", st, gotSID)
	}
}

func TestSameOriginWriteUsesFetchMetadata(t *testing.T) {
	mk := func(site, origin string) *http.Request {
		r := httptest.NewRequest(http.MethodPost, "http://car.example/login", nil)
		if site != "" {
			r.Header.Set("Sec-Fetch-Site", site)
		}
		if origin != "" {
			r.Header.Set("Origin", origin)
		}
		return r
	}
	cases := []struct {
		site, origin string
		want         bool
	}{
		{"same-origin", "null", true}, // form post under strict referrer policy
		{"same-origin", "http://car.example", true},
		{"cross-site", "http://car.example", false},
		{"same-site", "http://sub.car.example", false},
		{"", "", true},
		{"", "null", false},
		{"", "http://evil.example", false},
		{"", "http://car.example", true},
	}
	for _, c := range cases {
		if got := sameOriginWrite(mk(c.site, c.origin)); got != c.want {
			t.Errorf("site=%q origin=%q: got %v want %v", c.site, c.origin, got, c.want)
		}
	}
}
