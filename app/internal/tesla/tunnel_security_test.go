package tesla

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

// Requests through cloudflared arrive from loopback with Cf-Connecting-Ip /
// X-Forwarded-* set. None of that may substitute for the login.
func TestEveryRouteRequiresLoginEvenFromLoopbackTunnel(t *testing.T) {
	h, _, inner := newTestServer(t, nil)
	paths := []string{
		"/", "/tesla/", "/tesla/index.html", "/tesla/app.js", "/index.html", "/sw.js", "/manifest.webmanifest",
		"/admin/cookies", "/admin/cookies/clear",
		"/api/conversations", "/api/conversations/abc/messages", "/api/events", "/api/status", "/api/diagnostics",
		"/api/avatar?id=1", "/api/media/abc", "/api/link-preview-image?url=x", "/api/whatsapp/avatar?jid=x",
		"/api/send", "/api/send-media", "/api/unpair", "/api/google/reconnect", "/api/contacts", "/api/people",
		"/api/v1/outbox", "/api/v1/messages/1/attachments/0",
		"/api/tesla/config", "/api/tesla/pairing", "/api/tesla/pairing/start", "/api/tesla/wallpapers",
		wallpaperURLPrefix + "x.jpg", "/api/tesla/themes", "/api/tesla/theme", "/api/tesla/theme/background",
		"/api/tesla/typing", "/api/tesla/contacts", "/api/tesla/folder", "/api/transcribe",
		"/mcp", "/mcp/sse", "/mcp/message", "/auth/bootstrap?t=x",
	}
	for _, p := range paths {
		for _, m := range []string{http.MethodGet, http.MethodPost} {
			req := httptest.NewRequest(m, "http://abc-def.trycloudflare.com"+p, strings.NewReader("{}"))
			req.RemoteAddr = "127.0.0.1:40000"
			req.Header.Set("Cf-Connecting-Ip", "198.51.100.7")
			req.Header.Set("X-Forwarded-For", "127.0.0.1")
			req.Header.Set("X-Forwarded-Proto", "https")
			req.Header.Set("Authorization", "Bearer guess")
			rr := httptest.NewRecorder()
			h.ServeHTTP(rr, req)
			ok := rr.Code == http.StatusUnauthorized ||
				(rr.Code == http.StatusFound && strings.HasPrefix(rr.Header().Get("Location"), "/login"))
			if !ok {
				t.Errorf("%s %s without login: %d %q", m, p, rr.Code, rr.Header().Get("Location"))
			}
		}
	}
	if n := inner.hits.Load(); n != 0 {
		t.Fatalf("unauthenticated requests reached OpenMessage handler %d times", n)
	}
}

func TestLoginThroughTunnelOriginSetsSecureCookie(t *testing.T) {
	h, _, _ := newTestServer(t, nil)
	post := func(origin, site string) *httptest.ResponseRecorder {
		form := url.Values{"secret": {testSecret}, "next": {"/tesla/"}}
		req := httptest.NewRequest(http.MethodPost, "http://abc-def.trycloudflare.com/login", strings.NewReader(form.Encode()))
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		req.RemoteAddr = "127.0.0.1:40000"
		req.Header.Set("Cf-Connecting-Ip", "198.51.100.7")
		req.Header.Set("X-Forwarded-Proto", "https")
		if origin != "" {
			req.Header.Set("Origin", origin)
		}
		if site != "" {
			req.Header.Set("Sec-Fetch-Site", site)
		}
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, req)
		return rr
	}
	for _, tc := range []struct{ origin, site string }{
		{"https://abc-def.trycloudflare.com", ""},
		{"https://abc-def.trycloudflare.com", "same-origin"},
		{"null", "same-origin"},
	} {
		rr := post(tc.origin, tc.site)
		if rr.Code != http.StatusSeeOther {
			t.Fatalf("tunnel login origin=%q site=%q: %d %s", tc.origin, tc.site, rr.Code, rr.Body.String())
		}
		var got *http.Cookie
		for _, c := range rr.Result().Cookies() {
			if c.Name == SessionCookieName {
				got = c
			}
		}
		if got == nil || !got.Secure || !got.HttpOnly || got.SameSite != http.SameSiteLaxMode {
			t.Fatalf("session cookie over https tunnel must be Secure+HttpOnly+Lax: %+v", got)
		}
	}
	if rr := post("https://evil.example", ""); rr.Code != http.StatusForbidden {
		t.Fatalf("cross-origin tunnel login: %d", rr.Code)
	}
	if rr := post("https://abc-def.trycloudflare.com", "cross-site"); rr.Code != http.StatusForbidden {
		t.Fatalf("cross-site tunnel login: %d", rr.Code)
	}
}

