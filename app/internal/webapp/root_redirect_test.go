package webapp

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestIsPageNavigation(t *testing.T) {
	cases := []struct {
		method, path, mode, accept string
		want                       bool
	}{
		{"GET", "/foo", "navigate", "", true},
		{"GET", "/some/page", "", "text/html,application/xhtml+xml", true},
		{"GET", "/foo", "cors", "*/*", false},
		{"GET", "/api/conversations", "navigate", "text/html", false},
		{"GET", "/app/whatever", "navigate", "text/html", false},
		{"GET", "/static/x.js", "", "text/html", false},
		{"POST", "/foo", "navigate", "text/html", false},
		{"GET", "/foo", "", "application/json", false},
	}
	for _, c := range cases {
		r := httptest.NewRequest(c.method, c.path, nil)
		if c.mode != "" {
			r.Header.Set("Sec-Fetch-Mode", c.mode)
		}
		if c.accept != "" {
			r.Header.Set("Accept", c.accept)
		}
		if got := isPageNavigation(r); got != c.want {
			t.Errorf("%s %s mode=%q accept=%q: got %v want %v", c.method, c.path, c.mode, c.accept, got, c.want)
		}
	}
	_ = http.MethodGet
}
