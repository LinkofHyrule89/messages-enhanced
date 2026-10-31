// Message effects (our own code, no Google assets): full-screen keyword
// effects and reaction bursts like the Google Messages phone app, and
// Noto Animated Emoji (Google, CC BY 4.0) for emoji-only messages.
// window.MEEffects = { detect(text), play(kind, opts), playForMessage(m, opts),
//   reaction(emoji, opts), animateEmoji(bubbleBody, text) }
(function () {
  "use strict";
  // ---------- keyword triggers ----------
  // Each: kind, regexes (case-insensitive) and emoji. First match wins.
  var E = function (s) { return s; };
  var RULES = [
    { kind: "fireworks", re: /\bhappy new year\b|\bhappy (4th|fourth)( of july)?\b|\bfireworks?\b|\bhappy independence day\b/i, emoji: ["\u{1F386}", "\u{1F387}", "\u{1F9E8}"] },
    { kind: "celebrate", re: /\bhappy (birthday|bday|anniversary|graduation)\b|\bhbd\b|\bcongrat(s|ulations)\b|\bwe did it\b/i, emoji: ["\u{1F389}", "\u{1F973}", "\u{1F38A}", "\u{1F382}"] },
    { kind: "love", re: /\bi love (you|u)\b|\blove (you|u|ya)\b|\bhappy valentine'?s?( day)?\b|\bmiss (you|u)\b/i, emoji: ["\u2764\uFE0F", "\u{1F60D}", "\u{1F970}", "\u{1F498}", "\u{1F496}"] },
    { kind: "winter", re: /\bmerry (christmas|xmas)\b|\bhappy holidays\b|\blet it snow\b|\bit'?s snowing\b|\bhappy hanukkah\b|\bseason'?s greetings\b/i, emoji: ["\u2744\uFE0F", "\u2603\uFE0F", "\u26C4", "\u{1F384}"] },
    { kind: "thanksgiving", re: /\bhappy thanksgiving\b|\bgobble gobble\b/i, emoji: ["\u{1F983}"] },
    { kind: "fall", re: /\bhappy (fall|autumn)\b|\bhappy halloween\b|\btrick or treat\b/i, emoji: ["\u{1F342}", "\u{1F341}", "\u{1F383}"] },
    { kind: "spring", re: /\bhappy (spring|easter)\b|\bspring is here\b/i, emoji: ["\u{1F338}", "\u{1F337}", "\u{1F33C}"] },
    { kind: "summer", re: /\bhappy summer\b|\bsummer vibes\b|\bbeach day\b/i, emoji: ["\u2600\uFE0F", "\u{1F3D6}\uFE0F", "\u{1F31E}"] },
    { kind: "olympics", re: /\bgo team\b|\bgold medal\b|\blet'?s go+\b/i, emoji: ["\u{1F947}", "\u{1F3C5}", "\u{1F3C6}"] },
    { kind: "goodmorning", re: /\bgood ?morning\b|\brise and shine\b/i, emoji: ["\u{1F305}", "\u{1F304}"] },
    { kind: "goodnight", re: /\bgood ?night\b|\bsweet dreams\b|\bnighty night\b/i, emoji: ["\u{1F319}", "\u{1F634}", "\u{1F31B}"] },
    { kind: "laugh", re: /\b(lol|lmao|lmfao|rofl)\b|\bha(ha)+\b/i, emoji: ["\u{1F602}", "\u{1F923}"] },
    { kind: "surprise", re: /\bomg\b|\boh my (god|gosh)\b|\bno way\b/i, emoji: ["\u{1F62E}", "\u{1F632}", "\u{1F92F}"] },
    { kind: "sad", re: /\bso sad\b|\bi'?m sad\b|\bsorry for your loss\b/i, emoji: ["\u{1F622}", "\u{1F62D}", "\u{1F97A}"] },
    { kind: "anger", re: /\bso (mad|angry)\b|\bi'?m (mad|angry|furious)\b/i, emoji: ["\u{1F621}", "\u{1F620}", "\u{1F92C}"] }
  ];
  function detect(text) {
    var t = String(text || "");
    if (!t.trim() || t.length > 600) return null;
    for (var i = 0; i < RULES.length; i++) {
      var r = RULES[i];
      if (r.re.test(t)) return r.kind;
      for (var j = 0; j < r.emoji.length; j++) if (t.indexOf(r.emoji[j]) >= 0) return r.kind;
    }
    return null;
  }

  // ---------- particle engine (canvas) ----------
  var reduce = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  var canvas = null, ctx = null, parts = [], running = false, until = 0, overlay = null;
  function ensure() {
    if (canvas) return;
    canvas = document.createElement("canvas");
    canvas.className = "fx-canvas";
    canvas.setAttribute("aria-hidden", "true");
    document.body.appendChild(canvas);
    ctx = canvas.getContext("2d");
  }
  function size() {
    var d = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.round(innerWidth * d); canvas.height = Math.round(innerHeight * d);
    canvas.style.width = innerWidth + "px"; canvas.style.height = innerHeight + "px";
    ctx.setTransform(d, 0, 0, d, 0, 0);
  }
  var R = Math.random;
  function rnd(a, b) { return a + R() * (b - a); }
  var CONFETTI = ["#ff5a5f", "#ffb400", "#00c2a8", "#4285f4", "#a142f4", "#34a853", "#ff7ab6"];
  function add(p) { parts.push(p); }
  function spawn(kind, n, emoji) {
    var W = innerWidth, H = innerHeight;
    var i;
    switch (kind) {
      case "celebrate": case "olympics":
        var cols = kind === "olympics" ? ["#0081c8", "#fcb131", "#000000", "#00a651", "#ee334e"] : CONFETTI;
        for (i = 0; i < n; i++) {
          var left = i % 2 === 0;
          add({ t: "rect", x: left ? -10 : W + 10, y: H * rnd(.55, .9), vx: (left ? 1 : -1) * rnd(4, 11), vy: -rnd(9, 17), g: .32, drag: .985,
            w: rnd(6, 11), h: rnd(10, 16), rot: rnd(0, 6), vr: rnd(-.3, .3), c: cols[i % cols.length], life: rnd(140, 200) });
        }
        break;
      case "fireworks":
        for (var b = 0; b < Math.max(2, Math.round(n / 40)); b++) {
          var cx = W * rnd(.2, .8), cy = H * rnd(.15, .45), col = CONFETTI[b % CONFETTI.length], delay = b * 22;
          for (i = 0; i < 46; i++) {
            var a = (i / 46) * Math.PI * 2, s = rnd(2.5, 5.5);
            add({ t: "dot", x: cx, y: cy, vx: Math.cos(a) * s, vy: Math.sin(a) * s, g: .05, drag: .975, r: rnd(2, 3.4), c: col, life: rnd(70, 100), delay: delay, trail: true });
          }
        }
        break;
      case "winter": case "fall": case "thanksgiving": case "spring":
        var em = kind === "winter" ? null : emoji;
        for (i = 0; i < n; i++) add({ t: em ? "emoji" : "flake", e: em ? em[i % em.length] : "", x: rnd(0, W), y: -rnd(10, H * .6), vx: rnd(-.6, .6), vy: rnd(1.2, 2.8),
          sway: rnd(.5, 1.6), ph: rnd(0, 6), r: rnd(2, 5), fs: rnd(18, 30), rot: rnd(0, 6), vr: rnd(-.04, .04), life: 260, c: "#ffffff" });
        break;
      case "goodnight":
        for (i = 0; i < n; i++) add({ t: "star", x: rnd(0, W), y: rnd(0, H * .7), r: rnd(1, 2.8), tw: rnd(0, 6), life: rnd(150, 220), vx: 0, vy: 0 });
        add({ t: "emoji", e: "\u{1F319}", x: W * .78, y: H * .18, vx: 0, vy: 0, fs: 64, life: 220, fade: true });
        break;
      case "goodmorning":
        add({ t: "sun", x: W / 2, y: H + 40, vy: -2.2, vx: 0, life: 200 });
        break;
      case "love":
        for (i = 0; i < n; i++) add({ t: "emoji", e: emoji[i % emoji.length], x: rnd(0, W), y: H + rnd(10, H * .5), vx: rnd(-.4, .4), vy: -rnd(2.2, 4.2),
          sway: rnd(.4, 1.2), ph: rnd(0, 6), fs: rnd(22, 40), life: 240 });
        break;
      default: // laugh, surprise, sad, anger, reactions: emoji burst rising from below
        for (i = 0; i < n; i++) add({ t: "emoji", e: emoji[i % emoji.length], x: rnd(W * .1, W * .9), y: H + rnd(10, 120), vx: rnd(-2, 2), vy: -rnd(7, 13), g: .18,
          fs: rnd(26, 46), rot: rnd(-.4, .4), vr: rnd(-.05, .05), life: 150 });
        if (kind === "sad") for (i = 0; i < n * 2; i++) add({ t: "drop", x: rnd(0, W), y: -rnd(0, H), vx: 0, vy: rnd(7, 11), life: 160, c: "#8ab4f8" });
    }
  }
  function frame() {
    if (!running) return;
    var W = innerWidth, H = innerHeight;
    ctx.clearRect(0, 0, W, H);
    var alive = [];
    for (var i = 0; i < parts.length; i++) {
      var p = parts[i];
      if (p.delay > 0) { p.delay--; alive.push(p); continue; }
      p.age = (p.age || 0) + 1;
      if (p.age > p.life) continue;
      if (p.g) p.vy += p.g;
      if (p.drag) { p.vx *= p.drag; p.vy *= p.drag; }
      var sx = p.sway ? Math.sin(p.age / 30 + p.ph) * p.sway : 0;
      p.x += p.vx + sx; p.y += p.vy;
      if (p.vr) p.rot += p.vr;
      var fadeIn = Math.min(1, p.age / 10), fadeOut = Math.min(1, (p.life - p.age) / 30);
      ctx.globalAlpha = Math.max(0, Math.min(fadeIn, fadeOut)) * opacity;
      ctx.save();
      ctx.translate(p.x, p.y);
      if (p.rot) ctx.rotate(p.rot);
      switch (p.t) {
        case "rect": ctx.fillStyle = p.c; ctx.fillRect(-p.w / 2, -p.h / 2, p.w, p.h * Math.abs(Math.cos(p.age / 8))); break;
        case "dot": ctx.fillStyle = p.c; ctx.beginPath(); ctx.arc(0, 0, p.r, 0, 7); ctx.fill(); break;
        case "flake": ctx.fillStyle = p.c; ctx.beginPath(); ctx.arc(0, 0, p.r, 0, 7); ctx.fill(); break;
        case "drop": ctx.strokeStyle = p.c; ctx.lineWidth = 2; ctx.beginPath(); ctx.moveTo(0, 0); ctx.lineTo(0, 14); ctx.stroke(); break;
        case "star":
          ctx.globalAlpha *= .55 + .45 * Math.sin(p.age / 8 + p.tw);
          ctx.fillStyle = "#fff8d0"; ctx.beginPath(); ctx.arc(0, 0, p.r, 0, 7); ctx.fill(); break;
        case "sun":
          var g = ctx.createRadialGradient(0, 0, 10, 0, 0, Math.max(W, H) * .6);
          g.addColorStop(0, "rgba(255,214,102,.75)"); g.addColorStop(.25, "rgba(255,170,64,.35)"); g.addColorStop(1, "rgba(255,170,64,0)");
          ctx.fillStyle = g; ctx.fillRect(-W, -H, W * 2, H * 2);
          if (p.y < H * .7) p.vy *= .97;
          break;
        default: ctx.font = p.fs + "px 'Noto Color Emoji', 'Apple Color Emoji', 'Segoe UI Emoji', sans-serif"; ctx.textAlign = "center"; ctx.textBaseline = "middle"; ctx.fillText(p.e, 0, 0);
      }
      ctx.restore();
      if (p.y < H + 200 && p.y > -H && p.x > -200 && p.x < W + 200) alive.push(p);
      else if (p.t === "flake" || p.t === "emoji" && p.vy > 0) { if (p.y <= H + 200) alive.push(p); }
    }
    ctx.globalAlpha = 1;
    parts = alive;
    if (!parts.length || Date.now() > until) { stop(); return; }
    requestAnimationFrame(frame);
  }
  function stop() { running = false; parts = []; if (canvas) { ctx.clearRect(0, 0, innerWidth, innerHeight); canvas.style.display = "none"; } }
  var opacity = 1;
  // opts.subtle: Car Mode (fewer particles, lower opacity, shorter).
  function play(kind, opts) {
    opts = opts || {};
    if (reduce || !kind) return false;
    var rule = RULES.filter(function (r) { return r.kind === kind; })[0];
    var emoji = opts.emoji || (rule ? rule.emoji : ["\u2728"]);
    ensure(); size();
    canvas.style.display = "block";
    opacity = opts.subtle ? .45 : 1;
    var n = opts.subtle ? 18 : (kind === "goodnight" ? 70 : kind === "fireworks" ? 160 : 90);
    if (opts.count) n = opts.count;
    spawn(kind, n, emoji);
    until = Date.now() + (opts.subtle ? 2200 : 4500);
    if (!running) { running = true; requestAnimationFrame(frame); }
    return true;
  }
  // Reaction bursts (phone: 👍 ❤️ 😂 😮 😢 😡 👎 💩 🎉).
  var REACTION_FX = ["\u{1F44D}", "\u2764\uFE0F", "\u{1F602}", "\u{1F62E}", "\u{1F622}", "\u{1F621}", "\u{1F620}", "\u{1F44E}", "\u{1F4A9}", "\u{1F389}"];
  function reaction(emoji, opts) {
    opts = opts || {};
    var e = String(emoji || "");
    var hit = REACTION_FX.some(function (x) { return x === e || x.replace("\uFE0F", "") === e.replace("\uFE0F", ""); });
    if (!hit) return false;
    if (e.indexOf("\u{1F389}") === 0) return play("celebrate", opts);
    return play("burst", { emoji: [e], subtle: opts.subtle, count: opts.subtle ? 10 : 26 });
  }
  var played = {};
  function playForMessage(m, opts) {
    if (!m || !m.MessageID || played[m.MessageID]) return false;
    played[m.MessageID] = 1;
    var k = detect(m.Body);
    return k ? play(k, opts) : false;
  }

  // ---------- Noto Animated Emoji ----------
  // Emoji-only messages with 1–3 emoji show large and animated (Google
  // Messages animates emoji-only bubbles). Animations are Google's Noto
  // Animated Emoji (CC BY 4.0), loaded from Google Fonts' emoji CDN;
  // unknown emoji stay as text.
  var EMOJI_RE = /\p{Extended_Pictographic}/u;
  function graphemes(text) {
    if (window.Intl && Intl.Segmenter) return Array.from(new Intl.Segmenter(undefined, { granularity: "grapheme" }).segment(text), function (s) { return s.segment; });
    return Array.from(text);
  }
  function emojiOnly(text) {
    var t = String(text || "").replace(/\s+/g, "");
    if (!t || t.length > 40) return null;
    var g = graphemes(t);
    if (g.length < 1 || g.length > 3) return null;
    for (var i = 0; i < g.length; i++) if (!EMOJI_RE.test(g[i])) return null;
    return g;
  }
  function codepoints(e) {
    var out = [];
    for (var ch of e) { var c = ch.codePointAt(0); if (c !== 0xFE0F) out.push(c.toString(16)); }
    return out.join("_");
  }
  function animateEmoji(body, text) {
    var g = emojiOnly(text);
    if (!g || !body) return false;
    body.textContent = "";
    body.classList.add("emoji-anim");
    g.forEach(function (e) {
      var img = new Image();
      img.className = "emoji-anim-img";
      img.alt = e;
      img.decoding = "async";
      img.loading = "lazy";
      img.src = "https://fonts.gstatic.com/s/e/notoemoji/latest/" + codepoints(e) + "/512.webp";
      img.onerror = function () { var s = document.createElement("span"); s.className = "emoji-anim-txt"; s.textContent = e; img.replaceWith(s); };
      body.appendChild(img);
    });
    return true;
  }
  window.MEEffects = { detect: detect, play: play, reaction: reaction, playForMessage: playForMessage, animateEmoji: animateEmoji, emojiOnly: emojiOnly, stop: stop };
})();
