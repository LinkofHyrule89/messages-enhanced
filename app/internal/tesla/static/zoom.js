/* Tesla Messages: apply per-device display prefs before first paint (no flash).
   Loaded synchronously in <head>; the settings live in localStorage
   "tm.settings" (app.js owns the Settings controls):
     zoom     UI zoom percent (75-175, default 100)
     carMode  true (default) = big-touch car layout; false = denser
              tablet/desktop layout (html.desk).
   The CSS is px-based and sized for the car, so the desktop layout scales
   the page by DESK_SCALE (on top of the UI zoom) and adds .desk overrides. */
(function () {
  "use strict";
  var ZOOM_STEPS = [75, 90, 100, 110, 125, 150, 175];
  var DESK_SCALE = 0.62;
  var P = {
    ZOOM_STEPS: ZOOM_STEPS,
    DESK_SCALE: DESK_SCALE,
    zoomOf: function (s) { return s && ZOOM_STEPS.indexOf(+s.zoom) >= 0 ? +s.zoom : 100; },
    carMode: function (s) { return !(s && s.carMode === false); },
    // CSS zoom factor for <html> (also exported as --z for vh/vw math).
    scale: function (zoomPct, carMode) { return Math.round((zoomPct / 100) * (carMode ? 1 : DESK_SCALE) * 1000) / 1000; },
    apply: function (s) {
      var d = document.documentElement, car = P.carMode(s), k = P.scale(P.zoomOf(s), car);
      d.classList.toggle("desk", !car);
      d.classList.toggle("car", car);
      d.style.setProperty("--z", String(k));
      d.style.zoom = k === 1 ? "" : String(k);
      return k;
    },
  };
  var s = null;
  try { s = JSON.parse(localStorage.getItem("tm.settings") || "null"); } catch (e) {}
  P.apply(s && typeof s === "object" ? s : null);
  window.TMPrefs = P;
})();
