package webapp

// Minimal OAuth 2.1 authorization server for the remote MCP connector, so
// clients that only do OAuth (grok.com "Custom" connectors) can connect:
//
//   - discovery: /.well-known/oauth-protected-resource[/mcp] (RFC 9728) and
//     /.well-known/oauth-authorization-server (RFC 8414)
//   - dynamic client registration: POST /oauth/register (RFC 7591)
//   - authorization code + PKCE (S256 only): GET/POST /oauth/authorize, an
//     approval page behind the web app's own login
//   - tokens: POST /oauth/token (authorization_code, refresh_token; refresh
//     tokens rotate)
//
// Access tokens are opaque random strings; only their SHA-256 is stored
// (oauth.json in the data dir, mode 600). The static MESSAGES_MCP_TOKEN
// keeps working alongside OAuth. Deleting oauth.json signs every OAuth
// client out.

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"html/template"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	oauthCodeTTL     = 10 * time.Minute
	oauthAccessTTL   = 7 * 24 * time.Hour
	oauthRefreshTTL  = 180 * 24 * time.Hour
	oauthMaxClients  = 50
	oauthScope       = "messages"
	oauthMaxBodySize = 64 << 10
)

type oauthClient struct {
	ID           string   `json:"id"`
	Name         string   `json:"name"`
	RedirectURIs []string `json:"redirect_uris"`
	SecretHash   string   `json:"secret_hash,omitempty"`
	CreatedMS    int64    `json:"created_ms"`
}

type oauthToken struct {
	AccessHash   string `json:"access_hash"`
	RefreshHash  string `json:"refresh_hash"`
	ClientID     string `json:"client_id"`
	Scope        string `json:"scope"`
	AccessExpMS  int64  `json:"access_exp_ms"`
	RefreshExpMS int64  `json:"refresh_exp_ms"`
}

type oauthCode struct {
	clientID, redirectURI, challenge, scope string
	exp                                     time.Time
}

type oauthPending struct {
	clientID, redirectURI, challenge, scope, state string
	exp                                            time.Time
}

type oauthStore struct {
	mu      sync.Mutex
	path    string
	Clients map[string]*oauthClient `json:"clients"`
	Tokens  []*oauthToken           `json:"tokens"`
	codes   map[string]*oauthCode
	pending map[string]*oauthPending
}

func newOAuthStore(dataDir string) *oauthStore {
	st := &oauthStore{Clients: map[string]*oauthClient{}, codes: map[string]*oauthCode{}, pending: map[string]*oauthPending{}}
	if dataDir == "" {
		return st
	}
	st.path = filepath.Join(dataDir, "oauth.json")
	if b, err := os.ReadFile(st.path); err == nil {
		_ = json.Unmarshal(b, st)
		if st.Clients == nil {
			st.Clients = map[string]*oauthClient{}
		}
	}
	return st
}

// save writes the store; call with mu held.
func (st *oauthStore) save() {
	if st.path == "" {
		return
	}
	now := time.Now().UnixMilli()
	live := st.Tokens[:0]
	for _, t := range st.Tokens {
		if t.RefreshExpMS > now {
			live = append(live, t)
		}
	}
	st.Tokens = live
	b, err := json.MarshalIndent(st, "", "  ")
	if err != nil {
		return
	}
	tmp := st.path + ".tmp"
	if os.WriteFile(tmp, b, 0o600) == nil {
		_ = os.Rename(tmp, st.path)
	}
}

func randToken(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

func hashToken(t string) string {
	s := sha256.Sum256([]byte(t))
	return hex.EncodeToString(s[:])
}

// validAccess reports whether an OAuth access token is live.
func (st *oauthStore) validAccess(tok string) bool {
	if tok == "" {
		return false
	}
	h, now := hashToken(tok), time.Now().UnixMilli()
	st.mu.Lock()
	defer st.mu.Unlock()
	for _, t := range st.Tokens {
		if subtle.ConstantTimeCompare([]byte(t.AccessHash), []byte(h)) == 1 && t.AccessExpMS > now {
			return true
		}
	}
	return false
}

// publicBase is the server's public origin as the client sees it.
func (s *Server) publicBase(r *http.Request) string {
	if s.cfg.PublicURL != "" {
		return s.cfg.PublicURL
	}
	host := forwardedHost(r)
	scheme := "https"
	if h := strings.ToLower(host); (strings.HasPrefix(h, "localhost") || strings.HasPrefix(h, "127.0.0.1") || strings.HasPrefix(h, "[::1]")) &&
		r.TLS == nil && !strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https") {
		scheme = "http"
	}
	return scheme + "://" + host
}

