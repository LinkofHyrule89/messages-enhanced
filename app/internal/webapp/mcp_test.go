package webapp

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const testMCPToken = "0123456789abcdef0123456789abcdef-test"

type mcpRecorder struct {
	hits       int
	auth, cook string
}

func (m *mcpRecorder) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	m.hits++
	m.auth, m.cook = r.Header.Get("Authorization"), r.Header.Get("Cookie")
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write([]byte(`{"jsonrpc":"2.0","id":1,"result":{}}`))
}

func newMCPTestServer(t *testing.T, token string) (http.Handler, *Server, *mcpRecorder, string) {
	rec := &mcpRecorder{}
	dir := ""
	h, s, _ := newTestServer(t, func(c *Config, d *Deps) {
		c.MCPToken = token
		d.MCP = rec
		dir = d.DataDir
	})
	return h, s, rec, dir
}

func mcpPost(h http.Handler, bearer string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodPost, "https://msg.example/mcp", strings.NewReader(`{"jsonrpc":"2.0","id":1,"method":"initialize"}`))
	req.Header.Set("Content-Type", "application/json")
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	return rr
}

func TestMCPDisabledWithoutToken(t *testing.T) {
	h, _, rec, _ := newMCPTestServer(t, "short")
	if rr := mcpPost(h, "short"); rr.Code != http.StatusUnauthorized || rr.Header().Get("WWW-Authenticate") != "" {
		t.Fatalf("status = %d, want the plain login gate when token < 32 chars", rr.Code)
	}
	for _, p := range []string{"/.well-known/oauth-protected-resource/mcp", "/.well-known/oauth-authorization-server"} {
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "https://msg.example"+p, nil))
		if rr.Code != http.StatusNotFound {
			t.Fatalf("%s = %d, want 404", p, rr.Code)
		}
	}
	if rec.hits != 0 {
		t.Fatal("MCP handler reached")
	}
}

func TestMCPBearerGate(t *testing.T) {
	h, _, rec, _ := newMCPTestServer(t, testMCPToken)
	rr := mcpPost(h, "")
	if rr.Code != http.StatusUnauthorized {
		t.Fatalf("no token: %d", rr.Code)
	}
	if wa := rr.Header().Get("WWW-Authenticate"); !strings.Contains(wa, `resource_metadata="https://msg.example/.well-known/oauth-protected-resource/mcp"`) {
		t.Fatalf("WWW-Authenticate = %q", wa)
	}
	if rr := mcpPost(h, testMCPToken+"x"); rr.Code != http.StatusUnauthorized {
		t.Fatalf("wrong token: %d", rr.Code)
	}
	// the login cookie alone is not enough
	req := httptest.NewRequest(http.MethodPost, "https://msg.example/mcp", strings.NewReader(`{}`))
	req.AddCookie(login(t, h))
	rr = httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	if rr.Code != http.StatusUnauthorized {
		t.Fatalf("cookie only: %d", rr.Code)
	}
	if rec.hits != 0 {
		t.Fatal("reached MCP without a token")
	}
	if rr := mcpPost(h, testMCPToken); rr.Code != http.StatusOK || rec.hits != 1 {
		t.Fatalf("good token: %d hits=%d", rr.Code, rec.hits)
	}
	if rec.auth != "" || rec.cook != "" {
		t.Fatal("credentials forwarded to the MCP handler")
	}
}

func TestMCPFailureThrottle(t *testing.T) {
	h, _, _, _ := newMCPTestServer(t, testMCPToken)
	for i := 0; i < mcpFailMax; i++ {
		mcpPost(h, "wrong-token")
	}
	if rr := mcpPost(h, testMCPToken); rr.Code != http.StatusTooManyRequests {
		t.Fatalf("after %d failures: %d, want 429", mcpFailMax, rr.Code)
	}
}

