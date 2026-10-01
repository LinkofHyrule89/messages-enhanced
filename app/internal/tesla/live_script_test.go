package tesla

import (
	"os/exec"
	"strings"
	"testing"
)

// Runs static/live-stt.js (resampler + LocalAgreement-2) under node.
func TestLiveSTTScriptLogic(t *testing.T) {
	node, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not installed")
	}
	src, err := staticFS.ReadFile("static/live-stt.js")
	if err != nil {
		t.Fatal(err)
	}
	harness := `
const src = require('fs').readFileSync(0, 'utf8');
const m = { exports: {} }; new Function('module', src)(m); const L = m.exports;
const assert = require('assert');
// 48 kHz -> 16 kHz: a third of the samples, values preserved, across pushes.
let r = new L.Resampler(48000), n = 0, last;
for (let k = 0; k < 10; k++) { const f = new Float32Array(1000).fill(0.5); const o = r.push(f); n += o.length; last = o[o.length - 1]; }
assert.ok(Math.abs(n - 3333) <= 1, 'resampled length ' + n); assert.strictEqual(last, 16383);
r = new L.Resampler(44100); n = 0; for (let k = 0; k < 441; k++) n += r.push(new Float32Array(100)).length;
assert.ok(Math.abs(n - 16000) <= 2, '44.1k length ' + n);
r = new L.Resampler(16000); assert.strictEqual(r.push(Float32Array.from([1, -1, 0])).join(), '32767,-32768,0');

// LocalAgreement-2 on a growing utterance.
const W = (s, t0) => s.split(' ').map((w, i) => ({ w, s: (t0 || 0) + i * 0.5, e: (t0 || 0) + i * 0.5 + 0.4 }));
let a = new L.Agreement();
a.update(W('Hey I'));                                  assert.strictEqual(a.stableText(), ''); assert.strictEqual(a.text(), 'Hey I');
a.update(W('Hey, I am'));                              assert.strictEqual(a.stableText(), 'Hey, I'); assert.strictEqual(a.tentativeText(), 'am'); // newer spelling wins on commit
a.update(W('Hey I am running'));                       assert.strictEqual(a.stableText(), 'Hey, I am'); assert.strictEqual(a.text(), 'Hey, I am running');
a.update(W('Hey I am running about ten'));             assert.strictEqual(a.stableText(), 'Hey, I am running');
// whisper revises a tentative word ("ten" -> "10"): nothing new commits, tail replaced
a.update(W('Hey I am running about 10 minutes'));      assert.strictEqual(a.stableText(), 'Hey, I am running about'); assert.strictEqual(a.tentativeText(), '10 minutes');
// whisper re-spells a committed word ("Hey" -> "Hay"): committed text is kept, alignment still finds the tail
a.update(W('Hay I am running about 10 minutes late')); assert.strictEqual(a.text(), 'Hey, I am running about 10 minutes late');
// an empty pass (silence) does not erase committed words
a.update([]);                                           assert.strictEqual(a.text(), 'Hey, I am running about 10 minutes late');
// never duplicates committed words
assert.ok(!/running.*running/.test(a.text()));

// trim: a long window locks early committed words, cutting at a pause
a = new L.Agreement();
const long = 'one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen';
const lw = () => { const w = W(long); w[11].w = 'twelve.'; return w; };  // sentence end after word 12 (ends 5.9 s)
a.update(lw(), 20, 0); a.update(lw(), 20, 0);          // all 18 words committed
let cut = a.trim(9.2);
assert.ok(cut > 5.9 && cut <= 6.0, 'cut ' + cut);      // just after "twelve." (ends 5.9, next starts 6.0)
assert.strictEqual(a.locked.length, 12); assert.strictEqual(a.text(), long.replace('twelve', 'twelve.'));
assert.strictEqual(a.committed[0].w, 'thirteen'); assert.ok(a.committed[0].s < 0.1, 'shifted ' + a.committed[0].s);
// next window (audio after the cut) continues without duplicates
a.update(W('thirteen fourteen fifteen sixteen seventeen eighteen nineteen'), 20, 0);
a.update(W('thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty'), 20, 0);
assert.strictEqual(a.text(), long.replace('twelve', 'twelve.') + ' nineteen twenty');
// no pause at all: wait until trimAfter + 2 s, then cut at the last committed word ending >= 1 s before the end
a = new L.Agreement(); a.update(W(long), 20, 0); a.update(W(long), 20, 0);
assert.strictEqual(a.trim(9.2), 0, 'waits for a pause');
cut = a.trim(10.5);
assert.ok(Math.abs(cut - 8.9) < 1e-9, 'fallback cut ' + cut); assert.strictEqual(a.locked.length, 18);
// commit margin: words ending within 1 s of the window end stay tentative
a = new L.Agreement(); a.update(W('a b c d')); a.update(W('a b c d'), 2.0);
assert.strictEqual(a.stableText(), 'a b'); assert.strictEqual(a.tentativeText(), 'c d');
assert.strictEqual(a.trim(5), 0, 'short windows are not trimmed');
// hard cap with nothing committed: lock what is shown, restart the window
a = new L.Agreement(); a.update(W('a b c')); assert.strictEqual(a.trim(12.5), 12.5); assert.strictEqual(a.text(), 'a b c');
a.update(W('d e')); assert.strictEqual(a.text(), 'a b c d e');
console.log('ok');
`
	cmd := exec.Command(node, "-e", harness)
	cmd.Stdin = strings.NewReader(string(src))
	out, err := cmd.CombinedOutput()
	if err != nil || !strings.Contains(string(out), "ok") {
		t.Fatalf("node harness failed: %v\n%s", err, out)
	}
}
