package webapp

import (
	"os/exec"
	"strings"
	"testing"
)

func TestDraftsWired(t *testing.T) {
	b, _ := staticFS.ReadFile("static/index.html")
	s := string(b)
	d, a := strings.Index(s, `<script src="/app/drafts.js"></script>`), strings.Index(s, `<script src="/app/app.js"></script>`)
	if d < 0 || a < 0 || d > a {
		t.Fatalf("drafts.js must load before app.js (%d, %d)", d, a)
	}
	js, _ := staticFS.ReadFile("static/app.js")
	for _, want := range []string{"swapDraft(state.current, id)", "swapDraft(state.current, null)", "MEDrafts.clear(state.current)"} {
		if !strings.Contains(string(js), want) {
			t.Fatalf("app.js missing %q", want)
		}
	}
}

// Synthetic data only; uses an in-memory Storage stand-in.
func TestDraftsLogic(t *testing.T) {
	node, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not installed")
	}
	src, err := staticFS.ReadFile("static/drafts.js")
	if err != nil {
		t.Fatal(err)
	}
	harness := `
const src = require('fs').readFileSync(0, 'utf8');
const m = { exports: {} }; new Function('module', src)(m); const D = m.exports;
const assert = require('assert');
const mem = () => { const o = new Map(); return { getItem: k => o.has(k) ? o.get(k) : null, setItem: (k, v) => o.set(k, String(v)), removeItem: k => o.delete(k), size: () => o.size }; };
const s = mem();
assert.strictEqual(D.get('convA', s), '');
D.put('convA', 'hello \u00a0@Test Person\u00a0 there', s);
D.put('convB', 'other chat', s);
assert.strictEqual(D.get('convA', s), 'hello \u00a0@Test Person\u00a0 there');
assert.strictEqual(D.get('convB', s), 'other chat');
assert.strictEqual(D.get('convC', s), '');
D.put('convA', '   ', s);
assert.strictEqual(D.get('convA', s), '');
D.clear('convB', s);
assert.strictEqual(s.size(), 0);
assert.strictEqual(D.put('', 'x', s), false);
assert.strictEqual(D.normalize('a'.repeat(D.MAX + 5)).length, D.MAX);
assert.strictEqual(D.listPreview('hi\nthere'), 'Draft: hi there');
console.log('ok');
`
	cmd := exec.Command(node, "-e", harness)
	cmd.Stdin = strings.NewReader(string(src))
	out, err := cmd.CombinedOutput()
	if err != nil || !strings.Contains(string(out), "ok") {
		t.Fatalf("drafts logic: %v\n%s", err, out)
	}
}