func (s *Server) mcpEnabled() bool { return s.deps.MCP != nil && len(s.cfg.MCPToken) >= mcpMinTokenLen }

func (s *Server) registerOAuthRoutes(root *http.ServeMux) {
	root.HandleFunc("/.well-known/oauth-protected-resource", s.handleOAuthResourceMeta)
	root.HandleFunc("/.well-known/oauth-protected-resource/mcp", s.handleOAuthResourceMeta)
	root.HandleFunc("/.well-known/oauth-authorization-server", s.handleOAuthServerMeta)
	root.HandleFunc("/.well-known/openid-configuration", s.handleOAuthServerMeta)
	root.HandleFunc("/oauth/register", s.handleOAuthRegister)
	root.HandleFunc("/oauth/token", s.handleOAuthToken)
	root.Handle("/oauth/authorize", s.auth.Require(http.HandlerFunc(s.handleOAuthAuthorize)))
}

// cors lets browser-based MCP clients reach the public, cookie-less
// endpoints (/mcp, discovery, register, token). Returns true for preflight.
func mcpCORS(w http.ResponseWriter, r *http.Request) bool {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type, Mcp-Session-Id, Mcp-Protocol-Version, Last-Event-ID")
	w.Header().Set("Access-Control-Expose-Headers", "WWW-Authenticate, Mcp-Session-Id")
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return true
	}
	return false
}

func (s *Server) handleOAuthResourceMeta(w http.ResponseWriter, r *http.Request) {
	if mcpCORS(w, r) {
		return
	}
	if !s.mcpEnabled() {
		http.NotFound(w, r)
		return
	}
	base := s.publicBase(r)
	writeJSON(w, http.StatusOK, map[string]any{
		"resource":                 base + "/mcp",
		"authorization_servers":    []string{base},
		"scopes_supported":         []string{oauthScope},
		"bearer_methods_supported": []string{"header"},
		"resource_name":            "Messages Enhanced",
	})
}

func (s *Server) handleOAuthServerMeta(w http.ResponseWriter, r *http.Request) {
	if mcpCORS(w, r) {
		return
	}
	if !s.mcpEnabled() {
		http.NotFound(w, r)
		return
	}
	base := s.publicBase(r)
	writeJSON(w, http.StatusOK, map[string]any{
		"issuer":                                base,
		"authorization_endpoint":                base + "/oauth/authorize",
		"token_endpoint":                        base + "/oauth/token",
		"registration_endpoint":                 base + "/oauth/register",
		"response_types_supported":              []string{"code"},
		"grant_types_supported":                 []string{"authorization_code", "refresh_token"},
		"code_challenge_methods_supported":      []string{"S256"},
		"token_endpoint_auth_methods_supported": []string{"none", "client_secret_post", "client_secret_basic"},
		"scopes_supported":                      []string{oauthScope},
	})
}

func oauthError(w http.ResponseWriter, status int, code, desc string) {
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, status, map[string]string{"error": code, "error_description": desc})
}

// validRedirect: https anywhere, or http on loopback (local MCP clients).
func validRedirect(raw string) bool {
	u, err := url.Parse(raw)
	if err != nil || u.Fragment != "" || u.Host == "" {
		return false
	}
	if u.Scheme == "https" {
		return true
	}
	h := u.Hostname()
	return u.Scheme == "http" && (h == "localhost" || h == "127.0.0.1" || h == "::1")
}

