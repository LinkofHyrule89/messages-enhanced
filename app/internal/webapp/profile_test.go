package webapp

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

type fakeProfile struct {
	img  []byte
	hash string
}

func (f fakeProfile) AccountPhoto() ([]byte, string, string, bool) {
	return f.img, "image/png", f.hash, f.hash != ""
}

func TestProfilePhotoProxied(t *testing.T) {
	h, _, _ := newTestServer(t, func(_ *Config, d *Deps) { d.Profile = fakeProfile{img: []byte("\x89PNG\r\n\x1a\nfake"), hash: "abc123"} })
	// Login required.
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "http://car.example/api/app/profile-photo", nil))
	if rr.Code == http.StatusOK {
		t.Fatal("photo served without login")
	}
	c := login(t, h)
	get := func(path, inm string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodGet, "http://car.example"+path, nil)
		req.AddCookie(c)
		if inm != "" {
			req.Header.Set("If-None-Match", inm)
		}
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, req)
		return rr
	}
	if rr := get("/api/app/profile", ""); rr.Code != 200 || !strings.Contains(rr.Body.String(), `"photo_hash":"abc123"`) {
		t.Fatalf("profile: %d %s", rr.Code, rr.Body.String())
	}
	rr = get("/api/app/profile-photo?h=abc123", "")
	if rr.Code != 200 || rr.Header().Get("Content-Type") != "image/png" || rr.Header().Get("ETag") != `"abc123"` || !strings.HasPrefix(rr.Body.String(), "\x89PNG") {
		t.Fatalf("photo: %d %v", rr.Code, rr.Header())
	}
	if rr := get("/api/app/profile-photo", `"abc123"`); rr.Code != http.StatusNotModified {
		t.Fatalf("etag: %d", rr.Code)
	}
}

func TestProfilePhotoNone(t *testing.T) {
	h, _, _ := newTestServer(t, nil)
	c := login(t, h)
	req := httptest.NewRequest(http.MethodGet, "http://car.example/api/app/profile-photo", nil)
	req.AddCookie(c)
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	if rr.Code != http.StatusNotFound {
		t.Fatalf("got %d", rr.Code)
	}
}
