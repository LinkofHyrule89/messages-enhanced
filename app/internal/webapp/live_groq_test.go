package webapp

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestGroqPartialThrottleAndWords(t *testing.T) {
	calls := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		b, _ := io.ReadAll(r.Body)
		if !strings.Contains(string(b), "timestamp_granularities[]") || r.Header.Get("Authorization") != "Bearer k" {
			t.Errorf("bad request")
		}
		if calls == 2 {
			w.Header().Set("Retry-After", "7")
			w.WriteHeader(429)
			return
		}
		_, _ = w.Write([]byte(`{"text":" hi there","words":[{"word":"hi","start":0,"end":0.3},{"word":"there","start":0.3,"end":0.7}]}`))
	}))
	defer srv.Close()
	now := time.Unix(1000, 0)
	g := &GroqPartial{Endpoint: srv.URL, APIKey: "k", Model: "m", now: func() time.Time { return now }}
	res, err := g.TranscribePartial(context.Background(), []byte("wav"))
	if err != nil || res.Text != "hi there" || len(res.Words) != 2 {
		t.Fatalf("first: %+v %v", res, err)
	}
	if _, err := g.TranscribePartial(context.Background(), []byte("wav")); err != errPartialThrottled || calls != 1 {
		t.Fatalf("throttle: %v calls=%d", err, calls)
	}
	now = now.Add(GroqPartialInterval)
	if _, err := g.TranscribePartial(context.Background(), []byte("wav")); err != errPartialThrottled || calls != 2 {
		t.Fatalf("429: %v calls=%d", err, calls)
	}
	if d := g.PartialRetryAfter(); d != 7*time.Second {
		t.Fatalf("retry after %v", d)
	}
	now = now.Add(8 * time.Second)
	if _, err := g.TranscribePartial(context.Background(), []byte("wav")); err != nil || calls != 3 {
		t.Fatalf("after backoff: %v calls=%d", err, calls)
	}
}
