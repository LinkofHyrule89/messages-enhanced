package tesla

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// The bundled emoji font is public (like any web font), cached for a year,
// licensed (OFL.txt), and limited to the font directory's own files.
func TestEmojiFontServedPubliclyWithLongCache(t *testing.T) {
	h, _, _ := newTestServer(t, nil)
	get := func(p string) *httptest.ResponseRecorder {
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "http://car.example"+p, nil))
		return rr
	}
	rr := get("/tesla/fonts/noto-emoji-v0-0.woff2")
	if rr.Code != 200 || rr.Header().Get("Content-Type") != "font/woff2" || !strings.Contains(rr.Header().Get("Cache-Control"), "max-age=31536000") || rr.Body.Len() < 1000 {
		t.Fatalf("woff2: %d %q %q %d", rr.Code, rr.Header().Get("Content-Type"), rr.Header().Get("Cache-Control"), rr.Body.Len())
	}
	if rr := get("/tesla/fonts/noto-emoji.css"); rr.Code != 200 || !strings.Contains(rr.Body.String(), `font-family: "Noto Color Emoji"`) || !strings.Contains(rr.Body.String(), "tech(color-COLRv1)") {
		t.Fatalf("css: %d", rr.Code)
	}
	if rr := get("/tesla/fonts/OFL.txt"); rr.Code != 200 || !strings.Contains(rr.Body.String(), "SIL Open Font License") {
		t.Fatalf("license: %d", rr.Code)
	}
	for _, p := range []string{"/tesla/fonts/../app.js", "/tesla/fonts/", "/tesla/fonts/x/y.woff2"} {
		if rr := get(p); rr.Code == 200 {
			t.Errorf("%s served without login", p)
		}
	}
	// the rest of /tesla/ still needs a login
	if rr := get("/tesla/app.js"); rr.Code == 200 {
		t.Errorf("app.js served without login")
	}
}
