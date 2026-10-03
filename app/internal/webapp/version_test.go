package webapp

import (
	"io/fs"
	"strings"
	"testing"
)

func TestVersionedIndex(t *testing.T) {
	sub, _ := fs.Sub(staticFS, "static")
	v := staticVersion(sub)
	if len(v) != 12 || v != staticVersion(sub) {
		t.Fatalf("version %q", v)
	}
	s := string(versionedIndex(sub, v))
	for _, want := range []string{`src="/app/app.js?v=` + v + `"`, `href="/app/app.css?v=` + v + `"`, `<meta name="app-version" content="` + v + `">`} {
		if !strings.Contains(s, want) {
			t.Fatalf("missing %s", want)
		}
	}
}
