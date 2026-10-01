package webapp

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// The login page must work with the car's on-screen keyboard: top-aligned
// form with the keyboard-lift script, which (like app.css) is public, while
// the rest of /app/ stays behind the login.
func TestLoginPageKeyboardFriendly(t *testing.T) {
	h, _, _ := newTestServer(t, nil)
	get := func(path string) *httptest.ResponseRecorder {
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "http://car.example"+path, nil))
		return rr
	}
	page := get("/login")
	body := page.Body.String()
	for _, want := range []string{`<script src="/app/login.js" defer></script>`, `class="login-row"`, `id="secret"`, "autofocus", "interactive-widget=resizes-content"} {
		if !strings.Contains(body, want) {
			t.Errorf("login page missing %q", want)
		}
	}
	js := get("/app/login.js")
	if js.Code != 200 || !strings.Contains(js.Body.String(), "visualViewport") || !strings.Contains(js.Header().Get("Content-Security-Policy"), "script-src 'self'") {
		t.Fatalf("login.js: %d %q", js.Code, js.Header().Get("Content-Type"))
	}
	if rr := get("/app/app.js"); rr.Code == 200 {
		t.Fatal("app.js must still require login")
	}
	css := get("/app/app.css").Body.String()
	if !strings.Contains(css, ".login-body { overflow: auto; display: flex; align-items: flex-start;") || !strings.Contains(css, "body.login-body.kb-open .login-head") {
		t.Fatal("login CSS should top-align the form and fold the title when the keyboard is up")
	}
}
