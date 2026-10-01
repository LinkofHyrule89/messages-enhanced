/* Messages Enhanced: apply per-device display prefs before first paint (no flash).
   Loaded synchronously in <head>; the settings live in localStorage
   "tm.settings" (app.js owns the Settings controls):
     zoom     UI zoom percent (50-175), "auto", or unset (= the default).
              Default in Car Mode: DEFAULT_CAR_ZOOM (50%) on screens at least
              AUTO_MIN_W wide (the car), 100% on phones / portrait. "auto" is
              only stored when picked in Settings: it fits the car layout to
              the window (scales down when the window is narrower or shorter
              than CAR_DESIGN_W x CAR_DESIGN_H, clamped 50-100%) and
              re-computes on resize. A manual zoom is kept as chosen.
     carMode  true (default) = big-touch car layout; false = denser
              tablet/desktop layout (html.desk). Auto means 100% there.
   The CSS is px-based and sized for the car, so the desktop layout scales
   the page by DESK_SCALE (on top of the UI zoom) and adds .desk overrides.
   html.narrow (one pane at a time) is set when the layout width in page px
   (window width / zoom in Car Mode) is at most NARROW_W. */
(function () {
  "use strict";
  var ZOOM_STEPS = [50, 60, 67, 75, 90, 100, 110, 125, 150, 175];
  var DESK_SCALE = 0.62;
  var CAR_DESIGN_W = 1800, CAR_DESIGN_H = 1000; // full-screen car browser: stays 100%
  var AUTO_MIN_W = 1000; // phones / portrait: no auto shrink (one-pane layout at 100%, as before)
  var AUTO_MIN = 50, NARROW_W = 1100;
  var DEFAULT_CAR_ZOOM = 50;
  var P = {
    ZOOM_STEPS: ZOOM_STEPS,
    DESK_SCALE: DESK_SCALE,
    CAR_DESIGN_W: CAR_DESIGN_W,
    CAR_DESIGN_H: CAR_DESIGN_H,
    DEFAULT_CAR_ZOOM: DEFAULT_CAR_ZOOM,
    manualZoom: function (s) { return !!s && ZOOM_STEPS.indexOf(+s.zoom) >= 0; },
    zoomOf: function (s) { return P.manualZoom(s) ? +s.zoom : 100; },
    carMode: function (s) { return !(s && s.carMode === false); },
    isAuto: function (s) { return P.carMode(s) && !!s && s.zoom === "auto"; },
    // No zoom picked on this device yet (the default applies).
    isDefault: function (s) { return !P.manualZoom(s) && !P.isAuto(s); },
    // Default zoom for a window width: 50% on the car-sized screen, 100% on
    // phones / portrait (and when the width is unknown); desk mode is 100%.
    defaultZoom: function (w, car) { return car && w >= AUTO_MIN_W ? DEFAULT_CAR_ZOOM : 100; },
    autoZoom: function (w, h) {
      if (!(w > 0) || !(h > 0) || w < AUTO_MIN_W) return 100;
      var pct = Math.floor(Math.min(w / CAR_DESIGN_W, h / CAR_DESIGN_H, 1) * 100);
      return Math.max(AUTO_MIN, Math.min(100, pct));
    },
    // The zoom percent in effect right now (auto resolved against the window).
    effectiveZoom: function (s) {
      if (P.isAuto(s)) return P.autoZoom(window.innerWidth, window.innerHeight);
      if (P.manualZoom(s)) return +s.zoom;
      return P.defaultZoom(window.innerWidth, P.carMode(s));
    },
    // CSS zoom factor for <html> (also exported as --z for vh/vw math).
    scale: function (zoomPct, carMode) { return Math.round((zoomPct / 100) * (carMode ? 1 : DESK_SCALE) * 1000) / 1000; },
    apply: function (s) {
      P.current = s;
      var d = document.documentElement, car = P.carMode(s), k = P.scale(P.effectiveZoom(s), car);
      var w = window.innerWidth;
      d.classList.toggle("desk", !car);
      d.classList.toggle("car", car);
      d.classList.toggle("narrow", w > 0 && (car ? w / k : w) <= NARROW_W);
      d.style.setProperty("--z", String(k));
      d.style.zoom = k === 1 ? "" : String(k);
      return k;
    },
  };
  var s = null;
  try { s = JSON.parse(localStorage.getItem("tm.settings") || "null"); } catch (e) {}
  P.apply(s && typeof s === "object" ? s : null);
  window.TMPrefs = P;
  if (window.addEventListener) {
    var t = null;
    window.addEventListener("resize", function () {
      clearTimeout(t);
      t = setTimeout(function () {
        var k = P.apply(P.current);
        if (P.onchange) P.onchange(k);
      }, 60);
    });
  }
})();
