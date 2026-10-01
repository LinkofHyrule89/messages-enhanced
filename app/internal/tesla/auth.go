package tesla

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/binary"
	"html/template"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

const SessionCookieName = "tm_session"

// Auth implements a single-user shared-secret login. A successful login sets
// an HttpOnly cookie containing an expiry and an HMAC over it keyed from the
// secret, so no server-side session state is needed and changing the secret
// logs every browser out.
type Auth struct {
	secret   []byte
	macKey   []byte
	ttl      time.Duration
	secure   string
	now      func() time.Time
	limiter  *loginLimiter
	loginTpl *template.Template
}

func NewAuth(secret string, ttl time.Duration, secure string) *Auth {
	m := hmac.New(sha256.New, []byte(secret))
	m.Write([]byte("tesla-messages/session-mac/v1"))
	return &Auth{
		secret:   []byte(secret),
		macKey:   m.Sum(nil),
		ttl:      ttl,
		secure:   secure,
		now:      time.Now,
		limiter:  newLoginLimiter(),
		loginTpl: template.Must(template.New("login").Parse(loginHTML)),
	}
}

func (a *Auth) mint() string {
	exp := a.now().Add(a.ttl).Unix()
	var b [8]byte
	binary.BigEndian.PutUint64(b[:], uint64(exp))
	m := hmac.New(sha256.New, a.macKey)
	m.Write(b[:])
	return base64.RawURLEncoding.EncodeToString(b[:]) + "." + base64.RawURLEncoding.EncodeToString(m.Sum(nil))
}

// Valid reports whether a session cookie value is authentic and unexpired.
func (a *Auth) Valid(v string) bool {
	parts := strings.SplitN(v, ".", 2)
	if len(parts) != 2 {
		return false
	}
	payload, err1 := base64.RawURLEncoding.DecodeString(parts[0])
	sig, err2 := base64.RawURLEncoding.DecodeString(parts[1])
	if err1 != nil || err2 != nil || len(payload) != 8 {
		return false
	}
	m := hmac.New(sha256.New, a.macKey)
	m.Write(payload)
	if !hmac.Equal(sig, m.Sum(nil)) {
		return false
	}
	return a.now().Unix() < int64(binary.BigEndian.Uint64(payload))
}

func (a *Auth) Authenticated(r *http.Request) bool {
	c, err := r.Cookie(SessionCookieName)
	return err == nil && a.Valid(c.Value)
}

func (a *Auth) secureCookie(r *http.Request) bool {
	switch a.secure {
	case "1", "true", "yes", "on":
		return true
	case "0", "false", "no", "off":
		return false
	}
	return r.TLS != nil || strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https")
}

func (a *Auth) setCookie(w http.ResponseWriter, r *http.Request, value string, maxAge int) {
	http.SetCookie(w, &http.Cookie{
		Name:     SessionCookieName,
		Value:    value,
		Path:     "/",
		MaxAge:   maxAge,
		HttpOnly: true,
		Secure:   a.secureCookie(r),
		// Lax (not Strict) so opening the app from a link/redirect, e.g.
		// the Tesla fullscreen youtube.com/redirect trick, stays logged in.
		SameSite: http.SameSiteLaxMode,
	})
}