func (s *Server) handleOAuthRegister(w http.ResponseWriter, r *http.Request) {
	if mcpCORS(w, r) {
		return
	}
	if !s.mcpEnabled() {
		http.NotFound(w, r)
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	ip, now := clientIP(r), time.Now()
	if s.mcpFails.blocked(ip, now) {
		oauthError(w, http.StatusTooManyRequests, "slow_down", "too many requests")
		return
	}
	var req struct {
		RedirectURIs            []string `json:"redirect_uris"`
		ClientName              string   `json:"client_name"`
		TokenEndpointAuthMethod string   `json:"token_endpoint_auth_method"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, oauthMaxBodySize)).Decode(&req); err != nil {
		oauthError(w, http.StatusBadRequest, "invalid_client_metadata", "body must be JSON")
		return
	}
	if len(req.RedirectURIs) == 0 || len(req.RedirectURIs) > 10 {
		oauthError(w, http.StatusBadRequest, "invalid_redirect_uri", "redirect_uris required")
		return
	}
	for _, u := range req.RedirectURIs {
		if !validRedirect(u) {
			oauthError(w, http.StatusBadRequest, "invalid_redirect_uri", "redirect URIs must be https (or http on localhost)")
			return
		}
	}
	name := strings.TrimSpace(req.ClientName)
	if len(name) > 80 {
		name = name[:80]
	}
	c := &oauthClient{ID: "mcp_" + randToken(18), Name: name, RedirectURIs: req.RedirectURIs, CreatedMS: now.UnixMilli()}
	resp := map[string]any{
		"client_id":                  c.ID,
		"client_id_issued_at":        now.Unix(),
		"redirect_uris":              c.RedirectURIs,
		"grant_types":                []string{"authorization_code", "refresh_token"},
		"response_types":             []string{"code"},
		"token_endpoint_auth_method": "none",
	}
	if name != "" {
		resp["client_name"] = name
	}
	if m := req.TokenEndpointAuthMethod; m == "client_secret_post" || m == "client_secret_basic" {
		secret := randToken(32)
		c.SecretHash = hashToken(secret)
		resp["client_secret"] = secret
		resp["client_secret_expires_at"] = 0
		resp["token_endpoint_auth_method"] = m
	}
	st := s.oauth
	st.mu.Lock()
	if len(st.Clients) >= oauthMaxClients { // drop the oldest clients without live tokens
		used := map[string]bool{}
		for _, t := range st.Tokens {
			used[t.ClientID] = true
		}
		var old []*oauthClient
		for _, x := range st.Clients {
			if !used[x.ID] {
				old = append(old, x)
			}
		}
		sort.Slice(old, func(i, j int) bool { return old[i].CreatedMS < old[j].CreatedMS })
		for i := 0; i < len(old) && len(st.Clients) >= oauthMaxClients; i++ {
			delete(st.Clients, old[i].ID)
		}
	}
	if len(st.Clients) >= oauthMaxClients {
		st.mu.Unlock()
		oauthError(w, http.StatusBadRequest, "invalid_client_metadata", "too many registered clients")
		return
	}
	st.Clients[c.ID] = c
	st.save()
	st.mu.Unlock()
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusCreated, resp)
}

var oauthConsentTmpl = template.Must(template.New("consent").Parse(`<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="same-origin"><title>Connect to Messages Enhanced</title>
<style>
body{margin:0;min-height:100vh;display:grid;place-items:center;background:#0d0f12;color:#f2f4f7;font:18px/1.45 system-ui,sans-serif}
.card{max-width:460px;margin:24px;padding:32px;border-radius:24px;background:#15181d;box-shadow:0 8px 40px rgba(0,0,0,.5)}
h1{margin:0 0 12px;font-size:26px;font-weight:600}.host{font-weight:600;color:#8ab4f8;word-break:break-all}
ul{padding-left:20px;color:#c9cdd3}.btns{display:flex;gap:12px;margin-top:24px}
button{flex:1;min-height:56px;border:0;border-radius:28px;font:inherit;font-weight:600;cursor:pointer}
.allow{background:#8ab4f8;color:#0d1b2e}.deny{background:#2a2f37;color:#f2f4f7}
.note{margin-top:18px;font-size:14px;color:#8b949e}
</style></head><body><form class="card" method="post" action="/oauth/authorize">
<h1>Connect {{if .Name}}{{.Name}}{{else}}an assistant{{end}}?</h1>
<p>It will be signed in to your Messages Enhanced server and sent back to <span class="host">{{.Host}}</span>.</p>
<ul><li>Read your conversations, contacts and messages</li><li>Send messages and reactions as you</li></ul>
<input type="hidden" name="tx" value="{{.TX}}">
<div class="btns"><button class="deny" name="decision" value="deny">Deny</button><button class="allow" name="decision" value="allow">Allow</button></div>
<p class="note">Only allow this if you just started connecting an assistant yourself.</p>
</form></body></html>`))

func (s *Server) handleOAuthAuthorize(w http.ResponseWriter, r *http.Request) {
	if !s.mcpEnabled() {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Frame-Options", "DENY")
	w.Header().Set("Content-Security-Policy", "frame-ancestors 'none'")
	st := s.oauth
	switch r.Method {
	case http.MethodGet:
		q := r.URL.Query()
		st.mu.Lock()
		c := st.Clients[q.Get("client_id")]
		st.mu.Unlock()
		redirect := q.Get("redirect_uri")
		if c == nil {
			http.Error(w, "unknown client_id", http.StatusBadRequest)
			return
		}
		if redirect == "" && len(c.RedirectURIs) == 1 {
			redirect = c.RedirectURIs[0]
		}
		ok := false
		for _, u := range c.RedirectURIs {
			if u == redirect {
				ok = true
			}
		}
		if !ok {
			http.Error(w, "redirect_uri doesn't match the registered client", http.StatusBadRequest)
			return
		}
		fail := func(code, desc string) {
			u, _ := url.Parse(redirect)
			v := u.Query()
			v.Set("error", code)
			v.Set("error_description", desc)
			if st := q.Get("state"); st != "" {
				v.Set("state", st)
			}
			u.RawQuery = v.Encode()
			http.Redirect(w, r, u.String(), http.StatusFound)
		}
		if q.Get("response_type") != "code" {
			fail("unsupported_response_type", "only response_type=code")
			return
		}
		if q.Get("code_challenge") == "" || q.Get("code_challenge_method") != "S256" {
			fail("invalid_request", "PKCE with code_challenge_method=S256 is required")
			return
		}
		tx := randToken(18)
		st.mu.Lock()
		now := time.Now()
		for k, p := range st.pending {
			if now.After(p.exp) {
				delete(st.pending, k)
			}
		}
		st.pending[tx] = &oauthPending{clientID: c.ID, redirectURI: redirect, challenge: q.Get("code_challenge"),
			scope: oauthScope, state: q.Get("state"), exp: now.Add(oauthCodeTTL)}
		st.mu.Unlock()
		host := redirect
		if u, err := url.Parse(redirect); err == nil {
			host = u.Host
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_ = oauthConsentTmpl.Execute(w, map[string]string{"Name": c.Name, "Host": host, "TX": tx})
	case http.MethodPost:
		_ = r.ParseForm()
		st.mu.Lock()
		p := st.pending[r.PostForm.Get("tx")]
		delete(st.pending, r.PostForm.Get("tx"))
		st.mu.Unlock()
		if p == nil || time.Now().After(p.exp) {
			http.Error(w, "this approval expired; start connecting again", http.StatusBadRequest)
			return
		}
		u, _ := url.Parse(p.redirectURI)
		v := u.Query()
		if r.PostForm.Get("decision") == "allow" {
			code := randToken(32)
			st.mu.Lock()
			for k, c := range st.codes {
				if time.Now().After(c.exp) {
					delete(st.codes, k)
				}
			}
			st.codes[hashToken(code)] = &oauthCode{clientID: p.clientID, redirectURI: p.redirectURI, challenge: p.challenge, scope: p.scope, exp: time.Now().Add(oauthCodeTTL)}
			st.mu.Unlock()
			v.Set("code", code)
		} else {
			v.Set("error", "access_denied")
		}
		if p.state != "" {
			v.Set("state", p.state)
		}
		u.RawQuery = v.Encode()
		http.Redirect(w, r, u.String(), http.StatusFound)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func pkceS256(verifier string) string {
	s := sha256.Sum256([]byte(verifier))
	return base64.RawURLEncoding.EncodeToString(s[:])
}

func (s *Server) handleOAuthToken(w http.ResponseWriter, r *http.Request) {
	if mcpCORS(w, r) {
		return
	}
	if !s.mcpEnabled() {
		http.NotFound(w, r)
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	ip, now := clientIP(r), time.Now()
	if s.mcpFails.blocked(ip, now) {
		oauthError(w, http.StatusTooManyRequests, "slow_down", "too many failed attempts")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, oauthMaxBodySize)
	if err := r.ParseForm(); err != nil {
		oauthError(w, http.StatusBadRequest, "invalid_request", "form body required")
		return
	}
	f := r.PostForm
	clientID, secret := f.Get("client_id"), f.Get("client_secret")
	if id, sec, ok := r.BasicAuth(); ok {
		clientID, secret = id, sec
		if v, err := url.QueryUnescape(id); err == nil {
			clientID = v
		}
		if v, err := url.QueryUnescape(sec); err == nil {
			secret = v
		}
	}
	st := s.oauth
	st.mu.Lock()
	defer st.mu.Unlock()
	bad := func(code, desc string) {
		s.mcpFails.add(ip, now)
		status := http.StatusBadRequest
		if code == "invalid_client" {
			status = http.StatusUnauthorized
		}
		oauthError(w, status, code, desc)
	}
	c := st.Clients[clientID]
	if c == nil {
		bad("invalid_client", "unknown client")
		return
	}
	if c.SecretHash != "" && subtle.ConstantTimeCompare([]byte(hashToken(secret)), []byte(c.SecretHash)) != 1 {
		bad("invalid_client", "client authentication failed")
		return
	}
	issue := func(scope string) {
		access, refresh := randToken(32), randToken(32)
		st.Tokens = append(st.Tokens, &oauthToken{AccessHash: hashToken(access), RefreshHash: hashToken(refresh), ClientID: c.ID, Scope: scope,
			AccessExpMS: now.Add(oauthAccessTTL).UnixMilli(), RefreshExpMS: now.Add(oauthRefreshTTL).UnixMilli()})
		st.save()
		w.Header().Set("Cache-Control", "no-store")
		writeJSON(w, http.StatusOK, map[string]any{"access_token": access, "token_type": "Bearer", "expires_in": int(oauthAccessTTL.Seconds()),
			"refresh_token": refresh, "scope": scope})
	}
	switch f.Get("grant_type") {
	case "authorization_code":
		h := hashToken(f.Get("code"))
		code := st.codes[h]
		delete(st.codes, h) // single use
		if code == nil || now.After(code.exp) || code.clientID != c.ID {
			bad("invalid_grant", "code is invalid or expired")
			return
		}
		if ru := f.Get("redirect_uri"); ru != "" && ru != code.redirectURI {
			bad("invalid_grant", "redirect_uri mismatch")
			return
		}
		v := f.Get("code_verifier")
		if len(v) < 43 || len(v) > 128 || subtle.ConstantTimeCompare([]byte(pkceS256(v)), []byte(code.challenge)) != 1 {
			bad("invalid_grant", "PKCE verification failed")
			return
		}
		issue(code.scope)
	case "refresh_token":
		h := hashToken(f.Get("refresh_token"))
		for i, t := range st.Tokens {
			if subtle.ConstantTimeCompare([]byte(t.RefreshHash), []byte(h)) == 1 {
				if t.ClientID != c.ID || t.RefreshExpMS <= now.UnixMilli() {
					break
				}
				st.Tokens = append(st.Tokens[:i], st.Tokens[i+1:]...) // rotate
				issue(t.Scope)
				return
			}
		}
		bad("invalid_grant", "refresh token is invalid or expired")
	default:
		oauthError(w, http.StatusBadRequest, "unsupported_grant_type", "use authorization_code or refresh_token")
	}
}
