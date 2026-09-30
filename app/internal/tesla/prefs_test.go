package tesla

import (
	"os/exec"
	"strings"
	"testing"
)

// The display prefs script must run in <head> (before the body paints) and
// the Car Mode checkbox must default to on.
func TestPrefsScriptRunsBeforeFirstPaint(t *testing.T) {
	html, err := staticFS.ReadFile("static/index.html")
	if err != nil {
		t.Fatal(err)
	}
	s := string(html)
	head, body := strings.Index(s, `<script src="/tesla/zoom.js"></script>`), strings.Index(s, "<body")
	if head < 0 || body < 0 || head > body || strings.Contains(s[head:body], "defer") {
		t.Fatalf("zoom.js must be a blocking <head> script (at %d, body at %d)", head, body)
	}
	if !strings.Contains(s, `<input id="setCarMode" class="setting-check" type="checkbox" checked>`) {
		t.Fatal("Car Mode checkbox missing or not checked by default")
	}
}

// Runs zoom.js under node with a stub DOM (skipped when node isn't installed).
func TestPrefsScriptLogic(t *testing.T) {
	node, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not installed")
	}
	src, err := staticFS.ReadFile("static/zoom.js")
	if err != nil {
		t.Fatal(err)
	}
	harness := `
const src = require('fs').readFileSync(0, 'utf8');
function run(stored) {
  const cls = new Set(), style = { zoom: '', props: {}, setProperty(k, v) { this.props[k] = v; } };
  global.document = { documentElement: { style, classList: { toggle(c, on) { on ? cls.add(c) : cls.delete(c); } } } };
  global.localStorage = { getItem: () => stored };
  global.window = {};
  eval(src);
  return { cls: [...cls].sort().join(','), zoom: style.zoom, z: style.props['--z'], P: window.TMPrefs };
}
const out = [];
const check = (name, got, want) => { if (String(got) !== String(want)) out.push(name + ': got ' + got + ' want ' + want); };
let r = run(null);                                   check('default class', r.cls, 'car'); check('default zoom', r.zoom, ''); check('default --z', r.z, '1');
r = run('not json');                                 check('corrupt class', r.cls, 'car');
r = run(JSON.stringify({ zoom: 150 }));              check('car 150 zoom', r.zoom, '1.5');
r = run(JSON.stringify({ carMode: false }));         check('desk class', r.cls, 'desk'); check('desk zoom', r.zoom, String(r.P.DESK_SCALE));
r = run(JSON.stringify({ carMode: false, zoom: 150 })); check('desk 150 --z', r.z, String(Math.round(1.5 * r.P.DESK_SCALE * 1000) / 1000));
r = run(JSON.stringify({ carMode: 'no', zoom: 33 })); check('invalid values', r.cls + ' ' + r.zoom, 'car ');
check('carMode(undefined)', r.P.carMode({}), true); check('carMode(false)', r.P.carMode({ carMode: false }), false);
if (out.length) { console.log(out.join('\n')); process.exit(1); }
`
	cmd := exec.Command(node, "-e", harness)
	cmd.Stdin = strings.NewReader(string(src))
	if b, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("zoom.js checks failed: %v\n%s", err, b)
	}
}
