// Login page: keep the secret field clear of the on-screen keyboard.
// "Keyboard up" = the field has focus (the car browser may not resize the
// viewport for its keyboard) or visualViewport is >150px shorter than the
// window. Then the title row folds away (body.kb-open) and the page is
// scrolled so the field sits at the top of what's visible.
(function () {
  "use strict";
  var input = document.getElementById("secret");
  if (!input) return;
  var vv = window.visualViewport;
  // autofocus alone doesn't open the car's keyboard, so focus counts only
  // after the field was tapped (or typed in).
  var tapped = false;
  function markTapped() { tapped = true; setTimeout(lift, 0); } // may already have focus (autofocus)
  input.addEventListener("pointerdown", markTapped);
  input.addEventListener("touchstart", markTapped, { passive: true });
  input.addEventListener("keydown", function () { if (!tapped) { tapped = true; lift(); } });
  function keyboardUp() {
    var shrunk = vv ? window.innerHeight - vv.height > 150 : false;
    return (tapped && document.activeElement === input) || shrunk;
  }
  function lift() {
    var up = keyboardUp();
    document.body.classList.toggle("kb-open", up);
    if (up) {
      window.scrollTo(0, 0);
      var r = input.getBoundingClientRect(), top = vv ? vv.offsetTop : 0, h = vv ? vv.height : window.innerHeight;
      if (r.bottom + 120 > top + h) input.scrollIntoView({ block: "start" });
    }
  }
  input.addEventListener("focus", lift);
  input.addEventListener("blur", function () { tapped = false; setTimeout(lift, 50); });
  if (vv) { vv.addEventListener("resize", lift); vv.addEventListener("scroll", lift); }
  lift();
})();

// Remember a launch from the Messages Enhanced Android app (see app.js).
try {
  if (document.referrer.indexOf("android-app://com.ubermicrostudios.messagesenhanced") === 0) sessionStorage.setItem("me_android_twa", "1");
} catch (e) { /* storage blocked */ }
