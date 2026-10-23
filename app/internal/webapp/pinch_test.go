package webapp

import (
	"os/exec"
	"strings"
	"testing"
)

// The photo viewer loads pinch.js before app.js and keeps a big close button.
func TestPinchScriptWired(t *testing.T) {
	b, err := staticFS.ReadFile("static/index.html")
	if err != nil {
		t.Fatal(err)
	}
	s := string(b)
	p, a := strings.Index(s, `<script src="/app/pinch.js"></script>`), strings.Index(s, `<script src="/app/app.js"></script>`)
	if p < 0 || a < 0 || p > a {
		t.Fatalf("pinch.js must load before app.js (%d, %d)", p, a)
	}
	if !strings.Contains(s, `id="imageViewerClose"`) {
		t.Fatal("photo viewer close button missing")
	}
}

// Runs the pinch.js zoom math under node (skipped when node isn't installed).
func TestPinchScriptLogic(t *testing.T) {
	node, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not installed")
	}
	src, err := staticFS.ReadFile("static/pinch.js")
	if err != nil {
		t.Fatal(err)
	}
	harness := `
const src = require('fs').readFileSync(0, 'utf8');
const m = { exports: {} }; new Function('module', src)(m); const P = m.exports;
const assert = require('assert');
// pan clamps to the overflow; none at 1x
assert.deepStrictEqual(P.clampPan(1, 50, -50, 400, 300), { x: 0, y: 0 });
assert.deepStrictEqual(P.clampPan(2, 999, -999, 400, 300), { x: 200, y: -150 });
// zooming at a point keeps that point fixed (within bounds)
let z = P.zoomAt({ s: 1, x: 0, y: 0 }, 2, 100, 50, 400, 300);
assert.strictEqual(z.s, 2); assert.strictEqual(z.x, -100); assert.strictEqual(z.y, -50);
// scale is clamped
assert.strictEqual(P.zoomAt({ s: 1, x: 0, y: 0 }, 50, 0, 0, 400, 300).s, P.MAX);
assert.strictEqual(P.zoomAt({ s: 2, x: 0, y: 0 }, 0.2, 0, 0, 400, 300).s, P.MIN);
// double tap toggles in and back out
const d = P.doubleTap({ s: 1, x: 0, y: 0 }, 0, 0, 400, 300); assert.ok(d.s > 2);
assert.deepStrictEqual(P.doubleTap(d, 10, 10, 400, 300), { s: 1, x: 0, y: 0 });
console.log('ok');
`
	cmd := exec.Command(node, "-e", harness)
	cmd.Stdin = strings.NewReader(string(src))
	out, err := cmd.CombinedOutput()
	if err != nil || !strings.Contains(string(out), "ok") {
		t.Fatalf("node harness failed: %v\n%s", err, out)
	}
}
