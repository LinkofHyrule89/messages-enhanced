/* Messages Enhanced: service worker registration, "Install app" and Web Push
 * notifications (Settings). Everything degrades quietly: the car browser
 * has no Push API, so it just shows "not supported". Loaded on the car page
 * and the login page (where only the service worker part runs). */
(function () {
  "use strict";
  var $ = function (id) { return document.getElementById(id); };
  var ua = navigator.userAgent || "";
  var isIOS = /iPhone|iPad|iPod/.test(ua) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
  var isCar = /Tesla|QtCarBrowser/.test(ua); // car browser UA
  var standalone = (window.matchMedia && window.matchMedia("(display-mode: standalone)").matches) || navigator.standalone === true;
  var swOK = "serviceWorker" in navigator && window.isSecureContext;
  var pushOK = swOK && "PushManager" in window && "Notification" in window && "showNotification" in (window.ServiceWorkerRegistration ? ServiceWorkerRegistration.prototype : {});
  var HIDE_KEY = "tm.notifHideText";
  var st = { reg: null, cfg: null, sub: null, busy: false, prompt: null, msg: "" };
  window.TMPWA = { refresh: refresh, state: st, updateSW: function () { return st.reg ? st.reg.update().catch(function () {}) : Promise.resolve(); } };

  var regReady = swOK ? navigator.serviceWorker.register("/app/sw.js" + (window.TM_VERSION ? "?v=" + encodeURIComponent(window.TM_VERSION) : ""), { scope: "/app/" }).then(function (r) { st.reg = r; return r; })
    .catch(function (e) { st.swError = String(e && e.message || e); return null; }) : Promise.resolve(null);

  if (swOK) {
    navigator.serviceWorker.addEventListener("message", function (e) {
      var d = e.data || {};
      if (d.type === "open-conversation" && d.conv) window.dispatchEvent(new CustomEvent("tm-open-conversation", { detail: { conv: d.conv, reply: !!d.reply } }));
    });
  }

  // ----- install -----
  window.addEventListener("beforeinstallprompt", function (e) {
    e.preventDefault(); // we offer it in Settings instead of the mini-infobar
    st.prompt = e;
    renderInstall();
  });
  window.addEventListener("appinstalled", function () { st.prompt = null; st.installed = true; renderInstall(); });

  function renderInstall() {
    var sec = $("appSection");
    if (!sec) return;
    var btn = $("installBtn"), hint = $("installHint"), text = "";
    btn.hidden = !st.prompt;
    if (standalone) text = "You're using the installed app.";
    else if (st.installed) text = "Installed. Open Messages Enhanced from your home screen or app list.";
    else if (!st.prompt && isIOS) text = "On iPhone or iPad: tap the Share button in Safari, then “Add to Home Screen”. Open it from there to turn on notifications.";
    else if (!st.prompt && !isCar && "onbeforeinstallprompt" in window) text = "To install, use the browser menu → “Install app” or “Add to Home screen”.";
    hint.textContent = text;
    hint.hidden = !text;
    sec.hidden = !(st.prompt || text);
  }
  function onInstall() {
    var p = st.prompt;
    if (!p) return;
    st.prompt = null;
    p.prompt();
    (p.userChoice || Promise.resolve({})).then(function (c) {
      if (c && c.outcome === "accepted") st.installed = true;
      renderInstall();
    });
  }

  // ----- notifications -----
  function b64ToBytes(s) {
    var pad = "=".repeat((4 - s.length % 4) % 4);
    var raw = atob((s + pad).replace(/-/g, "+").replace(/_/g, "/"));
    var out = new Uint8Array(raw.length);
    for (var i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
    return out;
  }
  function sameKey(sub, key) {
    try {
      var a = new Uint8Array(sub.options.applicationServerKey), b = b64ToBytes(key);
      if (a.length !== b.length) return false;
      for (var i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
      return true;
    } catch (e) { return true; } // can't tell (old browser): assume fine
  }
  function post(path, body) {
    return fetch(path, { method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body || {}) })
      .then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (j) {
          if (!r.ok) { var e = new Error(j.error || ("HTTP " + r.status)); e.status = r.status; e.body = j; throw e; }
          return j;
        });
      });
  }
  function getCfg() {
    return fetch("/api/app/push/config", { credentials: "same-origin" }).then(function (r) { return r.ok ? r.json() : { available: false }; })
      .catch(function () { return { available: false }; }).then(function (c) { st.cfg = c; return c; });
  }
  function hideLocal() { try { return localStorage.getItem(HIDE_KEY) === "1"; } catch (e) { return false; } }
  function setHideLocal(v) { try { localStorage.setItem(HIDE_KEY, v ? "1" : "0"); } catch (e) {} }

  // Inside the Messages Enhanced Android app (TWA, or its Custom Tab fallback)
  // the launch referrer is android-app://<package>; remembered for the tab.
  var ANDROID_PKG = "com.ubermicrostudios.messagesenhanced";
  function inAndroidApp() {
    try {
      if (document.referrer.indexOf("android-app://" + ANDROID_PKG) === 0) sessionStorage.setItem("me_android_twa", "1");
      return sessionStorage.getItem("me_android_twa") === "1";
    } catch (e) { return false; }
  }
  function permission() { return pushOK ? Notification.permission : "unsupported"; }

  // Re-checks this device's subscription against the server (and re-sends
  // it, so the server list heals after a data reset or key change).
  function sync() {
    if (!pushOK) return Promise.resolve();
    return Promise.all([regReady, getCfg()]).then(function (v) {
      var reg = v[0], cfg = v[1];
      if (reg) st.reg = reg;
      if (!reg || !cfg.available) { st.sub = null; return; }
      return reg.pushManager.getSubscription().then(function (sub) {
        if (!sub) { st.sub = null; return; }
        if (!sameKey(sub, cfg.public_key)) { st.sub = null; return sub.unsubscribe(); } // server keys changed
        if (Notification.permission !== "granted") { st.sub = null; return; }
        return post("/api/app/push/subscribe", sub.toJSON()).then(function (res) {
          st.sub = sub; setHideLocal(!!res.hide_text);
        }).catch(function () { st.sub = null; });
      });
    });
  }

  function render() {
    var status = $("notifStatus");
    if (!status) return;
    var en = $("notifEnable"), dis = $("notifDisable"), hide = $("notifHide"), test = $("notifTest");
    var text, on = false, canEnable = false;
    var perm = permission(), app = inAndroidApp();
    if (!pushOK) {
      text = isIOS && !standalone
        ? "On iPhone or iPad, add Messages Enhanced to your Home Screen first (Share → Add to Home Screen), then turn notifications on from the installed app."
        : !window.isSecureContext ? "Needs a secure (https) connection."
        : "Not supported in this browser" + (isCar ? " (the car browser can't show notifications yet)." : ".");
    } else if (st.cfg && !st.cfg.available) {
      text = "Not available on this server.";
    } else if (perm === "denied") {
      text = app
        ? "Blocked: Android hasn't allowed notifications for the Messages Enhanced app. Tap “Allow notifications for the app” below, allow them, then come back here and tap Turn on."
        : "Blocked. Allow notifications for this site in the browser's site settings (tap the lock or ⓘ next to the address), then come back.";
    } else if (st.sub && perm === "granted") {
      on = true;
      text = "On. New messages notify this device, even with the page closed.";
    } else {
      canEnable = true;
      text = "Off. Get a notification for each new message, even with this page closed.";
    }
    if (st.busy && st.step) text = "Turning on\u2026 " + st.step + "\u2026";
    if (st.msg) text += " " + st.msg;
    status.textContent = text;
    $("notifRow").dataset.state = !pushOK ? "unsupported" : perm === "denied" ? "blocked" : on ? "enabled" : "off";
    en.hidden = !canEnable; en.disabled = st.busy;
    dis.hidden = !on; dis.disabled = st.busy;
    hide.hidden = !on; test.hidden = !on; test.disabled = st.busy;
    renderDebug();
    var appBtn = $("notifAppSettings");
    if (appBtn) appBtn.hidden = !(app && pushOK && !on && (perm === "denied" || st.needApp));
    var h = hideLocal();
    hide.classList.toggle("on", h);
    hide.setAttribute("aria-checked", h ? "true" : "false");
  }

  // Settings > Debug > Notification debug.
  function renderDebug() {
    var el = $("notifDebug");
    if (!el) return;
    var sw = "not supported";
    if (swOK) {
      var r = st.reg, w = r && (r.active || r.waiting || r.installing);
      sw = !r ? (st.swError ? "registration failed: " + st.swError : "not registered yet")
        : (w ? w.state : "no worker") + " · scope " + r.scope.replace(location.origin, "") +
          (navigator.serviceWorker.controller ? " · controls this page" : " · not controlling this page");
    }
    var host = "none";
    if (st.sub && st.sub.endpoint) { try { host = new URL(st.sub.endpoint).host; } catch (e) { host = "?"; } }
    var rows = [
      ["Web Push support", pushOK ? "yes" : "no (" + ["serviceWorker", "PushManager", "Notification"].filter(function (k) { return k === "serviceWorker" ? !swOK : !(k in window); }).join(", ") + " missing)"],
      ["Permission", permission()],
      ["Service worker", sw],
      ["Subscription", host],
      ["Server", st.cfg ? (st.cfg.available ? "push on · " + (st.cfg.devices || 0) + " device(s)" : "push off") : "not checked"],
      ["Android app", inAndroidApp() ? "yes" : "no"],
      ["Last tap", st.lastClick || "-"],
      ["Last error", st.lastError || "-"],
    ];
    el.textContent = "";
    rows.forEach(function (r) {
      var d = document.createElement("div"); d.className = "stt-info-row";
      var dt = document.createElement("dt"); dt.textContent = r[0];
      var dd = document.createElement("dd"); dd.textContent = r[1];
      d.appendChild(dt); d.appendChild(dd); el.appendChild(d);
    });
  }

  function refresh() {
    renderInstall();
    render();
    return sync().then(function () { render(); }, function () { render(); });
  }

  // Each step of turning notifications on is time-limited and named, so a
  // hang or failure always ends with the exact step and error under the
  // button (and in Settings > Debug > Notification debug).
  function step(label, fn, ms) {
    st.step = label; render();
    return new Promise(function (resolve, reject) {
      var t = setTimeout(function () { reject(new Error(label + ": no answer after " + Math.round(ms / 1000) + " s")); }, ms);
      Promise.resolve().then(fn).then(function (v) { clearTimeout(t); resolve(v); }, function (e) { clearTimeout(t); reject(e); });
    });
  }
  function errText(e) {
    if (!e) return "unknown error";
    var m = e.message || String(e);
    if (e.name && e.name !== "Error" && m.indexOf(e.name) < 0) m = e.name + ": " + (m || "no details");
    if (e.name === "AbortError" && /push service/i.test(m)) m += " (the browser couldn't reach its push service; check that Chrome is up to date, has network access and isn't restricted in battery settings)";
    if (e.name === "NotAllowedError" || /permission denied/i.test(m)) m += inAndroidApp()
      ? " (Chrome refused: check the app's notification permission, and Chrome's own notification setting for this site)"
      : " (allow notifications for this site in the browser's site settings)";
    return m || "unknown error";
  }
  function enable() {
    if (st.busy) return;
    st.lastClick = new Date().toLocaleTimeString();
    if (!pushOK) { st.msg = "Couldn't turn on: this browser has no Web Push support."; st.lastError = st.msg; render(); return; }
    st.busy = true; st.msg = ""; st.lastError = ""; render();
    // Ask for permission only here, on the user's tap.
    step("Asking for notification permission", function () {
      if (Notification.permission === "granted") return "granted";
      return new Promise(function (res) { var p = Notification.requestPermission(res); if (p && p.then) p.then(res); });
    }, 60000).then(function (perm) {
      if (perm !== "granted") {
        st.needApp = inAndroidApp();
        throw new Error(perm === "denied"
          ? "Notification permission is blocked" + (st.needApp ? " for the app. Tap \u201cAllow notifications for the app\u201d below." : " for this site.")
          : st.needApp ? "Android didn't grant notification permission to the app. Tap \u201cAllow notifications for the app\u201d below."
          : "Permission wasn't granted (the prompt was dismissed or blocked).");
      }
      st.needApp = false;
      return step("Starting the service worker", function () {
        return regReady.then(function (r) {
          if (!r) throw new Error("The service worker didn't register" + (st.swError ? ": " + st.swError : "."));
          return navigator.serviceWorker.ready;
        });
      }, 20000);
    }).then(function (reg) {
      st.reg = reg;
      return step("Getting the server's push key", function () {
        return st.cfg && st.cfg.public_key ? st.cfg : getCfg();
      }, 15000).then(function (cfg) {
        if (!cfg.available) throw new Error("Not available on this server" + (cfg.error ? " (" + cfg.error + ")" : "."));
        if (!cfg.public_key) throw new Error("The server sent no push key.");
        return step("Checking this device's subscription", function () { return reg.pushManager.getSubscription(); }, 15000).then(function (old) {
          if (old && !sameKey(old, cfg.public_key)) return old.unsubscribe().then(function () { return null; }, function () { return null; });
          return old;
        }).then(function (old) {
          return old || step("Subscribing with the browser's push service", function () {
            return reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: b64ToBytes(cfg.public_key) });
          }, 45000);
        });
      });
    }).then(function (sub) {
      var body = sub.toJSON(); body.hide_text = hideLocal();
      return step("Saving on the server", function () { return post("/api/app/push/subscribe", body); }, 20000).then(function (res) {
        st.sub = sub; setHideLocal(!!res.hide_text); st.msg = "";
      });
    }).catch(function (e) {
      st.lastError = (st.step ? st.step + ": " : "") + errText(e);
      st.msg = "Couldn't turn on. " + st.lastError;
      if (window.console) console.warn("notifications:", e);
    }).then(function () { st.busy = false; st.step = ""; render(); });
  }

  function disable() {
    if (st.busy) return;
    st.busy = true; render();
    var sub = st.sub;
    var done = function () { st.sub = null; st.busy = false; st.msg = ""; render(); };
    if (!sub) { done(); return; }
    post("/api/app/push/unsubscribe", { endpoint: sub.endpoint }).catch(function () {})
      .then(function () { return sub.unsubscribe().catch(function () {}); }).then(done);
  }

  function toggleHide() {
    var v = !hideLocal();
    setHideLocal(v); render();
    if (!st.sub) return;
    post("/api/app/push/settings", { endpoint: st.sub.endpoint, hide_text: v }).catch(function (e) {
      setHideLocal(!v); st.msg = "Couldn't save: " + e.message; render();
    });
  }

  function test() {
    if (!st.sub || st.busy) return;
    st.busy = true; render();
    var d = $("notifTestDesc");
    post("/api/app/push/test", { endpoint: st.sub.endpoint }).then(function () {
      d.textContent = "Sent. It should appear in a few seconds.";
    }).catch(function (e) {
      d.textContent = "Couldn't send: " + e.message;
      if (e.status === 404 || e.status === 410) st.sub = null;
    }).then(function () { st.busy = false; render(); });
  }

  function init() {
    if ($("notifEnable")) {
      $("notifEnable").addEventListener("click", enable);
      $("notifDisable").addEventListener("click", disable);
      $("notifHide").addEventListener("click", toggleHide);
      $("notifTest").addEventListener("click", test);
      $("installBtn").addEventListener("click", onInstall);
      render();
      renderInstall();
      // Quietly keep an existing subscription registered with the server.
      if (pushOK && Notification.permission === "granted") sync().then(render, render);
      // Back from Android's notification settings / permission prompt.
      document.addEventListener("visibilitychange", function () {
        if (document.visibilityState === "visible") { if (permission() === "granted") st.needApp = false; refresh(); }
      });
    }
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init); else init();
})();
