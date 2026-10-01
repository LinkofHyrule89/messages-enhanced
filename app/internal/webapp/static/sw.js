/* Messages Enhanced service worker (scope /app/).
 *
 * Caching policy (auth-safe): only static shell files under /app/ (js, css,
 * fonts, icons, the offline page) are ever cached. Never /api/, /login,
 * /logout, /admin, the HTML page itself, or anything not a plain 200
 * same-origin response. Navigations go to the network and fall back to the
 * offline page. Push messages become notifications grouped per conversation.
 */
"use strict";
var VERSION = "tm-shell-v1";
var PRECACHE = ["/app/offline.html", "/app/icons/icon-192.png", "/app/icons/badge-96.png"];

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
  if (p.indexOf("/app/fonts/") === 0 || p.indexOf("/app/icons/") === 0) return "immutable";
  if (/^\/app\/[\w.-]+\.(js|css)$/.test(p) && p !== "/app/sw.js") return "shell";
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
    e.respondWith(fetch(req).catch(function () { return caches.match("/app/offline.html"); }));
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
  var title = d.title || "Messages Enhanced";
  var opts = {
    body: d.body || "New message",
    tag: d.tag || "messages-enhanced",
    renotify: true,
    icon: "/app/icons/icon-192.png",
    badge: "/app/icons/badge-96.png",
    timestamp: d.ts || Date.now(),
    data: { url: d.url || "/app/", conv: d.conv || "" }
  };
  if (d.conv && !d.test) {
    // Chrome Android shows an inline text box for type:"text"; elsewhere
    // the Reply action just opens the chat with the composer focused.
    opts.actions = [
      { action: "reply", title: "Reply", type: "text", placeholder: "Reply…" },
      { action: "read", title: "Mark as read" }
    ];
  }
  e.waitUntil(self.registration.showNotification(title, opts));
});

function postJSON(path, body) {
  return fetch(path, {
    method: "POST", credentials: "same-origin",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  }).then(function (r) { if (!r.ok) throw new Error(path + " " + r.status); return r; });
}

function openConv(data, reply) {
  var u = new URL(data.url || "/app/", self.location.origin);
  if (reply) u.searchParams.set("reply", "1");
  return self.clients.matchAll({ type: "window", includeUncontrolled: true }).then(function (list) {
    for (var i = 0; i < list.length; i++) {
      var c = list[i];
      if (new URL(c.url).pathname.indexOf("/app/") === 0 && "focus" in c) {
        if (data.conv) c.postMessage({ type: "open-conversation", conv: data.conv, reply: !!reply });
        return c.focus();
      }
    }
    return self.clients.openWindow(u.href);
  });
}

function rid() {
  return "sw-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 10);
}

self.addEventListener("notificationclick", function (e) {
  var n = e.notification, data = n.data || {};
  n.close();
  if (e.action === "read" && data.conv) {
    e.waitUntil(postJSON("/api/app/conversations/read", { conversation_id: data.conv }).catch(function () {}));
    return;
  }
  if (e.action === "reply" && data.conv) {
    var text = (e.reply || "").trim();
    if (!text) { e.waitUntil(openConv(data, true)); return; }
    e.waitUntil(postJSON("/api/send", { conversation_id: data.conv, message: text, idempotency_key: rid() })
      .then(function () {
        return self.registration.showNotification(n.title || "Messages Enhanced", {
          body: "Sent: " + text, tag: n.tag || "messages-enhanced", renotify: false, silent: true,
          icon: "/app/icons/icon-192.png", badge: "/app/icons/badge-96.png", data: data
        }).then(function () {
          // Clear the "Sent" confirmation after a moment.
          return new Promise(function (res) { setTimeout(res, 4000); });
        }).then(function () {
          return self.registration.getNotifications({ tag: n.tag || "messages-enhanced" });
        }).then(function (ns) { ns.forEach(function (x) { if (x.body && x.body.indexOf("Sent: ") === 0) x.close(); }); });
      })
      .catch(function () { return openConv(data, true); }));
    return;
  }
  e.waitUntil(openConv(data, false));
});

// The browser may rotate a subscription; re-register it with the server.
self.addEventListener("pushsubscriptionchange", function (e) {
  var opts = e.oldSubscription && e.oldSubscription.options;
  if (!opts || !opts.applicationServerKey) return;
  e.waitUntil(self.registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: opts.applicationServerKey }).then(function (sub) {
    return fetch("/api/app/push/subscribe", { method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json" }, body: JSON.stringify(sub.toJSON()) });
  }).catch(function () {}));
});
