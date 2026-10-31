package web

import (
	"net/url"
	"testing"
)

func TestExtractLinkPreviewIcon(t *testing.T) {
	base, _ := url.Parse("https://example.test/post/1")
	html := `<html><head><title>T</title><link rel="icon" href="/fav.png"><link rel="apple-touch-icon" href="/touch.png"><meta property="og:title" content="Hello"></head></html>`
	p, _ := extractLinkPreview([]byte(html), base)
	if p == nil || p.IconURL != "https://example.test/touch.png" {
		t.Fatalf("icon = %+v", p)
	}
	p, _ = extractLinkPreview([]byte(`<html><head><meta property="og:title" content="Hi"></head></html>`), base)
	if p == nil || p.IconURL != "https://example.test/favicon.ico" {
		t.Fatalf("fallback icon = %+v", p)
	}
}
