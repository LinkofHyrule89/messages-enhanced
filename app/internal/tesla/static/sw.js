/* Tesla Messages service worker (scope /tesla/).
 *
 * Caching policy (auth-safe): only static shell files under /tesla/ (js, css,
 * fonts, icons, the offline page) are ever cached. Never /api/, /login,
 * /logout, /admin, the HTML page itself, or anything not a plain 200
 * same-origin response. Navigations go to the network and fall back to the
 * offline page. Push messages become notifications grouped per conversation.
 */
"use strict";
var VERSION = "tm-shell-v1";
var PRECACHE = ["/tesla/offline.html", "/tesla/icons/icon-192.png", "/tesla/icons/badge-96.png"];

self.addEventListener("install", function (e) {
  e.waitUntil(caches.open(VERSION).then(function (c) { return c.addAll(PRECACHE); }).then(function () { return self.skipWaiting(); }));
});

self.addEventListener("activate", function (e) {
  e.waitUntil(caches.keys().then(function (keys) {
    return Promise.all(keys.filter(function (k) { return k.indexOf("tm-") === 0 && k !== VERSION; }).map(function (k) { return caches.delete(k); }));
  }).then(function () { return self.clients.claim(); }));
});

function never(url) {
  var p = url.pathname;
  return p.indexOf("/api/") === 0 || p === "/login" || p === "/logout" || p.indexOf("/admin") === 0 || p.indexOf("/mcp") === 0;
}
function staticKind(url) {
  var p = url.pathname;
  if (p.indexOf("/tesla/fonts/") === 0 || p.indexOf("/tesla/icons/") === 0) return "immutable";
  if (/^\/tesla\/[\w.-]+\.(js|css)$/.test(p) && p !== "/tesla/sw.js") return "shell";
  return "";
}
function cacheable(res) {
  return res && res.status === 200 && res.type === "basic" && !res.redirected;
}
function put(req, res) {
  if (!cacheable(res)) return;
  var copy = res.clone();
  caches.open(VERSION).then(function (c) { return c.put(req, copy); });
}

self.addEventListener("fetch", function (e) {
  var req = e.request;
  if (req.method !== "GET") return;
  var url = new URL(req.url);
  if (url.origin !== self.location.origin || never(url)) return; // browser default, no cache

  if (req.mode === "navigate") {
    // Pages are behind the login (redirects, per-user HTML): never cached.
    e.respondWith(fetch(req).catch(function () { return caches.match("/tesla/offline.html"); }));
    return;
  }
  var kind = staticKind(url);
  if (kind === "immutable") {
    e.respondWith(caches.match(req).then(function (hit) {
      return hit || fetch(req).then(function (res) { put(req, res); return res; });
    }));
  } else if (kind === "shell") {
    // The server says no-cache for these: network first, cache only as an
    // offline fallback.
    e.respondWith(fetch(req).then(function (res) { put(req, res); return res; }).catch(function () {
      return caches.match(req).then(function (hit) { return hit || Response.error(); });
    }));
  }
});

self.addEventListener("push", function (e) {
  var d = {};
  try { d = e.data ? e.data.json() : {}; } catch (err) { d = { body: e.data ? e.data.text() : "" }; }
  var title = d.title || "Tesla Messages";
  var opts = {
    body: d.body || "New message",
    tag: d.tag || "tesla-messages",
    renotify: true,
    icon: "/tesla/icons/icon-192.png",
    badge: "/tesla/icons/badge-96.png",
    timestamp: d.ts || Date.now(),
    data: { url: d.url || "/tesla/", conv: d.conv || "" }
  };
  e.waitUntil(self.registration.showNotification(title, opts));
});

self.addEventListener("notificationclick", function (e) {
  e.notification.close();
  var data = e.notification.data || {};
  var target = new URL(data.url || "/tesla/", self.location.origin).href;
  e.waitUntil(self.clients.matchAll({ type: "window", includeUncontrolled: true }).then(function (list) {
    for (var i = 0; i < list.length; i++) {
      var c = list[i];
      if (new URL(c.url).pathname.indexOf("/tesla/") === 0 && "focus" in c) {
        if (data.conv) c.postMessage({ type: "open-conversation", conv: data.conv });
        return c.focus();
      }
    }
    return self.clients.openWindow(target);
  }));
});

// The browser may rotate a subscription; re-register it with the server.
self.addEventListener("pushsubscriptionchange", function (e) {
  var opts = e.oldSubscription && e.oldSubscription.options;
  if (!opts || !opts.applicationServerKey) return;
  e.waitUntil(self.registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: opts.applicationServerKey }).then(function (sub) {
    return fetch("/api/tesla/push/subscribe", { method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json" }, body: JSON.stringify(sub.toJSON()) });
  }).catch(function () {}));
});
