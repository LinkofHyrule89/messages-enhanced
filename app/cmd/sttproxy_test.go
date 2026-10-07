package cmd

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/rs/zerolog"
)

func TestSTTProxyForwardsOnlyTranscriptions(t *testing.T) {
	var gotPath, gotAuth, gotHost string
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath, gotAuth, gotHost = r.URL.Path, r.Header.Get("Authorization"), r.Host
		b, _ := io.ReadAll(r.Body)
		_, _ = w.Write([]byte(`{"text":"` + string(b) + `"}`))
	}))
	defer up.Close()
	h, err := NewSTTProxyHandler(up.URL, zerolog.Nop())
	if err != nil {
		t.Fatal(err)
	}
	px := httptest.NewServer(h)
	defer px.Close()
	req, _ := http.NewRequest(http.MethodPost, px.URL+"/openai/v1/audio/transcriptions", strings.NewReader("hi"))
	req.Header.Set("Authorization", "Bearer k")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 || string(b) != `{"text":"hi"}` || gotPath != "/openai/v1/audio/transcriptions" || gotAuth != "Bearer k" || gotHost != strings.TrimPrefix(up.URL, "http://") {
		t.Fatalf("forward: %d %s path=%s auth=%s host=%s", resp.StatusCode, b, gotPath, gotAuth, gotHost)
	}
	// @Groq replies go through too.
	req, _ = http.NewRequest(http.MethodPost, px.URL+"/openai/v1/chat/completions", strings.NewReader("q"))
	if resp, err := http.DefaultClient.Do(req); err != nil || resp.StatusCode != 200 || gotPath != "/openai/v1/chat/completions" {
		t.Fatalf("chat completions: %v %v %s", err, resp, gotPath)
	} else {
		resp.Body.Close()
	}
	for _, c := range []struct{ method, path string }{{"GET", "/openai/v1/audio/transcriptions"}, {"POST", "/openai/v1/embeddings"}, {"GET", "/openai/v1/models"}, {"POST", "/"}} {
		req, _ := http.NewRequest(c.method, px.URL+c.path, nil)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != 404 {
			t.Fatalf("%s %s: %d", c.method, c.path, resp.StatusCode)
		}
	}
}
