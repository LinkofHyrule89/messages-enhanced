package webapp

import (
	"net/http/httptest"
	"strings"
	"testing"
)

// The message box must be a contenteditable (Chrome on Android only hands
// Gboard images/stickers/GIFs to contenteditable), with composer.js (the
// textarea-API shim) loaded before app.js, and the page CSP must let it
// read blob:/data: images into files.
func TestComposerIsContentEditable(t *testing.T) {
	html, err := staticFS.ReadFile("static/index.html")
	if err != nil {
		t.Fatal(err)
	}
	s := string(html)
	if strings.Contains(s, `<textarea id="input"`) || !strings.Contains(s, `id="input" class="compose-input empty" contenteditable="true" role="textbox"`) {
		t.Fatal("#input must be a contenteditable textbox")
	}
	if !strings.Contains(s, `id="attachTray"`) {
		t.Fatal("attachment tray missing")
	}
	c, a := strings.Index(s, `<script src="/app/composer.js"></script>`), strings.Index(s, `<script src="/app/app.js"></script>`)
	if c < 0 || a < 0 || c > a {
		t.Fatalf("composer.js must load before app.js (%d, %d)", c, a)
	}
	if _, err := staticFS.ReadFile("static/composer.js"); err != nil {
		t.Fatal(err)
	}
	w := httptest.NewRecorder()
	setPageSecurityHeaders(w)
	if csp := w.Header().Get("Content-Security-Policy"); !strings.Contains(csp, "connect-src 'self' blob: data:;") {
		t.Fatalf("CSP connect-src: %s", csp)
	}
}
