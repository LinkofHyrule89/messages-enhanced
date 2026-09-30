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

func clientIP(r *http.Request) string {
	if ip := r.Header.Get("CF-Connecting-IP"); ip != "" {
		return ip
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// loginLimiter allows 5 failures per IP per 5 minutes.
type loginLimiter struct {
	mu   sync.Mutex
	hits map[string][]time.Time
}

func newLoginLimiter() *loginLimiter { return &loginLimiter{hits: map[string][]time.Time{}} }

const (
	loginWindow   = 5 * time.Minute
	loginMaxFails = 5
)

func (l *loginLimiter) prune(ip string, now time.Time) []time.Time {
	kept := l.hits[ip][:0]
	for _, t := range l.hits[ip] {
		if now.Sub(t) < loginWindow {
			kept = append(kept, t)
		}
	}
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
	h := l.prune(ip, now)
	if len(h) < loginMaxFails {
		return 0
	}
	return loginWindow - now.Sub(h[0])
}

func (l *loginLimiter) fail(ip string, now time.Time) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if len(l.hits) > 10000 {
		l.hits = map[string][]time.Time{}
	}
	l.hits[ip] = append(l.prune(ip, now), now)
}

func (l *loginLimiter) reset(ip string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.hits, ip)
}

const loginHTML = `<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Tesla Messages · Sign in</title>
<link rel="stylesheet" href="/tesla/app.css">
</head><body class="login-body">
<main class="login-card">
  <div class="login-logo">💬</div>
  <h1>Tesla Messages</h1>
  <form method="post" action="/login" autocomplete="off">
    <input type="hidden" name="next" value="{{.Next}}">
    <label for="secret">Access secret</label>
    <input id="secret" name="secret" type="password" autofocus required autocomplete="current-password">
    {{if .Error}}<p class="login-error" role="alert">{{.Error}}</p>{{end}}
    <button type="submit" class="btn btn-primary btn-block">Sign in</button>
  </form>
</main>
</body></html>`
