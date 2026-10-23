/* Messages Enhanced: lightweight pinch/pan/double-tap zoom for the photo
   viewer (Pointer Events + one CSS transform; no libraries, no layout work
   per frame, so it stays smooth in the Tesla browser).
   window.MEPinch.attach(img) -> { reset() }. Pure math is exported for tests. */
(function (root) {
  "use strict";
  var MIN = 1, MAX = 5, DOUBLE = 2.5, DBL_MS = 300, DBL_PX = 30;
  function clamp(v, lo, hi) { return v < lo ? lo : v > hi ? hi : v; }
  // Keep the scaled image covering its box: pan limited to the overflow.
  function clampPan(s, x, y, w, h) {
    var mx = (w * s - w) / 2, my = (h * s - h) / 2;
    return { x: clamp(x, -mx, mx) + 0, y: clamp(y, -my, my) + 0 }; // +0: no -0
  }
  // Zoom to scale s2 keeping point (px,py) (relative to the image centre) fixed.
  function zoomAt(t, s2, px, py, w, h) {
    s2 = clamp(s2, MIN, MAX);
    var k = s2 / t.s, x = px - (px - t.x) * k, y = py - (py - t.y) * k;
    var p = clampPan(s2, x, y, w, h);
    return { s: s2, x: p.x, y: p.y };
  }
  // Double tap: zoom in at the point, or back out when already zoomed.
  function doubleTap(t, px, py, w, h) {
    return t.s > 1.01 ? { s: 1, x: 0, y: 0 } : zoomAt(t, DOUBLE, px, py, w, h);
  }
  function attach(img) {
    var t = { s: 1, x: 0, y: 0 }, pts = {}, n = 0, start = null, lastTap = 0, lastX = 0, lastY = 0, moved = false;
    img.style.touchAction = "none";
    img.style.transformOrigin = "50% 50%";
    function apply(anim) {
      img.style.transition = anim ? "transform .2s ease-out" : "none";
      img.style.transform = t.s === 1 && !t.x && !t.y ? "" : "translate(" + t.x + "px," + t.y + "px) scale(" + t.s + ")";
    }
    // page px per client px (the UI zoom on <html> may scale coordinates)
    function geo() {
      var w = img.offsetWidth || 1, h = img.offsetHeight || 1, r = img.getBoundingClientRect();
      var k = r.width / (w * t.s) || 1;
      return { w: w, h: h, k: k, cx: r.left + r.width / 2 - t.x * k, cy: r.top + r.height / 2 - t.y * k };
    }
    function rel(g, cx, cy) { return { x: (cx - g.cx) / g.k, y: (cy - g.cy) / g.k }; }
    function snapshot() {
      var ids = Object.keys(pts), g = geo(), a = pts[ids[0]], b = pts[ids[1]] || a;
      var m = rel(g, (a.x + b.x) / 2, (a.y + b.y) / 2);
      start = { t: { s: t.s, x: t.x, y: t.y }, g: g, mx: m.x, my: m.y, d: Math.hypot(a.x - b.x, a.y - b.y) || 1 };
    }
    img.addEventListener("pointerdown", function (e) {
      e.stopPropagation();
      try { img.setPointerCapture(e.pointerId); } catch (_) {}
      pts[e.pointerId] = { x: e.clientX, y: e.clientY }; n++; moved = false;
      snapshot();
    });
    img.addEventListener("pointermove", function (e) {
      if (!pts[e.pointerId]) return;
      pts[e.pointerId] = { x: e.clientX, y: e.clientY };
      var ids = Object.keys(pts), g = start.g, a = pts[ids[0]], b = pts[ids[1]] || a;
      var m = rel(g, (a.x + b.x) / 2, (a.y + b.y) / 2);
      if (Math.abs(m.x - start.mx) + Math.abs(m.y - start.my) > 6 / g.k) moved = true;
      var s2 = ids.length > 1 ? start.t.s * Math.hypot(a.x - b.x, a.y - b.y) / start.d : start.t.s;
      if (ids.length < 2 && start.t.s <= 1) return;           // nothing to pan at 1x
      var z = zoomAt(start.t, s2, start.mx, start.my, g.w, g.h);
      var p = clampPan(z.s, z.x + (m.x - start.mx), z.y + (m.y - start.my), g.w, g.h);
      t = { s: z.s, x: p.x, y: p.y }; apply(false);
      e.preventDefault();
    });
    function up(e) {
      if (!pts[e.pointerId]) return;
      delete pts[e.pointerId]; n--;
      if (n > 0) { snapshot(); return; }
      if (t.s < 1.02) { t = { s: 1, x: 0, y: 0 }; apply(true); }
      if (e.type !== "pointerup" || moved) { lastTap = 0; return; }
      var now = Date.now();
      if (now - lastTap < DBL_MS && Math.abs(e.clientX - lastX) < DBL_PX && Math.abs(e.clientY - lastY) < DBL_PX) {
        var g = geo(), p = rel(g, e.clientX, e.clientY);
        t = doubleTap(t, p.x, p.y, g.w, g.h); apply(true); lastTap = 0;
      } else { lastTap = now; lastX = e.clientX; lastY = e.clientY; }
    }
    img.addEventListener("pointerup", up);
    img.addEventListener("pointercancel", up);
    img.addEventListener("click", function (e) { e.stopPropagation(); }); // taps on the photo don't close
    img.addEventListener("wheel", function (e) {                          // mouse/trackpad
      e.preventDefault(); e.stopPropagation();
      var g = geo(), p = rel(g, e.clientX, e.clientY);
      t = zoomAt(t, t.s * Math.exp(-e.deltaY / 300), p.x, p.y, g.w, g.h); apply(false);
    }, { passive: false });
    return { reset: function () { pts = {}; n = 0; lastTap = 0; t = { s: 1, x: 0, y: 0 }; apply(false); } };
  }
  var api = { attach: attach, clampPan: clampPan, zoomAt: zoomAt, doubleTap: doubleTap, MIN: MIN, MAX: MAX };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (root) root.MEPinch = api;
})(typeof window !== "undefined" ? window : null);
