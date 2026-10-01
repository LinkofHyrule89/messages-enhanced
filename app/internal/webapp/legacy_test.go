package webapp

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestLegacyEnvAndRedirects(t *testing.T) {
	t.Setenv(legacyEnvPrefix+"STT_MODE", "server")
	if got := Getenv("MESSAGES_STT_MODE"); got != "server" {
		t.Fatalf("fallback = %q", got)
	}
	t.Setenv("MESSAGES_STT_MODE", "builtin")
	if got := Getenv("MESSAGES_STT_MODE"); got != "builtin" {
		t.Fatalf("new name = %q", got)
	}
	mux := http.NewServeMux()
	registerLegacyRoutes(mux)
	for in, want := range map[string]string{legacyURLPrefix + "?c=1": "/app/?c=1", legacyAPIPrefix + "push/test": "/api/app/push/test"} {
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, in, nil))
		if rec.Code != http.StatusPermanentRedirect || rec.Header().Get("Location") != want {
			t.Fatalf("%s -> %d %q", in, rec.Code, rec.Header().Get("Location"))
		}
	}
}
