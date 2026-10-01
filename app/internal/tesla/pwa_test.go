package tesla

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestPWAFilesArePublic(t *testing.T) {
	h, _, inner := newTestServer(t, nil)
	cases := map[string]string{
		"/tesla/manifest.webmanifest":        "application/manifest+json",
		"/tesla/sw.js":                       "javascript",
		"/tesla/offline.html":                "text/html",
		"/tesla/pwa.js":                      "javascript",
		"/tesla/icons/icon-192.png":          "image/png",
		"/tesla/icons/icon-512.png":          "image/png",
		"/tesla/icons/icon-maskable-512.png": "image/png",
		"/tesla/icons/apple-touch-icon.png":  "image/png",
		"/tesla/icons/badge-96.png":          "image/png",
		"/tesla/icons/icon.svg":              "image/svg+xml",
		"/favicon.ico":                       "",
		"/apple-touch-icon.png":              "image/png",
		"/apple-touch-icon-precomposed.png":  "image/png",
	}
	for p, ct := range cases {
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "http://car.example"+p, nil))
		if rr.Code != 200 {
			t.Fatalf("%s: status %d (must be public)", p, rr.Code)
		}
		if ct != "" && !strings.Contains(rr.Header().Get("Content-Type"), ct) {
			t.Fatalf("%s: content-type %q, want %q", p, rr.Header().Get("Content-Type"), ct)
		}
		if rr.Header().Get("X-Content-Type-Options") != "nosniff" {
			t.Fatalf("%s: missing nosniff", p)
		}
	}
	if inner.hits.Load() != 0 {
		t.Fatal("PWA files must not reach the inner handler")
	}
	// The rest of /tesla/ stays behind the login.
	for _, p := range []string{"/tesla/", "/tesla/app.js", "/tesla/index.html", "/tesla/icons/../app.js"} {
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "http://car.example"+p, nil))
		if rr.Code == 200 {
			t.Fatalf("%s must require login, got 200", p)
		}
	}
	for _, p := range []string{"/tesla/icons/", "/tesla/icons/missing.png", "/tesla/icons/x.js"} {
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "http://car.example"+p, nil))
		if rr.Code != 404 {
			t.Fatalf("%s: want 404, got %d", p, rr.Code)
		}
	}
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost, "http://car.example/tesla/sw.js", nil))
	if rr.Code != http.StatusMethodNotAllowed {
		t.Fatalf("POST sw.js: %d", rr.Code)
	}
}

func TestManifestContents(t *testing.T) {
	h, _, _ := newTestServer(t, nil)
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "http://car.example/tesla/manifest.webmanifest", nil))
	var m struct {
		Name, ShortName, StartURL, Display, Scope, ID string
		Theme, Background                             string
		Icons                                         []struct{ Src, Sizes, Type, Purpose string }
	}
	var raw map[string]any
	if err := json.Unmarshal(rr.Body.Bytes(), &raw); err != nil {
		t.Fatalf("manifest JSON: %v", err)
	}
	b, _ := json.Marshal(raw)
	_ = json.Unmarshal(b, &m)
	m.ShortName, _ = raw["short_name"].(string)
	m.StartURL, _ = raw["start_url"].(string)
	m.Theme, _ = raw["theme_color"].(string)
	m.Background, _ = raw["background_color"].(string)
	if m.Name != "Tesla Messages" || m.ShortName != "Messages" || m.StartURL != "/tesla/" || m.Display != "standalone" {
		t.Fatalf("manifest basics: %+v", m)
	}
	if m.Theme == "" || m.Background == "" {
		t.Fatal("theme/background colors missing")
	}
	var has192, has512, maskable bool
	for _, ic := range m.Icons {
		if ic.Sizes == "192x192" && ic.Purpose == "any" {
			has192 = true
		}
		if ic.Sizes == "512x512" && ic.Purpose == "any" {
			has512 = true
		}
		if ic.Purpose == "maskable" {
			maskable = true
		}
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "http://car.example"+ic.Src, nil))
		if rr.Code != 200 {
			t.Fatalf("manifest icon %s: %d", ic.Src, rr.Code)
		}
	}
	if !has192 || !has512 || !maskable {
		t.Fatalf("icons: 192=%v 512=%v maskable=%v", has192, has512, maskable)
	}
}

func TestServiceWorkerNeverCachesAuthenticatedResponses(t *testing.T) {
	b, err := staticFS.ReadFile("static/sw.js")
	if err != nil {
		t.Fatal(err)
	}
	sw := string(b)
	for _, want := range []string{`"/api/"`, `"/login"`, `"/logout"`, `"/admin"`, `res.status === 200`, `!res.redirected`, `res.type === "basic"`, `/tesla/offline.html`, `addEventListener("push"`, `addEventListener("notificationclick"`, `renotify`} {
		if !strings.Contains(sw, want) {
			t.Fatalf("sw.js missing %q", want)
		}
	}
	// Navigations (the per-user HTML) are never put in the cache.
	nav := sw[strings.Index(sw, `req.mode === "navigate"`):]
	nav = nav[:strings.Index(nav, "return;")]
	if strings.Contains(nav, "put(") || strings.Contains(nav, "cache.put") {
		t.Fatal("navigation responses must not be cached")
	}
}

func TestPagesLinkIconAndManifest(t *testing.T) {
	h, _, _ := newTestServer(t, nil)
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "http://car.example/login", nil))
	idx, _ := staticFS.ReadFile("static/index.html")
	for name, page := range map[string]string{"login": rr.Body.String(), "car": string(idx)} {
		for _, want := range []string{`rel="manifest" href="/tesla/manifest.webmanifest"`, `href="/favicon.ico"`, `rel="apple-touch-icon"`, `name="theme-color"`, `/tesla/pwa.js`} {
			if !strings.Contains(page, want) {
				t.Fatalf("%s page missing %s", name, want)
			}
		}
	}
	// Chat theme moved into the header ⋮ menu; no palette button in the header.
	s := string(idx)
	if strings.Contains(s, `id="themeBtn"`) || strings.Contains(s, "theme-hint") {
		t.Fatal("standalone chat theme button should be gone")
	}
	if !strings.Contains(s, `id="convMenuTheme"`) {
		t.Fatal("Chat theme item missing from the conversation menu")
	}
}
