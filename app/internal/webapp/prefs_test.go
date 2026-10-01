package webapp

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
	head, body := strings.Index(s, `<script src="/app/zoom.js"></script>`), strings.Index(s, "<body")
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
check('steps', r.P.ZOOM_STEPS.join(','), '50,60,67,75,90,100,110,125,150,175');
check('auto default', r.P.isAuto(null), false); check('default is default', r.P.isDefault(null), true); check('auto not default', r.P.isDefault({ zoom: 'auto' }), false); check('auto manual', r.P.isAuto({ zoom: 100 }), false); check('auto desk', r.P.isAuto({ carMode: false }), false);
check('auto "auto"', r.P.isAuto({ zoom: 'auto' }), true);
check('auto full car', r.P.autoZoom(1920, 1080), 100); check('auto 2/3 car', r.P.autoZoom(1200, 900), 66);
check('auto 1280x960', r.P.autoZoom(1280, 960), 71); check('auto 1100x800', r.P.autoZoom(1100, 800), 61);
check('auto clamp', r.P.autoZoom(1000, 400), 50); check('auto phone', r.P.autoZoom(400, 800), 100);
let q;
function runWin(stored, w, h) {
  const cls = new Set(), style = { zoom: '', props: {}, setProperty(k, v) { this.props[k] = v; } };
  global.document = { documentElement: { style, classList: { toggle(c, on) { on ? cls.add(c) : cls.delete(c); } } } };
  global.localStorage = { getItem: () => stored };
  global.window = { innerWidth: w, innerHeight: h };
  eval(src);
  return { cls: [...cls].sort().join(','), zoom: style.zoom };
}
q = runWin(null, 1920, 1200);                        check('car default 50%', q.cls + ' ' + q.zoom, 'car 0.5');
q = runWin(null, 1200, 900);                         check('2/3 default 50%', q.cls + ' ' + q.zoom, 'car 0.5');
q = runWin(JSON.stringify({ zoom: 'auto' }), 1200, 900); check('2/3 auto', q.cls + ' ' + q.zoom, 'car 0.66');
q = runWin(JSON.stringify({ zoom: 'auto' }), 1920, 1200); check('full auto', q.cls + ' ' + q.zoom, 'car ');
q = runWin(JSON.stringify({ zoom: 'auto' }), 1100, 800); check('1100 auto two panes', q.cls, 'car');
q = runWin(JSON.stringify({ carMode: false }), 1920, 1200); check('desk default 100%', q.zoom, String(r.P.DESK_SCALE));
q = runWin(JSON.stringify({ zoom: 100 }), 1100, 800); check('1100 manual 100 narrow', q.cls + ' ' + q.zoom, 'car,narrow ');
q = runWin(null, 400, 800);                          check('phone', q.cls + ' ' + q.zoom, 'car,narrow ');
q = runWin(JSON.stringify({ carMode: false }), 1000, 800); check('desk narrow', q.cls, 'desk,narrow');
check('carMode(undefined)', r.P.carMode({}), true); check('carMode(false)', r.P.carMode({ carMode: false }), false);
if (out.length) { console.log(out.join('\n')); process.exit(1); }
`
	cmd := exec.Command(node, "-e", harness)
	cmd.Stdin = strings.NewReader(string(src))
	if b, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("zoom.js checks failed: %v\n%s", err, b)
	}
}

// The "Re-check microphone access" switch is on by default and wired up.
func TestMicRecheckSetting(t *testing.T) {
	html, err := staticFS.ReadFile("static/index.html")
	if err != nil {
		t.Fatal(err)
	}
	js, err := staticFS.ReadFile("static/app.js")
	if err != nil {
		t.Fatal(err)
	}
	h, j := string(html), string(js)
	if !strings.Contains(h, `<button id="setMicRecheck" class="setting-row mic-recheck-row on" type="button" role="switch" aria-checked="true">`) {
		t.Fatal("Re-check microphone access switch missing or not on by default")
	}
	for _, id := range []string{"micRecheckDesc", "micCheckBtn", "micCheckResult"} {
		if !strings.Contains(h, `id="`+id+`"`) || !strings.Contains(j, `$("`+id+`")`) {
			t.Fatalf("%s not in both index.html and app.js", id)
		}
	}
	if !strings.Contains(j, "micRecheck !== false") {
		t.Fatal("micRecheck must default to on when unset")
	}
}
