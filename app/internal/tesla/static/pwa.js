/* Tesla Messages: service worker registration, "Install app" and Web Push
 * notifications (Settings). Everything degrades quietly: the Tesla browser
 * has no Push API, so it just shows "not supported". Loaded on the car page
 * and the login page (where only the service worker part runs). */
(function () {
  "use strict";
  var $ = function (id) { return document.getElementById(id); };
  var ua = navigator.userAgent || "";
  var isIOS = /iPhone|iPad|iPod/.test(ua) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
  var isTesla = /Tesla/.test(ua);
  var standalone = (window.matchMedia && window.matchMedia("(display-mode: standalone)").matches) || navigator.standalone === true;
  var swOK = "serviceWorker" in navigator && window.isSecureContext;
  var pushOK = swOK && "PushManager" in window && "Notification" in window && "showNotification" in (window.ServiceWorkerRegistration ? ServiceWorkerRegistration.prototype : {});
  var HIDE_KEY = "tm.notifHideText";
  var st = { reg: null, cfg: null, sub: null, busy: false, prompt: null, msg: "" };
  window.TMPWA = { refresh: refresh, state: st };

  var regReady = swOK ? navigator.serviceWorker.register("/tesla/sw.js", { scope: "/tesla/" }).then(function (r) { st.reg = r; return r; })
    .catch(function (e) { st.swError = String(e && e.message || e); return null; }) : Promise.resolve(null);

  if (swOK) {
    navigator.serviceWorker.addEventListener("message", function (e) {
      var d = e.data || {};
      if (d.type === "open-conversation" && d.conv) window.dispatchEvent(new CustomEvent("tm-open-conversation", { detail: { conv: d.conv } }));
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
    else if (st.installed) text = "Installed. Open Tesla Messages from your home screen or app list.";
    else if (!st.prompt && isIOS) text = "On iPhone or iPad: tap the Share button in Safari, then “Add to Home Screen”. Open it from there to turn on notifications.";
    else if (!st.prompt && !isTesla && "onbeforeinstallprompt" in window) text = "To install, use the browser menu → “Install app” or “Add to Home screen”.";
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
    return fetch("/api/tesla/push/config", { credentials: "same-origin" }).then(function (r) { return r.ok ? r.json() : { available: false }; })
      .catch(function () { return { available: false }; }).then(function (c) { st.cfg = c; return c; });
  }
  function hideLocal() { try { return localStorage.getItem(HIDE_KEY) === "1"; } catch (e) { return false; } }
  function setHideLocal(v) { try { localStorage.setItem(HIDE_KEY, v ? "1" : "0"); } catch (e) {} }

  function permission() { return pushOK ? Notification.permission : "unsupported"; }

  // Re-checks this device's subscription against the server (and re-sends
  // it, so the server list heals after a data reset or key change).
  function sync() {
    if (!pushOK) return Promise.resolve();
    return Promise.all([regReady, getCfg()]).then(function (v) {
      var reg = v[0], cfg = v[1];
      if (!reg || !cfg.available) { st.sub = null; return; }
      return reg.pushManager.getSubscription().then(function (sub) {
        if (!sub) { st.sub = null; return; }
        if (!sameKey(sub, cfg.public_key)) { st.sub = null; return sub.unsubscribe(); } // server keys changed
        if (Notification.permission !== "granted") { st.sub = null; return; }
        return post("/api/tesla/push/subscribe", sub.toJSON()).then(function (res) {
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
    var perm = permission();
    if (!pushOK) {
      text = isIOS && !standalone
        ? "On iPhone or iPad, add Tesla Messages to your Home Screen first (Share → Add to Home Screen), then turn notifications on from the installed app."
        : !window.isSecureContext ? "Needs a secure (https) connection."
        : "Not supported in this browser" + (isTesla ? " (the Tesla browser can't show notifications yet)." : ".");
    } else if (st.cfg && !st.cfg.available) {
      text = "Not available on this server.";
    } else if (perm === "denied") {
      text = "Blocked. Allow notifications for this site in the browser's site settings (tap the lock or ⓘ next to the address), then come back.";
    } else if (st.sub && perm === "granted") {
      on = true;
      text = "On. New messages notify this device, even with the page closed.";
    } else {
      canEnable = true;
      text = "Off. Get a notification for each new message, even with this page closed.";
    }
    if (st.msg) text += " " + st.msg;
    status.textContent = text;
    $("notifRow").dataset.state = !pushOK ? "unsupported" : perm === "denied" ? "blocked" : on ? "enabled" : "off";
    en.hidden = !canEnable; en.disabled = st.busy;
    dis.hidden = !on; dis.disabled = st.busy;
    hide.hidden = !on; test.hidden = !on; test.disabled = st.busy;
    var h = hideLocal();
    hide.classList.toggle("on", h);
    hide.setAttribute("aria-checked", h ? "true" : "false");
  }

  function refresh() {
    renderInstall();
    render();
    return sync().then(function () { render(); }, function () { render(); });
  }

  function enable() {
    if (!pushOK || st.busy) return;
    st.busy = true; st.msg = ""; render();
    // Ask for permission only here, on the user's tap.
    var ask = Notification.permission === "granted" ? Promise.resolve("granted")
      : new Promise(function (res) { var p = Notification.requestPermission(res); if (p && p.then) p.then(res); });
    ask.then(function (perm) {
      if (perm !== "granted") throw new Error(perm === "denied" ? "" : "Permission wasn't granted.");
      return Promise.all([regReady.then(function (r) { return r ? navigator.serviceWorker.ready : null; }), st.cfg && st.cfg.public_key ? st.cfg : getCfg()]);
    }).then(function (v) {
      var reg = v[0], cfg = v[1];
      if (!reg) throw new Error("The service worker didn't start" + (st.swError ? ": " + st.swError : "."));
      if (!cfg.available) throw new Error("Not available on this server.");
      return reg.pushManager.getSubscription().then(function (old) {
        if (old && !sameKey(old, cfg.public_key)) return old.unsubscribe().then(function () { return null; });
        return old;
      }).then(function (old) {
        return old || reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: b64ToBytes(cfg.public_key) });
      });
    }).then(function (sub) {
      var body = sub.toJSON(); body.hide_text = hideLocal();
      return post("/api/tesla/push/subscribe", body).then(function (res) { st.sub = sub; setHideLocal(!!res.hide_text); st.msg = ""; });
    }).catch(function (e) {
      st.msg = e && e.message ? "Couldn't turn on: " + e.message : "";
    }).then(function () { st.busy = false; render(); });
  }

  function disable() {
    if (st.busy) return;
    st.busy = true; render();
    var sub = st.sub;
    var done = function () { st.sub = null; st.busy = false; st.msg = ""; render(); };
    if (!sub) { done(); return; }
    post("/api/tesla/push/unsubscribe", { endpoint: sub.endpoint }).catch(function () {})
      .then(function () { return sub.unsubscribe().catch(function () {}); }).then(done);
  }

  function toggleHide() {
    var v = !hideLocal();
    setHideLocal(v); render();
    if (!st.sub) return;
    post("/api/tesla/push/settings", { endpoint: st.sub.endpoint, hide_text: v }).catch(function (e) {
      setHideLocal(!v); st.msg = "Couldn't save: " + e.message; render();
    });
  }

  function test() {
    if (!st.sub || st.busy) return;
    st.busy = true; render();
    var d = $("notifTestDesc");
    post("/api/tesla/push/test", { endpoint: st.sub.endpoint }).then(function () {
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
    }
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init); else init();
})();
