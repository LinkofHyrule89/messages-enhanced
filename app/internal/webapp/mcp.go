package webapp

// /mcp: the remote MCP connector (Streamable HTTP; Deps.MCP), outside the
// cookie login but behind its own bearer auth: the long static token
// MESSAGES_MCP_TOKEN (at least 32 characters; unset = /mcp is off) or an
// OAuth access token from oauth.go. Clients send
//   Authorization: Bearer <token>
// Wrong or missing tokens get 401; repeated failures from one address are
// throttled (429).

import (
	"crypto/sha256"
	"crypto/subtle"
	"net/http"
	"strings"
	"sync"
	"time"
)

const (
	mcpMinTokenLen = 32
	mcpMaxBody     = 1 << 20
	mcpFailWindow  = 10 * time.Minute
	mcpFailMax     = 20
)

type mcpFailures struct {
	sync.Mutex
	byIP map[string][]time.Time
}

func (f *mcpFailures) blocked(ip string, now time.Time) bool {
	f.Lock()
	defer f.Unlock()
	ts := f.prune(ip, now)
	return len(ts) >= mcpFailMax
}

func (f *mcpFailures) add(ip string, now time.Time) {
	f.Lock()
	defer f.Unlock()
	if f.byIP == nil {
		f.byIP = map[string][]time.Time{}
	}
	f.byIP[ip] = append(f.prune(ip, now), now)
	if len(f.byIP) > 10000 { // bound memory
		f.byIP = map[string][]time.Time{}
	}
}

func (f *mcpFailures) prune(ip string, now time.Time) []time.Time {
	ts := f.byIP[ip]
	i := 0
	for i < len(ts) && now.Sub(ts[i]) > mcpFailWindow {
		i++
	}
	ts = ts[i:]
	if f.byIP != nil {
		if len(ts) == 0 {
			delete(f.byIP, ip)
		} else {
			f.byIP[ip] = ts
		}
	}
	return ts
}

func (s *Server) handleMCP(w http.ResponseWriter, r *http.Request) {
	if !s.mcpEnabled() { // connector off: /mcp is just another login-protected path
		s.gated.ServeHTTP(w, r)
		return
	}
	if mcpCORS(w, r) {
		return
	}
	token := s.cfg.MCPToken
	ip, now := clientIP(r), time.Now()
	if s.mcpFails.blocked(ip, now) {
		w.Header().Set("Retry-After", "600")
		http.Error(w, "too many failed attempts", http.StatusTooManyRequests)
		return
	}
	got := ""
	if h := r.Header.Get("Authorization"); len(h) > 7 && strings.EqualFold(h[:7], "bearer ") {
		got = strings.TrimSpace(h[7:])
	}
	a, b := sha256.Sum256([]byte(got)), sha256.Sum256([]byte(token))
	if got == "" || (subtle.ConstantTimeCompare(a[:], b[:]) != 1 && !s.oauth.validAccess(got)) {
		if got != "" {
			s.mcpFails.add(ip, now)
		}
		// Points OAuth clients (grok.com connectors) at the discovery document.
		w.Header().Set("WWW-Authenticate", `Bearer realm="messages-enhanced-mcp", resource_metadata="`+s.publicBase(r)+`/.well-known/oauth-protected-resource/mcp"`)
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	r.Header.Del("Authorization")
	r.Header.Del("Cookie")
	r.Body = http.MaxBytesReader(w, r.Body, mcpMaxBody)
	w.Header().Set("Cache-Control", "no-store")
	s.deps.MCP.ServeHTTP(w, r)
}
