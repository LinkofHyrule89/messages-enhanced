package webapp

import (
	"compress/gzip"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestWithGzip(t *testing.T) {
	big := strings.Repeat("hello world ", 500)
	h := withGzip(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/js":
			w.Header().Set("Content-Type", "application/javascript")
			_, _ = io.WriteString(w, big)
		case "/img":
			w.Header().Set("Content-Type", "image/png")
			_, _ = io.WriteString(w, big)
		case "/sse":
			w.Header().Set("Content-Type", "text/event-stream")
			_, _ = io.WriteString(w, big)
		}
	}))
	get := func(path string) *httptest.ResponseRecorder {
		r := httptest.NewRequest("GET", path, nil)
		r.Header.Set("Accept-Encoding", "gzip, br")
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, r)
		return rr
	}
	rr := get("/js")
	if rr.Header().Get("Content-Encoding") != "gzip" || rr.Body.Len() >= len(big) {
		t.Fatalf("js not gzipped: %v len=%d", rr.Header(), rr.Body.Len())
	}
	zr, err := gzip.NewReader(rr.Body)
	if err != nil {
		t.Fatal(err)
	}
	if b, _ := io.ReadAll(zr); string(b) != big {
		t.Fatal("gzip body mismatch")
	}
	for _, p := range []string{"/img", "/sse"} {
		if rr := get(p); rr.Header().Get("Content-Encoding") != "" || rr.Body.String() != big {
			t.Fatalf("%s must not be gzipped", p)
		}
	}
}