func TestPlainHTTPLoginCookieNotSecure(t *testing.T) {
	h, _, _ := newTestServer(t, nil)
	if c := login(t, h); c.Secure {
		t.Fatal("plain-http LAN login must not set a Secure cookie (browser would drop it)")
	}
}

func TestProxyReplacesClientAuthorizationWithControlToken(t *testing.T) {
	var seen []string
	rec := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = append(seen, r.Header.Get("Authorization"))
		w.WriteHeader(200)
	})
	h, _, _ := newTestServer(t, func(_ *Config, d *Deps) {
		d.Inner = rec
		d.InnerAuthorize = func(r *http.Request) { r.Header.Set("Authorization", "Bearer inner-token") }
	})
	c := login(t, h)
	req := httptest.NewRequest(http.MethodGet, "http://car.example/api/conversations", nil)
	req.Header.Set("Authorization", "Bearer attacker")
	req.AddCookie(c)
	h.ServeHTTP(httptest.NewRecorder(), req)
	if len(seen) != 1 || seen[0] != "Bearer inner-token" {
		t.Fatalf("inner saw Authorization %q", seen)
	}

	// Without an InnerAuthorize hook, client credentials are still dropped.
	seen = nil
	h2, _, _ := newTestServer(t, func(_ *Config, d *Deps) { d.Inner = rec })
	c = login(t, h2)
	req = httptest.NewRequest(http.MethodGet, "http://car.example/api/conversations", nil)
	req.Header.Set("Authorization", "Bearer attacker")
	req.AddCookie(c)
	h2.ServeHTTP(httptest.NewRecorder(), req)
	if len(seen) != 1 || seen[0] != "" {
		t.Fatalf("inner saw Authorization %q", seen)
	}
}

func TestClientIPTrustsProxyHeadersOnlyFromLoopback(t *testing.T) {
	mk := func(remote, cf, xff string) *http.Request {
		r := httptest.NewRequest(http.MethodPost, "http://x/login", nil)
		r.RemoteAddr = remote
		if cf != "" {
			r.Header.Set("Cf-Connecting-Ip", cf)
		}
		if xff != "" {
			r.Header.Set("X-Forwarded-For", xff)
		}
		return r
	}
	cases := []struct{ remote, cf, xff, want string }{
		{"127.0.0.1:1", "198.51.100.7", "", "198.51.100.7"},
		{"[::1]:1", "", "203.0.113.5, 10.0.0.1", "203.0.113.5"},
		{"192.168.1.20:1", "198.51.100.7", "203.0.113.5", "192.168.1.20"},
		{"127.0.0.1:1", "", "", "127.0.0.1"},
	}
	for _, c := range cases {
		if got := clientIP(mk(c.remote, c.cf, c.xff)); got != c.want {
			t.Errorf("clientIP(%s cf=%q xff=%q) = %q want %q", c.remote, c.cf, c.xff, got, c.want)
		}
	}
}

func TestLoginLimiterPerIPAndGlobal(t *testing.T) {
	l := newLoginLimiter()
	now := time.Unix(1_700_000_000, 0)
	for i := 0; i < loginMaxFails; i++ {
		l.fail("a", now)
	}
	if l.blockedFor("a", now) <= 0 {
		t.Fatal("ip a should be blocked")
	}
	if l.blockedFor("b", now) != 0 {
		t.Fatal("ip b should not be blocked by a's failures")
	}
	if l.blockedFor("a", now.Add(loginWindow+time.Second)) != 0 {
		t.Fatal("block should expire after the window")
	}
	// Rotating IPs: the global cap kicks in.
	l = newLoginLimiter()
	for i := 0; i < loginGlobalMaxFails; i++ {
		l.fail("ip-"+string(rune('A'+i)), now)
	}
	if l.blockedFor("fresh-ip", now) <= 0 {
		t.Fatal("global failure cap should block new IPs too")
	}
	if l.blockedFor("fresh-ip", now.Add(loginWindow+time.Second)) != 0 {
		t.Fatal("global block should expire")
	}
}
