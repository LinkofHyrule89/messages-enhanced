package webapp

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

func TestSettingsRoundTripAndLimits(t *testing.T) {
	h, s, _ := newTestServer(t, nil)
	c := login(t, h)

	rr := authedReq(t, h, c, http.MethodGet, "/api/app/settings", "", nil)
	if rr.Code != 200 || !strings.Contains(rr.Body.String(), `"settings":{}`) && !strings.Contains(rr.Body.String(), `"settings": {}`) {
		t.Fatalf("empty get: %d %s", rr.Code, rr.Body.String())
	}

	body := `{"theme":"blue","appearance":"dark","zoom":150}`
	rr = authedReq(t, h, c, http.MethodPut, "/api/app/settings", "application/json", strings.NewReader(body))
	if rr.Code != 200 {
		t.Fatalf("put: %d %s", rr.Code, rr.Body.String())
	}
	var got struct {
		Settings map[string]any `json:"settings"`
	}
	if err := json.Unmarshal(rr.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if got.Settings["theme"] != "blue" || got.Settings["appearance"] != "dark" || got.Settings["zoom"] != float64(150) {
		t.Fatalf("put body: %#v", got.Settings)
	}

	rr = authedReq(t, h, c, http.MethodGet, "/api/app/settings", "", nil)
	if rr.Code != 200 || !strings.Contains(rr.Body.String(), `"theme":"blue"`) {
		t.Fatalf("get after put: %d %s", rr.Code, rr.Body.String())
	}

	// Replace, don't merge: zoom goes away when the next object omits it.
	rr = authedReq(t, h, c, http.MethodPut, "/api/app/settings", "application/json", strings.NewReader(`{"theme":""}`))
	if rr.Code != 200 || strings.Contains(rr.Body.String(), "zoom") {
		t.Fatalf("replace: %d %s", rr.Code, rr.Body.String())
	}

	for _, bad := range []string{`[]`, `null`, `"x"`, `{`, `{"a":1} junk`} {
		rr = authedReq(t, h, c, http.MethodPut, "/api/app/settings", "application/json", strings.NewReader(bad))
		if rr.Code != 400 {
			t.Fatalf("reject %q: %d %s", bad, rr.Code, rr.Body.String())
		}
	}

	huge := `{"pad":"` + strings.Repeat("a", maxSettingsBytes) + `"}`
	rr = authedReq(t, h, c, http.MethodPut, "/api/app/settings", "application/json", strings.NewReader(huge))
	if rr.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversize: %d %s", rr.Code, rr.Body.String())
	}

	rr = authedReq(t, h, c, http.MethodPost, "/api/app/settings", "application/json", strings.NewReader(`{}`))
	if rr.Code != http.StatusMethodNotAllowed {
		t.Fatalf("post: %d", rr.Code)
	}

	// Previous good object is still stored after the rejected writes.
	rr = authedReq(t, h, c, http.MethodGet, "/api/app/settings", "", nil)
	if !strings.Contains(rr.Body.String(), `"theme":""`) {
		t.Fatalf("rejected writes changed the store: %s", rr.Body.String())
	}

	s.settings = nil
	rr = authedReq(t, h, c, http.MethodGet, "/api/app/settings", "", nil)
	if rr.Code != http.StatusServiceUnavailable {
		t.Fatalf("nil store: %d", rr.Code)
	}
}

func TestSettingsWritesRejectCrossOrigin(t *testing.T) {
	h, _, _ := newTestServer(t, nil)
	c := login(t, h)
	req, _ := http.NewRequest(http.MethodPut, "http://car.example/api/app/settings", strings.NewReader(`{"theme":"sage"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Origin", "https://evil.example")
	req.AddCookie(c)
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	if rr.Code != http.StatusForbidden {
		t.Fatalf("cross-origin settings write: %d %s", rr.Code, rr.Body.String())
	}
}

func TestSettingsStoreCorruptFile(t *testing.T) {
	dir := t.TempDir()
	path := dir + "/" + settingsFile
	if err := os.WriteFile(path, []byte(`[1,2,3]`), 0o600); err != nil {
		t.Fatal(err)
	}
	s, err := OpenSettingsStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	if string(s.Get()) != `{}` {
		t.Fatalf("corrupt file should start empty, got %s", s.Get())
	}
	if err := s.Put([]byte(`{"appearance":"dark"}`)); err != nil {
		t.Fatal(err)
	}
	s2, err := OpenSettingsStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	var obj map[string]string
	if err := json.Unmarshal(s2.Get(), &obj); err != nil || obj["appearance"] != "dark" {
		t.Fatalf("reload: %s %v", s2.Get(), err)
	}
}