func TestOAuthFullFlow(t *testing.T) {
	h, _, rec, dir := newMCPTestServer(t, testMCPToken)
	get := func(p string) map[string]any {
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "https://msg.example"+p, nil))
		if rr.Code != 200 {
			t.Fatalf("%s: %d", p, rr.Code)
		}
		var m map[string]any
		_ = json.Unmarshal(rr.Body.Bytes(), &m)
		return m
	}
	prm := get("/.well-known/oauth-protected-resource/mcp")
	if prm["resource"] != "https://msg.example/mcp" {
		t.Fatalf("resource = %v", prm["resource"])
	}
	asm := get("/.well-known/oauth-authorization-server")
	if asm["registration_endpoint"] != "https://msg.example/oauth/register" || asm["authorization_endpoint"] != "https://msg.example/oauth/authorize" {
		t.Fatalf("metadata = %v", asm)
	}

	// dynamic client registration
	const cb = "https://grok.com/connectors-oauth-exchange-code/"
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost, "https://msg.example/oauth/register",
		strings.NewReader(`{"client_name":"Grok","redirect_uris":["`+cb+`"],"token_endpoint_auth_method":"none"}`)))
	if rr.Code != http.StatusCreated {
		t.Fatalf("register: %d %s", rr.Code, rr.Body.String())
	}
	var reg map[string]any
	_ = json.Unmarshal(rr.Body.Bytes(), &reg)
	clientID, _ := reg["client_id"].(string)
	if clientID == "" {
		t.Fatal("no client_id")
	}
	// http (non-loopback) redirects are refused
	rr = httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost, "https://msg.example/oauth/register",
		strings.NewReader(`{"redirect_uris":["http://evil.example/cb"]}`)))
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("http redirect accepted: %d", rr.Code)
	}

	verifier := strings.Repeat("v", 50)
	q := url.Values{"response_type": {"code"}, "client_id": {clientID}, "redirect_uri": {cb}, "state": {"st8"},
		"code_challenge": {pkceS256(verifier)}, "code_challenge_method": {"S256"}, "scope": {"messages"}}
	authURL := "https://msg.example/oauth/authorize?" + q.Encode()

	// not logged in: sent to the login page, which comes back here
	rr = httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, authURL, nil))
	if rr.Code != http.StatusFound || !strings.HasPrefix(rr.Header().Get("Location"), "/login?next=%2Foauth%2Fauthorize%3F") {
		t.Fatalf("unauthenticated authorize: %d %q", rr.Code, rr.Header().Get("Location"))
	}
	cookie := login(t, h)
	req := httptest.NewRequest(http.MethodGet, authURL, nil)
	req.AddCookie(cookie)
	rr = httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	body := rr.Body.String()
	if rr.Code != 200 || !strings.Contains(body, "Connect Grok?") || !strings.Contains(body, "grok.com") {
		t.Fatalf("consent page: %d %s", rr.Code, body)
	}
	i := strings.Index(body, `name="tx" value="`)
	tx := body[i+len(`name="tx" value="`):]
	tx = tx[:strings.Index(tx, `"`)]

	// cross-site POST is rejected
	post := func(decision, origin string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodPost, "https://msg.example/oauth/authorize", strings.NewReader(url.Values{"tx": {tx}, "decision": {decision}}.Encode()))
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		req.Header.Set("Origin", origin)
		req.AddCookie(cookie)
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, req)
		return rr
	}
	if rr := post("allow", "https://evil.example"); rr.Code != http.StatusForbidden {
		t.Fatalf("cross-origin approve: %d", rr.Code)
	}
	rr = post("allow", "https://msg.example")
	loc, _ := url.Parse(rr.Header().Get("Location"))
	if rr.Code != http.StatusFound || !strings.HasPrefix(loc.String(), cb) || loc.Query().Get("state") != "st8" || loc.Query().Get("code") == "" {
		t.Fatalf("approve: %d %q", rr.Code, loc)
	}
	code := loc.Query().Get("code")

	token := func(form url.Values) (*httptest.ResponseRecorder, map[string]any) {
		req := httptest.NewRequest(http.MethodPost, "https://msg.example/oauth/token", strings.NewReader(form.Encode()))
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, req)
		var m map[string]any
		_ = json.Unmarshal(rr.Body.Bytes(), &m)
		return rr, m
	}
	// wrong verifier fails and burns the code
	if rr, _ := token(url.Values{"grant_type": {"authorization_code"}, "code": {code}, "client_id": {clientID}, "redirect_uri": {cb}, "code_verifier": {strings.Repeat("w", 50)}}); rr.Code != 400 {
		t.Fatalf("bad verifier: %d", rr.Code)
	}
	if rr, _ := token(url.Values{"grant_type": {"authorization_code"}, "code": {code}, "client_id": {clientID}, "redirect_uri": {cb}, "code_verifier": {verifier}}); rr.Code != 400 {
		t.Fatalf("code reuse: %d", rr.Code)
	}

	// approve again for a fresh code
	req = httptest.NewRequest(http.MethodGet, authURL, nil)
	req.AddCookie(cookie)
	rr = httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	body = rr.Body.String()
	i = strings.Index(body, `name="tx" value="`)
	tx = body[i+len(`name="tx" value="`):]
	tx = tx[:strings.Index(tx, `"`)]
	rr = post("allow", "https://msg.example")
	loc, _ = url.Parse(rr.Header().Get("Location"))
	rr, tok := token(url.Values{"grant_type": {"authorization_code"}, "code": {loc.Query().Get("code")}, "client_id": {clientID}, "redirect_uri": {cb}, "code_verifier": {verifier}})
	if rr.Code != 200 {
		t.Fatalf("token: %d %s", rr.Code, rr.Body.String())
	}
	access, _ := tok["access_token"].(string)
	refresh, _ := tok["refresh_token"].(string)
	if rr := mcpPost(h, access); rr.Code != 200 || rec.hits != 1 {
		t.Fatalf("mcp with access token: %d", rr.Code)
	}

	// tokens survive a restart (only hashes on disk)
	raw, err := os.ReadFile(filepath.Join(dir, "oauth.json"))
	if err != nil || strings.Contains(string(raw), access) || strings.Contains(string(raw), refresh) {
		t.Fatalf("oauth.json: err=%v contains plaintext token", err)
	}
	if fi, _ := os.Stat(filepath.Join(dir, "oauth.json")); fi.Mode().Perm() != 0o600 {
		t.Fatalf("oauth.json mode %v", fi.Mode().Perm())
	}
	st := newOAuthStore(dir)
	if !st.validAccess(access) {
		t.Fatal("access token not persisted")
	}

	// refresh rotates
	rr, tok2 := token(url.Values{"grant_type": {"refresh_token"}, "refresh_token": {refresh}, "client_id": {clientID}})
	if rr.Code != 200 || tok2["access_token"] == access {
		t.Fatalf("refresh: %d", rr.Code)
	}
	if rr, _ := token(url.Values{"grant_type": {"refresh_token"}, "refresh_token": {refresh}, "client_id": {clientID}}); rr.Code != 400 {
		t.Fatalf("old refresh token reusable: %d", rr.Code)
	}
	if rr := mcpPost(h, tok2["access_token"].(string)); rr.Code != 200 {
		t.Fatalf("refreshed access token: %d", rr.Code)
	}
}

