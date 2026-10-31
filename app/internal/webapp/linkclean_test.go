package webapp

import (
	"os/exec"
	"strings"
	"testing"
)

func TestLinkCleanWired(t *testing.T) {
	b, _ := staticFS.ReadFile("static/index.html")
	s := string(b)
	l, a := strings.Index(s, `<script src="/app/linkclean.js"></script>`), strings.Index(s, `<script src="/app/app.js"></script>`)
	if l < 0 || a < 0 || l > a {
		t.Fatalf("linkclean.js must load before app.js (%d, %d)", l, a)
	}
	for _, id := range []string{`id="setCleanRule"`, `id="setCleanRuleReset"`} {
		if !strings.Contains(s, id) {
			t.Fatalf("missing %s", id)
		}
	}
}

// Synthetic URLs only.
func TestLinkCleanLogic(t *testing.T) {
	node, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not installed")
	}
	src, err := staticFS.ReadFile("static/linkclean.js")
	if err != nil {
		t.Fatal(err)
	}
	harness := `
const src = require('fs').readFileSync(0, 'utf8');
const m = { exports: {} }; new Function('module', src)(m); const L = m.exports;
const assert = require('assert');
const c = (u, r) => L.cleanURL(u, r);
assert.strictEqual(c('https://shop.example.com/p/123?utm_source=x&utm_medium=y&id=9'), 'https://shop.example.com/p/123?id=9');
assert.strictEqual(c('https://example.com/a?ref=abc&ref_=nav&pf_rd_p=1&pf_rd_r=2&psc=1'), 'https://example.com/a');
assert.strictEqual(c('https://video.example.org/watch?v=abc123&t=42&si=zzz'), 'https://video.example.org/watch?v=abc123');
assert.strictEqual(c('https://example.net/x?share_id=1&ShareTm=2&_ga=3&spm=a.b&q=cats#top'), 'https://example.net/x?q=cats#top');
assert.strictEqual(c('https://example.com/x?Q=1&id=2'), 'https://example.com/x?Q=1&id=2');
assert.strictEqual(c('https://example.com/plain'), 'https://example.com/plain');
assert.strictEqual(c('https://example.com/x?UTM_Campaign=1&keep=a%20b'), 'https://example.com/x?keep=a%20b');
// custom rule
assert.strictEqual(c('https://example.com/x?foo=1&bar=2', 'foo'), 'https://example.com/x?bar=2');
// invalid rule leaves URL alone
assert.strictEqual(L.validRule('utm|('), false);
assert.strictEqual(L.validRule(L.DEFAULT_RULE), true);
assert.strictEqual(c('https://example.com/x?utm=1', '('), 'https://example.com/x?utm=1');
// text with several links and punctuation
const r = L.cleanText('See https://example.com/a?utm_source=x&id=1, and https://example.org/b?s=2. Also http://example.net/c.');
assert.strictEqual(r.text, 'See https://example.com/a?id=1, and https://example.org/b. Also http://example.net/c.');
assert.strictEqual(r.count, 2);
console.log('ok');
`
	cmd := exec.Command(node, "-e", harness)
	cmd.Stdin = strings.NewReader(string(src))
	out, err := cmd.CombinedOutput()
	if err != nil || !strings.Contains(string(out), "ok") {
		t.Fatalf("node harness failed: %v\n%s", err, out)
	}
}
