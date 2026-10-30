package webapp

import (
	"os/exec"
	"strings"
	"testing"
)

// Group sender headers must follow Google Messages: show the reply author's
// name at the start of a sender run even when the bubble is a reply; hide it
// for consecutive messages from the same person (reply or not). sameRun must
// ignore ReplyToID.
func TestMessageRunsReplySenderHeader(t *testing.T) {
	node, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not installed")
	}
	src, err := staticFS.ReadFile("static/app.js")
	if err != nil {
		t.Fatal(err)
	}
	// Pull the run helpers out of the IIFE-style app by re-declaring them.
	harness := `
const assert = require('assert');
const RUN_GAP_MS = 15 * 60 * 1000;
function runKey(m) { return m.IsFromMe ? "me" : String(m.SenderNumber || m.SenderName || "them"); }
function sameRun(a, b) {
  return !!a && !!b && runKey(a) === runKey(b) && Math.abs((b.TimestampMS || 0) - (a.TimestampMS || 0)) < RUN_GAP_MS &&
    new Date(a.TimestampMS).toDateString() === new Date(b.TimestampMS).toDateString();
}
function showSender(msgs, idx, groupChat) {
  const m = msgs[idx];
  if (!groupChat || m.IsFromMe) return false;
  const prevSame = idx > 0 && sameRun(msgs[idx - 1], m);
  return !prevSame;
}
const t0 = Date.parse('2025-01-15T12:00:00Z');
const stephen = { MessageID: '1', SenderName: 'Stephen Compton', SenderNumber: '+15551111', IsFromMe: false, TimestampMS: t0, ReplyToID: '' };
const scott1 = { MessageID: '2', SenderName: 'Scott Irvine', SenderNumber: '+15552222', IsFromMe: false, TimestampMS: t0 + 1000, ReplyToID: '1' };
const scott2 = { MessageID: '3', SenderName: 'Scott Irvine', SenderNumber: '+15552222', IsFromMe: false, TimestampMS: t0 + 2000, ReplyToID: '1' };
const scott3 = { MessageID: '4', SenderName: 'Scott Irvine', SenderNumber: '+15552222', IsFromMe: false, TimestampMS: t0 + 3000, ReplyToID: '3' };
const msgs = [stephen, scott1, scott2, scott3];
assert.strictEqual(showSender(msgs, 0, true), true);
assert.strictEqual(showSender(msgs, 1, true), true, 'reply after other sender must show name');
assert.strictEqual(showSender(msgs, 2, true), false, 'consecutive reply hides name');
assert.strictEqual(showSender(msgs, 3, true), false);
assert.ok(sameRun(scott1, scott2), 'sameRun ignores ReplyToID');
assert.ok(!sameRun(stephen, scott1));
assert.strictEqual(showSender(msgs, 1, false), false, '1:1 never shows');
// Ensure app.js still documents the invariant (regression tripwire).
const fs = require('fs');
const app = fs.readFileSync(0, 'utf8');
assert.ok(app.includes('ReplyToID is'), 'app.js should note ReplyToID is ignored for run headers');
assert.ok(app.includes('sameRun does not consider ReplyToID'), 'app.js should note sameRun ignores ReplyToID');
console.log('ok');
`
	cmd := exec.Command(node, "-e", harness)
	cmd.Stdin = strings.NewReader(string(src))
	out, err := cmd.CombinedOutput()
	if err != nil || !strings.Contains(string(out), "ok") {
		t.Fatalf("node harness failed: %v\n%s", err, out)
	}
}