func TestOAuthDeny(t *testing.T) {
	h, _, _, _ := newMCPTestServer(t, testMCPToken)
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost, "https://msg.example/oauth/register",
		strings.NewReader(`{"redirect_uris":["https://client.example/cb"]}`)))
	var reg map[string]any
	_ = json.Unmarshal(rr.Body.Bytes(), &reg)
	q := url.Values{"response_type": {"code"}, "client_id": {reg["client_id"].(string)}, "redirect_uri": {"https://client.example/cb"},
		"code_challenge": {pkceS256(strings.Repeat("a", 43))}, "code_challenge_method": {"S256"}}
	cookie := login(t, h)
	req := httptest.NewRequest(http.MethodGet, "https://msg.example/oauth/authorize?"+q.Encode(), nil)
	req.AddCookie(cookie)
	rr = httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	body := rr.Body.String()
	i := strings.Index(body, `name="tx" value="`)
	tx := body[i+len(`name="tx" value="`):]
	tx = tx[:strings.Index(tx, `"`)]
	req = httptest.NewRequest(http.MethodPost, "https://msg.example/oauth/authorize", strings.NewReader(url.Values{"tx": {tx}, "decision": {"deny"}}.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(cookie)
	rr = httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	loc, _ := url.Parse(rr.Header().Get("Location"))
	if loc.Query().Get("error") != "access_denied" || loc.Query().Get("code") != "" {
		t.Fatalf("deny redirect = %q", loc)
	}
	// unregistered redirect_uri is refused without redirecting
	q.Set("redirect_uri", "https://evil.example/cb")
	req = httptest.NewRequest(http.MethodGet, "https://msg.example/oauth/authorize?"+q.Encode(), nil)
	req.AddCookie(cookie)
	rr = httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("bad redirect_uri: %d", rr.Code)
	}
}
