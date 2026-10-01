// Live typing helpers (no DOM): resampling mic audio to 16 kHz PCM and the
// LocalAgreement-2 policy that decides which words are stable.
// Loaded before app.js as window.TMLive; also require()-able for tests.
(function (root) {
  "use strict";
  var RATE = 16000;

  // Box-filter decimator from the AudioContext rate to 16 kHz: each output
  // sample is the mean of the input samples it covers (cheap anti-aliasing).
  function Resampler(fromRate) {
    this.ratio = fromRate / RATE;
    this.need = this.ratio; this.acc = 0; this.cnt = 0;
  }
  Resampler.prototype.push = function (f32) {
    var out = new Int16Array(Math.ceil(f32.length / this.ratio) + 2), n = 0;
    for (var i = 0; i < f32.length; i++) {
      this.acc += f32[i]; this.cnt++;
      if (this.cnt >= this.need) {
        var v = this.acc / this.cnt;
        v = v > 1 ? 1 : v < -1 ? -1 : v;
        out[n++] = v < 0 ? v * 0x8000 : v * 0x7fff;
        this.need = this.need - this.cnt + this.ratio;
        this.acc = 0; this.cnt = 0;
      }
    }
    return out.subarray(0, n);
  };

  function norm(w) {
    return String(w || "").toLowerCase().replace(/[^\p{L}\p{N}']+/gu, "").replace(/^'+|'+$/g, "");
  }
  // Word-level longest common prefix of two hypotheses.
  function lcp(a, b) {
    var n = 0;
    while (n < a.length && n < b.length && norm(a[n].w) === norm(b[n].w)) n++;
    return n;
  }
  function shift(words, by) {
    return words.map(function (w) { return { w: w.w, s: Math.max(0, w.s - by), e: Math.max(0, w.e - by) }; });
  }

  // LocalAgreement-2 over hypotheses of a growing window: a word becomes
  // committed once two consecutive hypotheses agree on it (after the words
  // already committed). Committed words never change; the rest is tentative
  // and replaced by each new hypothesis. When the window gets long, words
  // committed well before its end move to `locked` and the caller drops
  // that audio, so windows stay short (see trim).
  function Agreement() { this.locked = []; this.committed = []; this.prevTail = []; this.tentative = []; }
  // Index in hyp just past the words that correspond to this.committed.
  Agreement.prototype.align = function (hyp) {
    var c = this.committed.length, i, k;
    if (!c) return 0;
    var same = c <= hyp.length;
    for (i = 0; same && i < c; i++) if (norm(hyp[i].w) !== norm(this.committed[i].w)) same = false;
    if (same) return c;
    // Whisper re-spelled or split/merged an earlier word: look for the last
    // committed word near where it should be.
    var last = norm(this.committed[c - 1].w), best = -1;
    for (k = Math.max(0, c - 3); k < Math.min(hyp.length, c + 3); k++) {
      if (norm(hyp[k].w) === last && (best < 0 || Math.abs(k + 1 - c) < Math.abs(best - c))) best = k + 1;
    }
    if (best >= 0) return best;
    // Fall back to time: skip words ending before the last committed one.
    var tEnd = this.committed[c - 1].e;
    k = 0;
    while (k < hyp.length && hyp[k].e <= tEnd + 0.05) k++;
    return k;
  };
  // windowSecs (optional): words ending within `margin` s of the window end
  // were probably cut mid-word, so they can't be committed yet.
  Agreement.prototype.update = function (hyp, windowSecs, margin) {
    hyp = (hyp || []).filter(function (w) { return w && norm(w.w); });
    if (!hyp.length) return 0; // silence / [BLANK_AUDIO]: keep what's shown
    var tail = hyp.slice(this.align(hyp));
    var n = lcp(this.prevTail, tail);
    if (windowSecs) {
      var limit = windowSecs - (margin == null ? 1 : margin);
      while (n > 0 && tail[n - 1].e > limit) n--;
    }
    for (var i = 0; i < n; i++) this.committed.push(tail[i]);
    this.prevTail = tail.slice(n);
    this.tentative = this.prevTail;
    return n;
  };
  // Called after an update with the window length (s). Returns how many
  // seconds of audio the caller should drop from the window start (0 = none).
  //   - window > trimAfter: lock committed words that end >= keep s before
  //     the window end, cutting at a pause (sentence end or a gap before the
  //     next word) so no word is split; past trimAfter + 2 s any committed
  //     word will do;
  //   - window > hardCap with nothing committed to cut at: lock everything
  //     shown and restart the window (the final pass cleans up any seam).
  Agreement.prototype.trim = function (windowSecs, opt) {
    opt = opt || {};
    var trimAfter = opt.trimAfter || 8, hardCap = opt.hardCap || 12, keep = opt.keep || 1;
    if (windowSecs <= trimAfter) return 0;
    var c = this.committed, best = -1, anyLast = -1;
    for (var j = 0; j < c.length; j++) {
      if (!(c[j].e > 0 && c[j].e <= windowSecs - keep)) continue;
      anyLast = j;
      var next = c[j + 1] || this.tentative[0];
      if (/[.?!,;:]$/.test(c[j].w) || (next && next.s - c[j].e >= 0.25)) best = j;
    }
    var i = best >= 0 ? best : windowSecs > trimAfter + 2 ? anyLast : -1;
    if (i >= 0) {
      var w = c[i], nx = c[i + 1] || this.tentative[0];
      var cut = nx && nx.s > w.e ? w.e + Math.min(0.15, (nx.s - w.e) / 2) : w.e;
      if (cut > 0) {
        this.locked = this.locked.concat(c.slice(0, i + 1).map(function (x) { return x.w; }));
        this.committed = shift(c.slice(i + 1), cut);
        this.prevTail = shift(this.prevTail, cut);
        this.tentative = this.prevTail;
        return cut;
      }
    }
    if (windowSecs > hardCap) { this.lockAll(); return windowSecs; }
    return 0;
  };
  // Lock everything shown and start a fresh window (caller moves the audio).
  Agreement.prototype.lockAll = function () {
    this.locked = this.locked.concat(this.committed.concat(this.tentative).map(function (x) { return x.w; }));
    this.committed = []; this.prevTail = []; this.tentative = [];
  };
  Agreement.prototype.stableText = function () {
    return this.locked.concat(this.committed.map(function (x) { return x.w; })).join(" ");
  };
  Agreement.prototype.tentativeText = function () { return this.tentative.map(function (x) { return x.w; }).join(" "); };
  Agreement.prototype.text = function () {
    var a = this.stableText(), b = this.tentativeText();
    return a && b ? a + " " + b : a || b;
  };

  var api = { RATE: RATE, Resampler: Resampler, Agreement: Agreement, norm: norm, lcp: lcp };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.TMLive = api;
})(typeof window !== "undefined" ? window : this);