// HandleLogin serves GET (form) and POST (check secret) on /login.
func (a *Auth) HandleLogin(w http.ResponseWriter, r *http.Request) {
	next := safeNext(r.FormValue("next"))
	switch r.Method {
	case http.MethodGet:
		if a.Authenticated(r) {
			http.Redirect(w, r, next, http.StatusFound)
			return
		}
		a.renderLogin(w, next, "", http.StatusOK)
	case http.MethodPost:
		if !sameOriginWrite(r) {
			http.Error(w, "cross-origin login rejected", http.StatusForbidden)
			return
		}
		ip := clientIP(r)
		if wait := a.limiter.blockedFor(ip, a.now()); wait > 0 {
			a.renderLogin(w, next, "Too many attempts. Try again in a minute.", http.StatusTooManyRequests)
			return
		}
		got := r.PostFormValue("secret")
		if subtle.ConstantTimeCompare([]byte(strings.TrimSpace(got)), a.secret) != 1 {
			a.limiter.fail(ip, a.now())
			time.Sleep(300 * time.Millisecond)
			a.renderLogin(w, next, "Wrong secret.", http.StatusUnauthorized)
			return
		}
		a.limiter.reset(ip)
		a.setCookie(w, r, a.mint(), int(a.ttl.Seconds()))
		http.Redirect(w, r, next, http.StatusSeeOther)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (a *Auth) HandleLogout(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !sameOriginWrite(r) {
		http.Error(w, "cross-origin request rejected", http.StatusForbidden)
		return
	}
	a.setCookie(w, r, "", -1)
	http.Redirect(w, r, "/login", http.StatusSeeOther)
}

func (a *Auth) renderLogin(w http.ResponseWriter, next, msg string, status int) {
	setPageSecurityHeaders(w)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = a.loginTpl.Execute(w, map[string]string{"Next": next, "Error": msg})
}

// Require wraps next so every request needs a valid session. API calls get
// a 401 JSON error; page loads are redirected to /login.
func (a *Auth) Require(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !a.Authenticated(r) {
			if strings.HasPrefix(r.URL.Path, "/api/") || strings.HasPrefix(r.URL.Path, "/mcp") || r.Method != http.MethodGet {
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusUnauthorized)
				_, _ = w.Write([]byte(`{"error":"login required"}`))
				return
			}
			http.Redirect(w, r, "/login?next="+url.QueryEscape(r.URL.RequestURI()), http.StatusFound)
			return
		}
		if !isSafeMethod(r.Method) && !sameOriginWrite(r) {
			http.Error(w, "cross-origin request rejected", http.StatusForbidden)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func isSafeMethod(m string) bool {
	return m == http.MethodGet || m == http.MethodHead || m == http.MethodOptions
}

// sameOriginWrite is the CSRF check for state-changing requests: browsers
// send Origin (and Sec-Fetch-Site) on POSTs, and both must say same-origin.
func sameOriginWrite(r *http.Request) bool {
	// Sec-Fetch-Site is set by the browser and can't be forged by page JS.
	// (Origin may be "null" on form posts from pages with a strict
	// Referrer-Policy, so prefer this when present.)
	if s := r.Header.Get("Sec-Fetch-Site"); s != "" {
		return s == "same-origin" || s == "none"
	}
	origin := r.Header.Get("Origin")
	if origin == "" {
		// Non-browser clients (curl, tests) omit both headers; the cookie is
		// SameSite=Lax so cross-site browser POSTs don't carry it anyway.
		return true
	}
	u, err := url.Parse(origin)
	if err != nil || origin == "null" {
		return false
	}
	return strings.EqualFold(u.Host, forwardedHost(r))
}

func forwardedHost(r *http.Request) string {
	if h := r.Header.Get("X-Forwarded-Host"); h != "" {
		return strings.TrimSpace(strings.Split(h, ",")[0])
	}
	return r.Host
}

func safeNext(n string) string {
	if n == "" || !strings.HasPrefix(n, "/") || strings.HasPrefix(n, "//") || strings.HasPrefix(n, "/\\") || strings.HasPrefix(n, "/login") {
		return "/tesla/"
	}
	return n
}

// clientIP identifies the login client for rate limiting. Proxy headers are
// only trusted when the TCP peer is loopback (cloudflared / a local reverse
// proxy); a LAN client talking to the port directly can't spoof them to
// dodge the per-IP limit.
func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if ip := net.ParseIP(host); ip != nil && ip.IsLoopback() {
		if v := strings.TrimSpace(r.Header.Get("CF-Connecting-IP")); v != "" {
			return v
		}
		if v := r.Header.Get("X-Forwarded-For"); v != "" {
			return strings.TrimSpace(strings.Split(v, ",")[0])
		}
	}
	return host
}

// loginLimiter allows 5 failures per client IP per 5 minutes, plus a global
// cap across all clients so a botnet rotating IPs through the public tunnel
// still can't make meaningful brute-force progress.
type loginLimiter struct {
	mu     sync.Mutex
	hits   map[string][]time.Time
	global []time.Time
}

func newLoginLimiter() *loginLimiter { return &loginLimiter{hits: map[string][]time.Time{}} }

const (
	loginWindow         = 5 * time.Minute
	loginMaxFails       = 5
	loginGlobalMaxFails = 30
)

func pruneTimes(ts []time.Time, now time.Time) []time.Time {
	kept := ts[:0]
	for _, t := range ts {
		if now.Sub(t) < loginWindow {
			kept = append(kept, t)
		}
	}
	return kept
}

func (l *loginLimiter) prune(ip string, now time.Time) []time.Time {
	kept := pruneTimes(l.hits[ip], now)
	if len(kept) == 0 {
		delete(l.hits, ip)
		return nil
	}
	l.hits[ip] = kept
	return kept
}

func (l *loginLimiter) blockedFor(ip string, now time.Time) time.Duration {
	l.mu.Lock()
	defer l.mu.Unlock()
	var wait time.Duration
	l.global = pruneTimes(l.global, now)
	if len(l.global) >= loginGlobalMaxFails {
		wait = loginWindow - now.Sub(l.global[len(l.global)-loginGlobalMaxFails])
	}
	if h := l.prune(ip, now); len(h) >= loginMaxFails {
		if w := loginWindow - now.Sub(h[len(h)-loginMaxFails]); w > wait {
			wait = w
		}
	}
	return wait
}

func (l *loginLimiter) fail(ip string, now time.Time) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if len(l.hits) > 10000 {
		l.hits = map[string][]time.Time{}
	}
	l.hits[ip] = append(l.prune(ip, now), now)
	l.global = append(pruneTimes(l.global, now), now)
}

func (l *loginLimiter) reset(ip string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.hits, ip)
}

const loginHTML = `<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, interactive-widget=resizes-content">
<meta name="theme-color" content="#0d0f12">
<title>Tesla Messages · Sign in</title>
<link rel="manifest" href="/tesla/manifest.webmanifest">
<link rel="icon" href="/favicon.ico" sizes="16x16 32x32 48x48">
<link rel="icon" href="/tesla/icons/icon.svg" type="image/svg+xml">
<link rel="apple-touch-icon" href="/tesla/icons/apple-touch-icon.png">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-title" content="Messages">
<link rel="stylesheet" href="/tesla/app.css">
<script src="/tesla/login.js" defer></script>
<script src="/tesla/pwa.js" defer></script>
</head><body class="login-body">
<main class="login-card">
  <div class="login-head"><span class="login-logo" aria-hidden="true"><svg viewBox="0 0 24 24" focusable="false"><path d="M20 2H4a2 2 0 0 0-2 2v18l4-4h14a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2z"/></svg></span><h1>Tesla Messages</h1></div>
  <form method="post" action="/login" autocomplete="off">
    <input type="hidden" name="next" value="{{.Next}}">
    <label for="secret">Access secret</label>
    <div class="login-row">
      <input id="secret" name="secret" type="password" autofocus required autocomplete="current-password" enterkeyhint="go">
      <button type="submit" class="btn btn-primary">Sign in</button>
    </div>
    {{if .Error}}<p class="login-error" role="alert">{{.Error}}</p>{{end}}
  </form>
</main>
</body></html>`
