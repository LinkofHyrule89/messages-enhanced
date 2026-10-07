/* Messages Enhanced: car-friendly client for the OpenMessage API.
   Plain JS, no build step, no hover-dependent UI. */
(function () {
  "use strict";

  var $ = function (id) { return document.getElementById(id); };
  var state = {
    convs: [],
    current: null,        // conversation id
    config: { stt_mode: "auto", stt_enabled: false, max_record_secs: 60 },
    rec: null,            // active recording
    transcribing: false,
    pairPoll: null,
    sendingMedia: false,
    replyTo: null,        // { MessageID, SenderName, Body } while replying
    nodes: {},            // per-thread DOM reused across re-renders (videos, link cards)
    folder: null,         // { name, label, loading, error } while browsing a Google folder (view only)
    typing: {},           // conv id -> { sender key -> { name, expires } }
    menuFor: null,        // { msg, btn } while the message ⋮ menu is open
    confirmFor: null,     // message awaiting "Delete this message?"
    convMenuFor: null,    // { conv, btn } while the conversation menu is open
  };

  // ---------- helpers ----------
  function api(path, opts) {
    opts = opts || {};
    opts.credentials = "same-origin";
    opts.headers = opts.headers || {};
    return fetch(path, opts).then(function (r) {
      if (r.status === 401) { location.href = "/login?next=" + encodeURIComponent(location.pathname + location.hash); throw new Error("login required"); }
      var ct = r.headers.get("content-type") || "";
      var body = ct.indexOf("json") >= 0 ? r.json() : r.text();
      return body.then(function (b) {
        if (!r.ok) { throw new Error((b && b.error) || (typeof b === "string" && b.trim()) || ("HTTP " + r.status)); }
        return b;
      });
    });
  }
  function postJSON(path, obj) {
    return api(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(obj || {}) });
  }
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  // ---------- icons ----------
  // Every icon is inline SVG (Material Design paths, 24x24): the car
  // browser has a color-emoji font but its text font lacks many symbol
  // glyphs (U+22EE "⋮", check marks, arrows...), which render as empty boxes.
  // Markup uses <span class="ico" data-icon="name"></span>; hydrateIcons()
  // fills them in, icon(name) builds one in JS.
  var ICONS = {
    more: "M12 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm0 2a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm0 6a2 2 0 1 0 0 4 2 2 0 0 0 0-4z",
    check: "M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
    close: "M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
    back: "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z",
    chevLeft: "M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z",
    chevRight: "M10 6 8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z",
    minus: "M19 13H5v-2h14v2z",
    plus: "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
    palette: "M12 3a9 9 0 0 0 0 18c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8zm-5.5 9a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm3-4a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm5 0a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm3 4a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3z",
    chat: "M20 2H4a2 2 0 0 0-2 2v18l4-4h14a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2z",
    attach: "M16.5 6.5v10a4.5 4.5 0 0 1-9 0V5a3 3 0 0 1 6 0v10.5a1.5 1.5 0 0 1-3 0V6.5H9v9a3 3 0 0 0 6 0V5a4.5 4.5 0 0 0-9 0v11.5a6 6 0 0 0 12 0v-10h-1.5z",
    photo: "M21 19V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z",
    video: "M17 10.5V7a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-3.5l4 4v-11l-4 4z",
    mic: "M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.92V21h2v-3.08A7 7 0 0 0 19 11h-2z",
    mail: "M20 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2zm0 4-8 5-8-5V6l8 5 8-5v2z",
    group: "M16 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6zm-8 0a3 3 0 1 0 0-6 3 3 0 0 0 0 6zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z",
    trash: "M6 19a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z",
    info: "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z",
    unarchive: "M20.55 5.22l-1.39-1.68A1.45 1.45 0 0 0 18 3H6c-.47 0-.88.21-1.17.55L3.46 5.22C3.17 5.57 3 6.01 3 6.5V19c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V6.5c0-.49-.17-.93-.45-1.28zM12 9.5l5.5 5.5H14v2h-4v-2H6.5L12 9.5zM5.12 5l.82-1h12l.93 1H5.12z",
    archive: "M20.54 5.23l-1.39-1.68A1.45 1.45 0 0 0 18 3H6c-.47 0-.88.21-1.16.55L3.46 5.23C3.17 5.57 3 6.02 3 6.5V19a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6.5c0-.48-.17-.93-.46-1.27zM12 17.5 6.5 12H10v-2h4v2h3.5L12 17.5zM5.12 5l.81-1h12l.94 1H5.12z",
    warning: "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z",
    block: "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zM4 12a8 8 0 0 1 12.9-6.31L5.69 16.9A7.9 7.9 0 0 1 4 12zm8 8a7.9 7.9 0 0 1-4.9-1.69L18.31 7.1A8 8 0 0 1 12 20z",
    logout: "M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5zM4 5h8V3H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h8v-2H4V5z",
    cookie: "M21.95 10.99c-1.79-.03-3.7-1.95-2.68-4.22-2.97 1-5.78-1.59-5.19-4.56C7.11.74 2 6.41 2 12a10 10 0 0 0 10 10c5.89 0 10.54-5.08 9.95-11.01zM8.5 15a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm2-5a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm4.5 6a1 1 0 1 1 0-2 1 1 0 0 1 0 2z",
    emoji: "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20a8 8 0 1 1 0-16 8 8 0 0 1 0 16zm3.5-9a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3zm-7 0a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3zm3.5 6.5c2.33 0 4.31-1.46 5.11-3.5H6.89c.8 2.04 2.78 3.5 5.11 3.5z",
    clock: "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20a8 8 0 1 1 0-16 8 8 0 0 1 0 16zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67z",
    bell: "M12 22c1.1 0 2-.9 2-2h-4a2 2 0 0 0 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4a1.5 1.5 0 0 0-3 0v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z",
    send: "M2.01 21 23 12 2.01 3 2 10l15 2-15 2z",
    install: "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z",
    refresh: "M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z",
    gear: "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96a7 7 0 0 0-1.62-.94l-.36-2.54a.48.48 0 0 0-.48-.41h-3.84a.47.47 0 0 0-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.48.48 0 0 0-.59.22L2.74 8.87a.47.47 0 0 0 .12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32a.48.48 0 0 0-.12-.61l-2.01-1.58zM12 15.6a3.6 3.6 0 1 1 0-7.2 3.6 3.6 0 0 1 0 7.2z",
  };
  function icon(name, cls) {
    var ns = "http://www.w3.org/2000/svg";
    var svg = document.createElementNS(ns, "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("aria-hidden", "true");
    svg.setAttribute("focusable", "false");
    svg.setAttribute("class", "ico-svg" + (cls ? " " + cls : ""));
    var p = document.createElementNS(ns, "path");
    p.setAttribute("d", ICONS[name] || "");
    svg.appendChild(p);
    return svg;
  }
  // An element with an icon followed by text (no symbol characters).
  function iconEl(tag, cls, name, text) {
    var e = el(tag, cls);
    e.appendChild(icon(name));
    if (text) e.appendChild(el("span", null, text));
    return e;
  }
  function hydrateIcons(root) {
    Array.prototype.forEach.call((root || document).querySelectorAll("[data-icon]"), function (n) {
      if (n.firstChild && n.firstChild.nodeName.toLowerCase() === "svg") return;
      n.textContent = "";
      n.appendChild(icon(n.getAttribute("data-icon")));
    });
  }
  // Spaces the car's text font may not have: Chrome's time format puts a
  // NARROW NO-BREAK SPACE (U+202F) before AM/PM, which showed as a box under
  // messages. Also used on link-preview text from other sites.
  function fontSafe(str) {
    return String(str == null ? "" : str)
      .replace(/[\u2000-\u200A\u202F\u205F\u3000]/g, " ")
      .replace(/[\u200B-\u200D\u2060\uFEFF]/g, "")
      .replace(/[\u2010\u2011\u2012\u2212]/g, "-");
  }
  function clockTime(ms, withSeconds) {
    var o = { hour: "numeric", minute: "2-digit" };
    if (withSeconds) o.second = "2-digit";
    return fontSafe(new Date(ms).toLocaleTimeString([], o));
  }

  var toastTimer = null;
  function toast(msg, kind) {
    var t = $("toast");
    t.textContent = msg;
    t.className = "toast" + (kind ? " toast-" + kind : "");
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.hidden = true; }, kind === "error" ? (msg.length > 110 ? 11000 : 6000) : 3500);
  }
  function initials(name) {
    var parts = String(name || "?").replace(/[^\p{L}\p{N} ]/gu, "").trim().split(/\s+/);
    var s = (parts[0] || "?").charAt(0) + (parts.length > 1 ? parts[parts.length - 1].charAt(0) : "");
    return s.toUpperCase() || "#";
  }
  function hue(str) {
    var h = 0; str = String(str || "");
    for (var i = 0; i < str.length; i++) h = (h * 31 + str.charCodeAt(i)) % 360;
    return h;
  }
  function fmtTime(ms) {
    if (!ms) return "";
    var d = new Date(ms), now = new Date();
    if (d.toDateString() === now.toDateString()) return clockTime(ms);
    var y = new Date(now); y.setDate(now.getDate() - 1);
    if (d.toDateString() === y.toDateString()) return "Yesterday";
    if (now - d < 6 * 864e5) return fontSafe(d.toLocaleDateString([], { weekday: "short" }));
    return fontSafe(d.toLocaleDateString([], { month: "short", day: "numeric" }));
  }
  function dayLabel(ms) {
    var d = new Date(ms), now = new Date(), y = new Date(now);
    y.setDate(now.getDate() - 1);
    if (d.toDateString() === now.toDateString()) return "Today";
    if (d.toDateString() === y.toDateString()) return "Yesterday";
    return fontSafe(d.toLocaleDateString([], { weekday: "long", month: "short", day: "numeric" }));
  }
  function convName(c) { return c.unified_name || c.Name || c.ConversationID; }
  var PLATFORM = { whatsapp: "WhatsApp", signal: "Signal", gchat: "Chat", imessage: "iMessage", telegram: "Telegram" };

  // ---------- contact photos ----------
  // Mirrors the desktop page (internal/web/static/index.html:
  // conversationParticipants / conversationAvatarRefs / googleAvatarLookupKey /
  // fetchGoogleCachedAvatar): Google Messages (sms) conversations only, photos
  // from GET /api/avatar?source=sms&participant_id=&contact_id=&phone=
  // (404 = no photo). Results, including "no photo", are cached in memory by
  // lookup key so the periodic list re-renders neither refetch nor flicker.
  var AVATAR_MAX_INFLIGHT = 4;
  var AVATAR_NONE_TTL = 15 * 60 * 1000;  // 404 / empty image: re-check after 15 min
  var AVATAR_ERROR_TTL = 2 * 60 * 1000;  // network / 5xx / 401: re-check after 2 min
  var avatarCache = {};                  // key -> { url, pending, expires, waiters }
  var avatarQueue = [], avatarInflight = 0;

  function conversationParticipants(c) {
    if (!c) return [];
    var raw = c.Participants || c.participants;
    if (!raw) return [];
    if (Array.isArray(raw)) return raw;
    try { var parsed = JSON.parse(raw); return Array.isArray(parsed) ? parsed : []; } catch (e) { return []; }
  }
  function isMe(p) { return !!(p && (p.is_me || p.isMe)); }
  function sourcePlatformOf(c) { return String((c && (c.SourcePlatform || c.source_platform)) || "sms").trim().toLowerCase() || "sms"; }
  function normalizeParticipantIdentifier(v) {
    var raw = String(v || "").trim().toLowerCase();
    if (!raw) return "";
    var digits = raw.replace(/\D+/g, "");
    if (digits.length >= 7) return digits;
    return raw.replace(/\s+/g, " ");
  }
  function uniqueStrings(values) {
    var seen = {}, out = [];
    (values || []).forEach(function (v) {
      var raw = String(v || "").trim();
      if (!raw || seen["$" + raw]) return;
      seen["$" + raw] = 1; out.push(raw);
    });
    return out;
  }
  function uniqueNumbers(values) {
    var seen = {}, out = [];
    (values || []).forEach(function (v) {
      var raw = String(v || "").trim(), key = normalizeParticipantIdentifier(raw);
      if (!raw || !key || seen["$" + key]) return;
      seen["$" + key] = 1; out.push(raw);
    });
    return out;
  }
  // refs for a set of participants: { source, participantIDs, contactIDs, numbers }
  function refsFor(people) {
    var ids = [], cids = [], nums = [];
    people.forEach(function (p) {
      if (!p) return;
      ids.push(p.id || p.participant_id || p.participantId);
      cids.push(p.contact_id || p.contactId);
      nums.push(p.number); nums.push(p.phone);
    });
    return { source: "sms", participantIDs: uniqueStrings(ids), contactIDs: uniqueStrings(cids), numbers: uniqueNumbers(nums) };
  }
  function avatarLookupKey(r) {
    var src = String(r.source || "").trim().toLowerCase();
    var parts = uniqueStrings(r.participantIDs).join("|");
    var contacts = uniqueStrings(r.contactIDs).join("|");
    var phones = uniqueNumbers(r.numbers).map(normalizeParticipantIdentifier).sort().join("|");
    if (!src || (!parts && !contacts && !phones)) return "";
    return src + "::" + parts + "::" + contacts + "::" + phones;
  }
  // Lookups to show for a conversation: one for a 1:1 chat (the other person,
  // exactly like the desktop page). For a group: first the group's own icon
  // (cached server-side under participant_id "conv:<conversation id>"), then
  // one per non-me member (up to 6), of which the first two with photos are
  // shown when there's no group icon.
  function avatarLookups(c) {
    if (!c || sourcePlatformOf(c) !== "sms") return [];
    var all = conversationParticipants(c);
    var others = c.IsGroup ? currentMembers(c).others : all.filter(function (p) { return p && !isMe(p); });
    var list = [];
    if (c.IsGroup) {
      var g = { source: "sms", participantIDs: ["conv:" + c.ConversationID], contactIDs: [], numbers: [], group: true };
      g.key = avatarLookupKey(g);
      list.push(g);
      others.forEach(function (p) {
        if (list.length >= 6) return;
        var r = refsFor([p]); r.key = avatarLookupKey(r);
        if (r.key && !list.some(function (x) { return x.key === r.key; })) list.push(r);
      });
    } else {
      var r1 = refsFor(others.length ? others : all); r1.key = avatarLookupKey(r1);
      if (r1.key) list.push(r1);
    }
    return list;
  }
  function avatarCached(key) {
    var e = avatarCache[key];
    if (!e || e.pending) return undefined;
    if (e.url) return e.url;
    return Date.now() < e.expires ? null : undefined;
  }
  function avatarSettle(key, url, ttl) {
    var e = avatarCache[key];
    var waiters = (e && e.waiters) || [];
    avatarCache[key] = { url: url || null, pending: false, expires: url ? Infinity : Date.now() + ttl, waiters: [] };
    waiters.forEach(function (fn) { try { fn(); } catch (x) {} });
  }
  function avatarPump() {
    while (avatarInflight < AVATAR_MAX_INFLIGHT && avatarQueue.length) {
      (function (r) {
        avatarInflight++;
        // v=2: busts browser-cached responses from before the participant-ID
        // collision fix (they were served with max-age=86400).
        var q = "v=2&source=" + encodeURIComponent(r.source) + (syncState.avatarVersion ? "&av=" + syncState.avatarVersion : "");
        if (r.participantIDs[0]) q += "&participant_id=" + encodeURIComponent(r.participantIDs[0]);
        if (r.contactIDs[0]) q += "&contact_id=" + encodeURIComponent(r.contactIDs[0]);
        if (r.numbers[0]) q += "&phone=" + encodeURIComponent(r.numbers[0]);
        // Same auth as api(): the session cookie, same-origin. A 401 is
        // left to api() (which redirects to /login) and just retried later here.
        fetch("/api/avatar?" + q, { credentials: "same-origin" }).then(function (resp) {
          if (resp.status === 404) { avatarSettle(r.key, null, AVATAR_NONE_TTL); return; }
          if (!resp.ok) { avatarSettle(r.key, null, AVATAR_ERROR_TTL); return; }
          return resp.blob().then(function (blob) {
            if (!blob || !blob.size) { avatarSettle(r.key, null, AVATAR_NONE_TTL); return; }
            avatarSettle(r.key, URL.createObjectURL(blob), 0);
          });
        }).catch(function () {
          avatarSettle(r.key, null, AVATAR_ERROR_TTL);
        }).then(function () { avatarInflight--; avatarPump(); });
      })(avatarQueue.shift());
    }
  }
  // Ensure a lookup is cached or in flight; onDone runs when a fetch settles.
  function avatarRequest(r, onDone) {
    if (avatarCached(r.key) !== undefined) return;
    var e = avatarCache[r.key];
    if (!e || !e.pending) {
      e = avatarCache[r.key] = { url: null, pending: true, expires: 0, waiters: [] };
      avatarQueue.push(r);
      avatarPump();
    }
    if (onDone) e.waiters.push(onDone);
  }
  function avatarImg(url, cls, key, onFail) {
    var img = el("img", cls);
    img.alt = "";
    img.decoding = "async";
    img.draggable = false;
    img.addEventListener("error", function () {
      // Undecodable image: treat as "no photo" and fall back.
      if (avatarCache[key] && avatarCache[key].url === url) avatarSettle(key, null, AVATAR_NONE_TTL);
      if (onFail) onFail();
    });
    img.src = url;
    return img;
  }
  // Paint an avatar circle from whatever is cached right now; falls back to
  // the text (initials / 👥) when there is no photo.
  function paintAvatar(av, lookups, fallbackText, bg) {
    var photos = [];
    var groupURL = lookups.length && lookups[0].group ? avatarCached(lookups[0].key) : null;
    if (groupURL) photos.push({ url: groupURL, key: lookups[0].key });
    for (var i = 0; !groupURL && i < lookups.length && photos.length < 2; i++) {
      if (lookups[i].group) continue;
      var u = avatarCached(lookups[i].key);
      if (u) photos.push({ url: u, key: lookups[i].key });
    }
    var sig = photos.map(function (p) { return p.url; }).join(" ");
    if (av.dataset.photos === sig && av.firstChild) return;
    av.dataset.photos = sig;
    av.textContent = "";
    av.classList.remove("has-photo", "avatar-duo");
    av.style.background = bg;
    if (!photos.length) {
      if (fallbackText && fallbackText.icon) av.appendChild(icon(fallbackText.icon, "avatar-ico"));
      else av.textContent = fallbackText;
      return;
    }
    var repaint = function () { if (av.isConnected) { av.dataset.photos = "?"; paintAvatar(av, lookups, fallbackText, bg); } };
    av.classList.add("has-photo");
    if (photos.length === 1) {
      av.appendChild(avatarImg(photos[0].url, "avatar-img", photos[0].key, repaint));
    } else {
      av.classList.add("avatar-duo");
      av.style.background = "transparent";
      av.appendChild(avatarImg(photos[0].url, "avatar-img duo-a", photos[0].key, repaint));
      av.appendChild(avatarImg(photos[1].url, "avatar-img duo-b", photos[1].key, repaint));
    }
  }
  function buildAvatar(c, cls) {
    var fallback = c.IsGroup ? { icon: "group" } : initials(convName(c));
    var bg = "hsl(" + hue(c.ConversationID) + " 45% 38%)";
    var av = el("div", cls);
    var lookups = avatarLookups(c);
    paintAvatar(av, lookups, fallback, bg);
    var update = function () { if (av.isConnected) paintAvatar(av, lookups, fallback, bg); };
    lookups.forEach(function (r) { avatarRequest(r, update); });
    return av;
  }

  // ---------- conversations ----------
  // ---------- notification sound ----------
  // A chime for new incoming messages (not mine, not history, not muted
  // chats), deduped by message id. Fed by both the live stream and the poll:
  // each refresh of the list / open thread passes through here.
  var chime = (function () {
    var SRC = "/app/sounds/chime.mp3", GAP_MS = 1500;
    var startedAt = Date.now(), seen = {}, base = {}, listReady = false, lastAt = 0, audio = null, ctx = null, buf = null, unlocked = false;
    function enabled() { return loadSettings().sound !== false; }
    function muted(id) {
      var c = (state.convs || []).find(function (x) { return x.ConversationID === id; });
      return !!(c && c.notification_mode === "muted");
    }
    function getAudio() {
      if (!audio && window.Audio) { audio = new Audio(SRC); audio.preload = "auto"; audio.volume = 0.8; }
      return audio;
    }
    function getCtx() {
      var AC = window.AudioContext || window.webkitAudioContext;
      if (!ctx && AC) { try { ctx = new AC(); } catch (e) { ctx = null; } }
      return ctx;
    }
    function loadBuf() {
      var c = getCtx();
      if (!c || buf) return;
      fetch(SRC, { credentials: "same-origin" }).then(function (r) { return r.arrayBuffer(); })
        .then(function (ab) { return new Promise(function (ok, bad) { c.decodeAudioData(ab, ok, bad); }); })
        .then(function (b) { buf = b; }).catch(function () {});
    }
    function webAudio() {
      var c = getCtx();
      if (!c) return;
      if (c.state === "suspended") c.resume().catch(function () {});
      if (buf) { var s = c.createBufferSource(); s.buffer = buf; s.connect(c.destination); s.start(); return; }
      // Synthesized fallback: the same two-note chime.
      [[1046.5, 0], [1567.98, 0.12]].forEach(function (n) {
        var o = c.createOscillator(), g = c.createGain(), t = c.currentTime + n[1];
        o.type = "sine"; o.frequency.value = n[0];
        g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(0.25, t + 0.01);
        g.gain.exponentialRampToValueAtTime(0.0001, t + 0.5);
        o.connect(g); g.connect(c.destination); o.start(t); o.stop(t + 0.55);
      });
    }
    function play(force) {
      if (!force && !enabled()) return;
      var now = Date.now();
      if (!force && now - lastAt < GAP_MS) return;
      lastAt = now;
      var a = getAudio();
      if (a) {
        try { a.currentTime = 0; } catch (e) {}
        var p = a.play();
        if (p && p.catch) p.catch(webAudio);
        return;
      }
      webAudio();
    }
    // Autoplay is blocked until the user interacts: on the first tap or key,
    // prime both the <audio> element (silent play) and the AudioContext.
    function unlock() {
      if (unlocked) return;
      unlocked = true;
      var a = getAudio();
      if (a) {
        a.muted = true;
        var p = a.play();
        var done = function () { try { a.pause(); a.currentTime = 0; } catch (e) {} a.muted = false; };
        if (p && p.then) p.then(done, done); else done();
      }
      var c = getCtx();
      if (c && c.state === "suspended") c.resume().catch(function () {});
      loadBuf();
      ["pointerdown", "touchstart", "keydown"].forEach(function (t) { document.removeEventListener(t, unlock, true); });
    }
    function initUnlock() {
      ["pointerdown", "touchstart", "keydown"].forEach(function (t) { document.addEventListener(t, unlock, true); });
    }
    function incoming(m) { return !m.IsFromMe && !/^TOMBSTONE/i.test(m.Status || ""); }
    // Messages of one conversation (newest first or any order).
    function noteMessages(id, msgs) {
      var max = 0, fresh = false;
      msgs.forEach(function (m) { if (m.TimestampMS > max) max = m.TimestampMS; });
      if (!(id in base)) { // first look at this chat = history
        base[id] = max;
        msgs.forEach(function (m) { seen[m.MessageID] = 1; });
        return;
      }
      msgs.forEach(function (m) {
        if (!m.MessageID || seen[m.MessageID]) return;
        seen[m.MessageID] = 1;
        if (m.TimestampMS > base[id] && incoming(m)) fresh = true;
      });
      if (max > base[id]) base[id] = max;
      if (fresh && !muted(id)) play(false);
    }
    // Conversation list: a newer LastMessageTS on a chat that isn't open
    // means new messages; fetch its newest few to check sender and id.
    function noteConversations(list) {
      list.forEach(function (c) {
        var id = c.ConversationID, ts = c.LastMessageTS || 0;
        if (!(id in base)) {
          // A chat that shows up later with a fresh message is a new chat.
          base[id] = listReady && ts > startedAt ? startedAt : ts;
          if (base[id] === ts) return;
        }
        if (!listReady || ts <= base[id] || id === state.current || c.notification_mode === "muted") return;
        api("/api/conversations/" + encodeURIComponent(id) + "/messages?limit=5")
          .then(function (msgs) { noteMessages(id, msgs || []); }).catch(function () {});
      });
      listReady = true;
    }
    return { play: play, initUnlock: initUnlock, noteMessages: noteMessages, noteConversations: noteConversations };
  })();

  function loadConversations(force) {
    // Folder lists are read live from Google (read-only); refresh them only
    // on entry, not on every live-update ping.
    if (state.folder) return force ? loadFolder() : Promise.resolve();
    return api("/api/conversations?limit=100").then(function (list) {
      if (state.folder) return;
      state.convs = (list || []).filter(function (c) { return c.tab !== "archive" && c.tab !== "spam"; });
      chime.noteConversations(state.convs);
      applyConvPatches();
      renderConversations();
    }).catch(function (e) { if (e.message !== "login required") toast("Couldn't load conversations: " + e.message, "error"); });
  }
  // Pinned conversations: google_pinned is the phone's pin (read-only,
  // synced from Google Messages); local_pinned_at_ms is a pin made here
  // (stored on this server, shared by every signed-in screen, not on the phone).
  function convPins(c) { return { phone: !!(c && c.google_pinned), local: !!(c && c.local_pinned_at_ms > 0) }; }
  function isConvPinned(c) { var p = convPins(c); return p.phone || p.local; }
  function byRecency(a, b) { return (b.LastMessageTS || 0) - (a.LastMessageTS || 0); }
  // Live list rows: the list is re-fetched on every message/conversation
  // event, on SSE (re)connect, when the page becomes visible, and every 8 s
  // while the event stream is silent (the quick tunnel buffers it). On top
  // of that, rows are patched from what this page already knows (a send
  // just made, the newest message of the open thread) so the snippet, time
  // and order change at once even if the server's list row lags; a patch is
  // dropped once the server's row is as new.
  state.convPatch = {}; // conv id -> { ts, preview }
  function msgPreview(m, conv) {
    var t = String(m.Body || "").replace(/\s+/g, " ").trim();
    if (m.MediaID && (!t || GENERIC_MEDIA_BODY[t] || BARE_FILENAME.test(t))) {
      var mt = String(m.MimeType || "");
      t = /^image\//.test(mt) ? "Photo" : /^video\//.test(mt) ? "Video" : /^audio\//.test(mt) ? "Audio" : "Attachment";
    }
    if (m.IsFromMe) return "You: " + t;
    if (conv && conv.IsGroup && m.SenderName) return String(m.SenderName).split(" ")[0] + ": " + t;
    return t;
  }
  function patchConv(convID, m) {
    if (!m || !convID || /^TOMBSTONE/i.test(m.Status || "")) return false;
    var c = state.convs.find(function (x) { return x.ConversationID === convID; });
    var ts = m.TimestampMS || Date.now();
    if (!c || ts <= (c.LastMessageTS || 0) && c.last_message_preview) return false;
    var p = state.convPatch[convID];
    if (p && p.ts > ts) return false;
    state.convPatch[convID] = { ts: ts, preview: msgPreview(m, c) };
    applyConvPatches();
    if (!state.folder) renderConversations();
    return true;
  }
  function applyConvPatches() {
    Object.keys(state.convPatch).forEach(function (id) {
      var p = state.convPatch[id], c = state.convs.find(function (x) { return x.ConversationID === id; });
      if (!c) return;
      if ((c.LastMessageTS || 0) >= p.ts) { delete state.convPatch[id]; return; } // server caught up
      c.LastMessageTS = p.ts;
      c.last_message_preview = p.preview;
    });
  }
  function renderConversations() {
    if (!state.folder) renderProfile();
    var box = $("convItems");
    box.textContent = "";
    if (!state.convs.length) {
      var f = state.folder;
      box.appendChild(el("div", "conv-empty" + (f && f.error ? " conv-error" : ""),
        !f ? "No conversations yet" : f.loading ? "Loading " + f.label + "…" : f.error ? "Couldn't load " + f.label + ": " + f.error : "No " + f.label.toLowerCase() + " conversations"));
      return;
    }
    // "Pinned" first (phone + local pins, by latest message), then the rest in
    // their normal order.
    var pinned = [], rest = [];
    state.convs.forEach(function (c) { (isConvPinned(c) ? pinned : rest).push(c); });
    pinned.sort(byRecency);
    if (pinned.length) {
      var head = el("div", "conv-section");
      head.appendChild(svgIcon(PIN_ICON));
      head.appendChild(document.createTextNode("Pinned"));
      box.appendChild(head);
      pinned.forEach(function (c) { box.appendChild(buildConvRow(c)); });
      if (rest.length) box.appendChild(el("div", "conv-section", state.folder ? "Other " + state.folder.label.toLowerCase() : "Other conversations"));
    }
    if (!state.folder) rest.sort(byRecency);
    rest.forEach(function (c) { box.appendChild(buildConvRow(c)); });
    // A live re-render replaces the rows; keep an open long-press menu tied
    // to the new row (and fresh data), or close it if the row is gone.
    var f = state.convMenuFor;
    if (f && f.btn.id !== "convMenuBtn") {
      var row = box.querySelector('.conv[data-id="' + CSS.escape(f.conv.ConversationID) + '"]');
      var fresh = state.convs.find(function (x) { return x.ConversationID === f.conv.ConversationID; });
      if (row && fresh) { f.btn = row; f.conv = fresh; row.classList.add("open"); } else closeConvMenu();
    }
  }
  // List status icon for "You: …" previews (same icons as under your last
  // message). Groups: read by everyone (by Google's "Read by" names vs the
  // members) = filled checks; read by only some = delivered.
  function listStatusKind(c) {
    if (!c.last_from_me || !c.last_status || state.folder) return "";
    var kind = statusKind({ Status: c.last_status, MessageID: "" });
    if (kind === "read" && c.IsGroup) {
      var names = readByNames({ status_text: c.last_status_text });
      var others = currentMembers(c).others.length;
      if (names && names.length && others && names.length < others) kind = "delivered";
    }
    return kind;
  }
  function rcsBadge() {
    var svg = svgEl("svg", { viewBox: "0 0 24 24", class: "rcs-badge", "aria-hidden": "true" });
    svg.appendChild(svgEl("path", { d: "M5 3.5h14A2.5 2.5 0 0 1 21.5 6v8a2.5 2.5 0 0 1-2.5 2.5h-8.5L5.6 20.3c-.4.3-.9 0-.9-.4v-3.4H5A2.5 2.5 0 0 1 2.5 14V6A2.5 2.5 0 0 1 5 3.5z" }));
    return svg;
  }
  // Header profile button (non-car): your Google account photo (fetched,
  // cached and proxied by the server: GET /api/app/profile gives its hash,
  // used as ?h= cache-buster), else your contact photo from Google Messages,
  // else your initial. The account photo is re-checked every 30 minutes and
  // when the page comes back to the foreground.
  var profileKey = "", profilePhoto = { hash: "", at: 0, busy: false };
  function refreshProfilePhoto(force) {
    if (profilePhoto.busy || (!force && Date.now() - profilePhoto.at < 30 * 60 * 1000)) return;
    profilePhoto.busy = true;
    api("/api/app/profile").then(function (r) {
      profilePhoto.at = Date.now();
      var h = (r && r.photo_hash) || "";
      if (h !== profilePhoto.hash) { profilePhoto.hash = h; profileKey = ""; renderProfile(); }
      // First check after a restart: the server fetches in the background.
      if (!h && !profilePhoto.retried) { profilePhoto.retried = true; setTimeout(function () { refreshProfilePhoto(true); }, 15000); }
    }).catch(function () {}).then(function () { profilePhoto.busy = false; });
  }
  function renderProfile() {
    refreshProfilePhoto(false);
    var me = null;
    state.convs.some(function (c) {
      return conversationParticipants(c).some(function (p) { if (p && isMe(p) && (p.id || p.number)) { me = p; return true; } return false; });
    });
    var key = (me ? (me.id || "") + "|" + (me.number || "") + "|" + (me.name || "") : "none") + "|" + profilePhoto.hash;
    if (key === profileKey) return;
    profileKey = key;
    var slot = $("profileAv");
    slot.textContent = "";
    var name = (me && (me.first_name || me.name)) || "Me";
    var av = el("div", "avatar profile-avatar");
    var r = me ? refsFor([me]) : null;
    if (r) r.key = avatarLookupKey(r);
    var lookups = r && r.key ? [r] : [];
    var bg = "hsl(" + hue((me && me.number) || name) + " 45% 38%)";
    if (profilePhoto.hash) {
      av.classList.add("has-photo");
      var img = el("img", "avatar-img");
      img.alt = "";
      img.src = "/api/app/profile-photo?h=" + encodeURIComponent(profilePhoto.hash);
      img.addEventListener("error", function () { profilePhoto.hash = ""; profileKey = ""; renderProfile(); });
      av.appendChild(img);
    } else {
      paintAvatar(av, lookups, initials(name).charAt(0), bg);
      lookups.forEach(function (x) { avatarRequest(x, function () { if (av.isConnected && !profilePhoto.hash) paintAvatar(av, lookups, initials(name).charAt(0), bg); }); });
    }
    slot.appendChild(av);
    $("profileBtn").title = name + " · Menu";
  }
  function buildConvRow(c) {
    var pins = convPins(c);
    var b = el("button", "conv" + (c.ConversationID === state.current ? " active" : "") + (c.UnreadCount > 0 ? " unread" : "") + (pins.phone || pins.local ? " is-pinned" : ""));
    b.type = "button";
    b.dataset.id = c.ConversationID;
    var av = el("span", "conv-av");
    av.appendChild(buildAvatar(c, "avatar"));
    // Non-car: Google Messages' small chat-bubble badge on RCS chats.
    if (String(c.display_protocol || "").toUpperCase() === "RCS") av.appendChild(rcsBadge());
    var mid = el("div", "conv-mid");
    var nameRow = el("div", "conv-name", convName(c));
    if (PLATFORM[c.source_platform]) nameRow.appendChild(el("span", "tag", PLATFORM[c.source_platform]));
    mid.appendChild(nameRow);
    var typers = typingNames(c.ConversationID);
    if (typers.length) mid.appendChild(typingText("conv-preview typing", typingLabel(typers, c.IsGroup)));
    else {
      var pv = el("div", "conv-preview");
      var lk = listStatusKind(c);
      if (lk) pv.appendChild(statusIcon(lk));
      pv.appendChild(document.createTextNode(c.last_message_preview || ""));
      mid.appendChild(pv);
    }
    var right = el("div", "conv-right");
    var time = el("div", "conv-time");
    var deskRow = isDesk();
    if ((pins.phone || pins.local) && !deskRow) {
      var icon = svgIcon(PIN_ICON, "conv-pin" + (pins.phone ? " conv-pin-phone" : ""));
      var t = document.createElementNS("http://www.w3.org/2000/svg", "title");
      t.textContent = pins.phone ? "Pinned on your phone" : "Pinned";
      icon.appendChild(t);
      time.appendChild(icon);
      time.classList.add("has-pin");
    }
    // Pinned rows: fixed-width pin column + fixed-width, right-aligned time,
    // so the pins line up whatever the time text ("6:21 PM", "Mon").
    time.appendChild(el("span", "conv-time-text", fmtTime(c.LastMessageTS)));
    right.appendChild(time);
    // Non-car (Google Messages): the pin sits under the time.
    if ((pins.phone || pins.local) && deskRow) {
      var pin2 = svgIcon(PIN_ICON, "conv-pin conv-pin-under");
      var t2 = document.createElementNS("http://www.w3.org/2000/svg", "title");
      t2.textContent = pins.phone ? "Pinned on your phone" : "Pinned";
      pin2.appendChild(t2);
      right.appendChild(pin2);
    }
    if (c.UnreadCount > 0) right.appendChild(el("div", "badge", String(c.UnreadCount)));
    b.appendChild(av); b.appendChild(mid); b.appendChild(right);
    if (pins.phone || pins.local) b.setAttribute("aria-label", convName(c) + (pins.phone ? ", pinned on your phone" : ", pinned"));
    // Long-press (or right-click) opens the conversation menu (Pin / Unpin).
    attachLongPress(b, function (x, y) { openConvMenu(c, { left: x, top: y, bottom: y }, b); });
    b.addEventListener("click", function () { openConversation(c.ConversationID); });
    return b;
  }
  // Long-press: fires after 550 ms without moving; the click that follows is
  // swallowed so the conversation doesn't also open.
  function attachLongPress(node, onLong) {
    var timer = null, sx = 0, sy = 0, fired = false;
    function cancel() { if (timer) { clearTimeout(timer); timer = null; } node.classList.remove("pressing"); }
    node.addEventListener("pointerdown", function (e) {
      if (e.button) return;
      fired = false; sx = e.clientX; sy = e.clientY;
      cancel();
      node.classList.add("pressing");
      timer = setTimeout(function () { timer = null; node.classList.remove("pressing"); fired = true; onLong(sx, sy); }, 550);
    });
    node.addEventListener("pointermove", function (e) {
      if (timer && (Math.abs(e.clientX - sx) > 14 || Math.abs(e.clientY - sy) > 14)) cancel();
    });
    ["pointerup", "pointercancel", "pointerleave"].forEach(function (t) { node.addEventListener(t, cancel); });
    node.addEventListener("contextmenu", function (e) {
      e.preventDefault();
      if (fired) return;
      cancel(); fired = true;
      onLong(e.clientX, e.clientY);
    });
    node.addEventListener("click", function (e) {
      if (!fired) return;
      fired = false;
      e.preventDefault(); e.stopImmediatePropagation();
    }, true);
  }

  // ---------- conversation menu (Pin / Unpin) ----------
  function openConvMenu(c, anchor, btn) {
    closeMsgMenu();
    closeConvMenu();
    if (!c) { toast("This conversation isn't loaded yet"); return; }
    state.convMenuFor = { conv: c, btn: btn };
    btn.classList.add("open");
    if (btn.id === "convMenuBtn") btn.setAttribute("aria-expanded", "true");
    var pins = convPins(c), item = $("convMenuPin");
    var canPin = !state.folder || !!c.local;
    var google = sourcePlatformOf(c) === "sms";
    var fname = state.folder && state.folder.name;
    $("convMenuTitle").textContent = convName(c);
    // Order: Chat theme, Group/Contact details, Pin/Unpin, Archive, Move to trash.
    $("convMenuTheme").hidden = !(btn.id === "convMenuBtn" && state.current === c.ConversationID && !state.folder);
    $("convMenuDetailsLabel").textContent = c.IsGroup ? "Group details" : "Contact details";
    // Pinned on the phone: nothing to do here, so no Pin item at all.
    item.hidden = pins.phone;
    item.classList.toggle("is-pinned", pins.local);
    if (!canPin) {
      item.disabled = true;
      $("convMenuPinLabel").textContent = "Pin conversation";
      $("convMenuPinNote").textContent = "Not available: this conversation isn't stored here";
    } else {
      item.disabled = false;
      $("convMenuPinLabel").textContent = pins.local ? "Unpin conversation" : "Pin conversation";
      $("convMenuPinNote").textContent = pins.local ? "" : "Pinned in Messages Enhanced only, not on your phone";
    }
    var archived = fname === "archived" || c.tab === "archive";
    var arch = $("convMenuArchive");
    arch.hidden = !google || (!!fname && fname !== "archived");
    arch.querySelector(".conv-menu-ico").dataset.icon = archived ? "unarchive" : "archive";
    arch.querySelector(".conv-menu-ico").textContent = "";
    hydrateIcons(arch);
    $("convMenuArchiveLabel").textContent = archived ? "Unarchive" : "Archive";
    arch.dataset.archived = archived ? "1" : "";
    $("convMenuTrash").hidden = !google;
    // Non-car: the header's mute bell lives in this menu too.
    var inHeader = btn.id === "convMenuBtn" && isDesk();
    $("convMenuMute").hidden = !inHeader;
    $("convMenuMuteLabel").textContent = $("muteBtn").classList.contains("muted") ? "Unmute notifications" : "Mute notifications";
    // Settings is in the conversation header's ⋮ in every mode (no gear).
    $("convMenuSettings").hidden = btn.id !== "convMenuBtn";
    var menu = $("convMenu");
    menu.hidden = false;
    placePopover(menu, anchor);
  }
  function closeAppMenu() {
    if ($("appMenu").hidden) return false;
    $("appMenu").hidden = true; $("appMenuBtn").setAttribute("aria-expanded", "false"); $("profileBtn").setAttribute("aria-expanded", "false"); $("emptyMenuBtn").setAttribute("aria-expanded", "false");
    return true;
  }
  function closeConvMenu() {
    var f = state.convMenuFor;
    state.convMenuFor = null;
    $("convMenu").hidden = true;
    if (f && f.btn) { f.btn.classList.remove("open"); if (f.btn.id === "convMenuBtn") f.btn.setAttribute("aria-expanded", "false"); }
  }
  // ----- Group / Contact details -----
  // Current members only: Google keeps people who left or were removed in
  // the participant list (stored with hidden: true) so old messages still
  // show their names. If every other entry is hidden, show them all.
  function currentMembers(c) {
    var people = conversationParticipants(c);
    var others = people.filter(function (p) { return p && !isMe(p); });
    var current = others.filter(function (p) { return !p.hidden; });
    return { others: current.length ? current : others, me: people.filter(isMe)[0] || null };
  }
  // Your own name: from your participant entry in any conversation.
  function selfName() {
    var n = "";
    state.convs.some(function (c) {
      return conversationParticipants(c).some(function (p) { if (p && isMe(p) && (p.name || p.first_name)) { n = p.name || p.first_name; return true; } return false; });
    });
    return n;
  }
  function selfAvatar(me, cls) {
    var name = (me && (me.name || me.first_name)) || selfName() || "Me";
    if (profilePhoto.hash) {
      var av = el("div", cls + " has-photo");
      var img = el("img", "avatar-img");
      img.alt = "";
      img.src = "/api/app/profile-photo?h=" + encodeURIComponent(profilePhoto.hash);
      av.appendChild(img);
      img.addEventListener("error", function () { av.replaceWith(contactAvatar({ name: name, number: me && me.number, participant_id: me && me.id }, cls)); });
      return av;
    }
    return contactAvatar({ name: name, number: me && me.number, participant_id: me && me.id }, cls);
  }
  function openDetails(c) {
    var people = conversationParticipants(c);
    var cm = currentMembers(c);
    var others = cm.others;
    $("detailsTitle").textContent = c.IsGroup ? "Group details" : "Contact details";
    var slot = $("detailsAvatar");
    slot.textContent = "";
    slot.appendChild(buildAvatar(c, "avatar details-avatar"));
    $("detailsName").textContent = convName(c);
    var sub;
    if (c.IsGroup) sub = people.length ? (others.length + 1) + " people, including you" : "Group conversation";
    else sub = (others[0] && others[0].number && others[0].number !== convName(c)) ? others[0].number : (others[0] && others[0].number ? "" : "No number available");
    $("detailsSub").textContent = sub;
    var list = $("detailsMembers");
    list.textContent = "";
    $("detailsMembersLabel").hidden = !c.IsGroup;
    list.hidden = !c.IsGroup;
    if (c.IsGroup) {
      var rows = others.slice().sort(function (a, b) { return String(a.name || a.number).localeCompare(String(b.name || b.number)); });
      rows.unshift(cm.me || { is_me: true });
      rows.forEach(function (p) {
        var row = el("div", "details-member");
        var mine = isMe(p), myName = mine ? (p.name || p.first_name || selfName()) : "";
        var name = mine ? (myName ? myName + " (you)" : "You") : (p.name || p.number || "Unknown");
        row.appendChild(mine ? selfAvatar(p, "avatar details-member-avatar") :
          contactAvatar({ name: name, number: p.number, participant_id: p.id, contact_id: p.contact_id }, "avatar details-member-avatar"));
        var txt = el("div", "details-member-text");
        txt.appendChild(el("div", "details-member-name", name));
        if (p.number && p.number !== name) txt.appendChild(el("div", "details-member-num", p.number));
        row.appendChild(txt);
        list.appendChild(row);
      });
      if (!others.length) list.appendChild(el("div", "details-empty", "Member list isn't available for this conversation."));
    }
    $("detailsView").hidden = false;
  }
  function closeDetails() { $("detailsView").hidden = true; }

  // ----- Archive / Move to trash (Google Messages, on the phone) -----
  function dropConversation(id) {
    if (state.current === id) closeThread();
    state.convs = state.convs.filter(function (x) { return x.ConversationID !== id; });
    renderConversations();
  }
  function setConvArchived(c, archived) {
    var id = c.ConversationID;
    toast(archived ? "Archiving…" : "Moving back to the inbox…");
    return postJSON("/api/app/conversations/archive", { conversation_id: id, archived: archived }).then(function () {
      // Archiving takes it out of the list; unarchiving takes it out of the
      // Archived folder.
      dropConversation(id);
      toast(archived ? "Archived. Find it in Settings → Folders → Archived." : "Moved back to the inbox");
      if (!state.folder) loadConversations();
    }).catch(function (e) { toast((archived ? "Couldn't archive: " : "Couldn't unarchive: ") + e.message, "error"); });
  }
  var trashFor = null;
  function askTrash(c) {
    trashFor = c;
    $("trashTitle").textContent = "Move “" + convName(c) + "” to trash?";
    $("trashOk").disabled = false;
    $("trashOk").textContent = "Move to trash";
    $("trashView").hidden = false;
    $("trashCancel").focus();
  }
  function hideTrash() { $("trashView").hidden = true; trashFor = null; }
  function confirmTrash() {
    var c = trashFor;
    if (!c) return hideTrash();
    $("trashOk").disabled = true;
    $("trashOk").textContent = "Moving…";
    postJSON("/api/app/conversations/trash", { conversation_id: c.ConversationID }).then(function () {
      hideTrash();
      dropConversation(c.ConversationID); // back to the list
      toast("Moved to trash");
      if (!state.folder) loadConversations();
    }).catch(function (e) {
      $("trashOk").disabled = false;
      $("trashOk").textContent = "Move to trash";
      toast("Couldn't move it to trash: " + e.message, "error");
    });
  }

  function setConvPinned(c, pinned) {
    return postJSON("/api/app/conversations/pin", { conversation_id: c.ConversationID, pinned: pinned }).then(function (res) {
      state.convs.forEach(function (x) {
        if (x.ConversationID !== res.conversation_id) return;
        x.local_pinned_at_ms = res.local_pinned_at_ms || 0;
        x.google_pinned = !!res.google_pinned;
      });
      renderConversations();
      toast(pinned ? "Conversation pinned" : "Conversation unpinned");
    }).catch(function (e) {
      if (e.message !== "login required") toast((pinned ? "Couldn't pin: " : "Couldn't unpin: ") + e.message, "error");
    });
  }

  // ---------- per-conversation chat theme ----------
  // Stored on the server (GET /api/app/themes, PUT/DELETE /api/app/theme,
  // custom photos at /api/app/theme/background) so the car, a tablet and a
  // phone all show the same theme. The server stores ids only; the colors
  // live here. Every sent-bubble / accent color keeps white text >= 4.5:1;
  // received bubbles stay dark. Over a wallpaper the thread gets a dark scrim
  // and fully opaque bubbles so text stays readable.
  var THEME_OLD_KEY = "tm.theme."; // legacy localStorage themes (migrated once)
  var PALETTES = [
    { id: "blue",       name: "Blue",       light: "#a8c7fa", me: "#1f5fbf", them: "#26313f", accent: "#2f6fe4", surface: "#0e2a3e" },
    { id: "periwinkle", name: "Periwinkle", light: "#bac3ff", me: "#4454b8", them: "#2d2f45", accent: "#4f5fcf", surface: "#1b1f3d" },
    { id: "sky",        name: "Sky",        light: "#86d1ec", me: "#006689", them: "#1e3640", accent: "#00769e", surface: "#0b2b35" },
    { id: "lilac",      name: "Lilac",      light: "#e3b8ff", me: "#7a3fae", them: "#382b47", accent: "#8a4cc0", surface: "#2a1d38" },
    { id: "sage",       name: "Sage",       light: "#c3cfb2", me: "#48633e", them: "#2c3628", accent: "#4f6e44", surface: "#1d2a1d" },
    { id: "coral",      name: "Peach",      light: "#ffb690", me: "#a1461c", them: "#46302a", accent: "#b04e20", surface: "#36221a" },
    { id: "rose",       name: "Pink",       light: "#ffb1cf", me: "#a6356b", them: "#462a37", accent: "#b53b76", surface: "#361c29" },
    { id: "gold",       name: "Gold",       light: "#efcf72", me: "#7a5600", them: "#3b3322", accent: "#8a6200", surface: "#2e2612" },
    { id: "slate",      name: "Slate",      light: "#c4c7cc", me: "#505760", them: "#2b2e34", accent: "#5a626c", surface: "#1e2227" },
  ];
  var LEGACY_PRESETS = { ocean: "sky", sunset: "coral", forest: "sage", mono: "slate" };
  var THEME_DEFAULT = { light: "#9cc3ff", me: "#2f6fe4", them: "#262b33", accent: "#3e8bff", surface: null };
  var CT_MAX_UPLOAD = 15 * 1024 * 1024;
  var themes = {};          // conv id -> server theme {palette, color, wallpaper, custom, updated_ms}
  var themesLoaded = false;
  var wallpapers = null;    // [{id, name, items:[...]}] (fetched lazily)
  var wallpaperIndex = {};  // id -> item
  var ct = null;            // open Chat theme panel state

  function darken(hex, f) {
    var n = parseInt(hex.slice(1), 16);
    var r = Math.round(((n >> 16) & 255) * f), g = Math.round(((n >> 8) & 255) * f), b = Math.round((n & 255) * f);
    return "#" + ((1 << 24) | (r << 16) | (g << 8) | b).toString(16).slice(1);
  }
  function validHex(v) { return typeof v === "string" && /^#[0-9a-f]{6}$/i.test(v); }
  function paletteByID(id) { for (var i = 0; i < PALETTES.length; i++) if (PALETTES[i].id === id) return PALETTES[i]; return null; }
  // Theme -> colors ({light, me, them, accent, surface}); default look for none.
  function themeColors(t) {
    var p = t && paletteByID(t.palette);
    if (p) return p;
    if (t && validHex(t.color)) return { light: t.color, me: t.color, them: THEME_DEFAULT.them, accent: t.color, surface: null };
    return THEME_DEFAULT;
  }
  function customBgURL(convID, version) {
    return "/api/app/theme/background?conversation_id=" + encodeURIComponent(convID) + "&v=" + encodeURIComponent(version || "");
  }
  // Theme -> background image URL (or "").
  function themeBackground(convID, t) {
    if (!t) return "";
    if (t.pendingURL) return t.pendingURL;
    if (t.custom) return customBgURL(convID, t.custom);
    if (t.wallpaper) return "/wallpapers/" + wallpaperFileFor(t.wallpaper);
    return "";
  }
  // Wallpaper URLs are content-hashed; before the list is fetched we only know
  // the id, so the server theme carries it and we resolve lazily.
  function wallpaperFileFor(id) {
    var it = wallpaperIndex[id];
    return it ? it.file : "";
  }
  function paintThemeVars(node, colors) {
    var s = node.style;
    s.setProperty("--me", colors.me);
    s.setProperty("--them", colors.them);
    s.setProperty("--accent", colors.accent);
    s.setProperty("--accent-2", darken(colors.accent, 0.8));
    s.setProperty("--accent-light", colors.light);
  }
  function clearThemeVars(node) {
    ["--me", "--them", "--accent", "--accent-2", "--accent-light"].forEach(function (k) { node.style.removeProperty(k); });
  }
  function setWallpaper(node, url) {
    if (url) {
      node.style.backgroundImage = "linear-gradient(rgba(6,8,11,.42), rgba(6,8,11,.42)), url(\"" + url.replace(/"/g, "%22") + "\")";
      node.classList.add("has-wallpaper");
    } else {
      node.style.backgroundImage = "";
      node.classList.remove("has-wallpaper");
    }
  }
  function applyTheme(id) {
    var tv = $("threadView"), t = id ? themes[id] : null;
    var hasColors = !!(t && (paletteByID(t.palette) || validHex(t.color)));
    clearThemeVars(tv);
    tv.classList.toggle("themed", hasColors);
    if (hasColors) paintThemeVars(tv, themeColors(t));
    if (t && t.wallpaper && !wallpaperIndex[t.wallpaper]) {
      setWallpaper(tv, "");
      loadWallpapers().then(function () { if (state.current === id) applyTheme(id); });
      return;
    }
    setWallpaper(tv, themeBackground(id, t));
  }
  function loadThemes() {
    return api("/api/app/themes").then(function (res) {
      themes = (res && res.themes) || {};
      themesLoaded = true;
      migrateLocalThemes();
      if (state.current && !ct) applyTheme(state.current);
    }).catch(function () {});
  }
  function loadWallpapers() {
    if (wallpapers) return Promise.resolve(wallpapers);
    if (loadWallpapers.p) return loadWallpapers.p;
    loadWallpapers.p = api("/api/app/wallpapers").then(function (res) {
      wallpapers = (res && res.categories) || [];
      wallpapers.forEach(function (c) { c.items.forEach(function (it) { wallpaperIndex[it.id] = it; }); });
      return wallpapers;
    }).catch(function (e) { loadWallpapers.p = null; throw e; });
    return loadWallpapers.p;
  }
  function putTheme(id, t) {
    if (!t || (!t.palette && !t.color && !t.wallpaper && !t.custom)) {
      return api("/api/app/theme?conversation_id=" + encodeURIComponent(id), { method: "DELETE" }).then(function () { delete themes[id]; });
    }
    return api("/api/app/theme", { method: "PUT", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ conversation_id: id, theme: { palette: t.palette || "", color: t.color || "", wallpaper: t.wallpaper || "", custom: !!t.custom } }) })
      .then(function (res) { themes[id] = res.theme; return res.theme; });
  }
  // One-time move of old per-device themes (localStorage) to the server. A
  // conversation that already has a server theme keeps it.
  function migrateLocalThemes() {
    var keys = [];
    try {
      for (var i = 0; i < localStorage.length; i++) {
        var k = localStorage.key(i);
        if (k && k.indexOf(THEME_OLD_KEY) === 0) keys.push(k);
      }
    } catch (e) { return; }
    keys.forEach(function (k) {
      var id = k.slice(THEME_OLD_KEY.length), old = null;
      try { old = JSON.parse(localStorage.getItem(k) || "null"); } catch (e) {}
      var done = function () { try { localStorage.removeItem(k); } catch (e) {} };
      if (!id || themes[id] || !old || typeof old !== "object") { done(); return; }
      var t = null;
      if (old.preset && LEGACY_PRESETS[old.preset]) t = { palette: LEGACY_PRESETS[old.preset] };
      else if (validHex(old.color)) t = { color: old.color.toLowerCase() };
      if (!t) { done(); return; }
      putTheme(id, t).then(function () { done(); if (state.current === id && !ct) applyTheme(id); }).catch(function () {});
    });
  }

  // Re-read one conversation's theme (it may have changed on another device).
  function refreshTheme(id) {
    if (!themesLoaded || !id) return;
    api("/api/app/theme?conversation_id=" + encodeURIComponent(id)).then(function (res) {
      if (!res) return;
      var before = JSON.stringify(themes[id] || null);
      if (res.set) themes[id] = res.theme; else delete themes[id];
      if (JSON.stringify(themes[id] || null) !== before && state.current === id && !ct) applyTheme(id);
    }).catch(function () {});
  }

  // ----- Chat theme panel -----
  function themeKey(t) {
    t = t || {};
    return [t.palette || "", t.palette ? "" : (t.color || ""), t.wallpaper || "", t.custom || "", t.pendingURL || ""].join("|");
  }
  function openChatTheme() {
    if (!state.current || state.folder) return;
    closeMsgMenu();
    var id = state.current, saved = themes[id] || {};
    ct = { id: id, view: "main", cat: null, catBefore: null,
      saved: { palette: saved.palette, color: saved.color, wallpaper: saved.wallpaper, custom: saved.custom },
      draft: { palette: saved.palette, color: saved.color, wallpaper: saved.wallpaper, custom: saved.custom },
      pendingFile: null, busy: false };
    $("themeView").hidden = false;
    renderChatTheme();
    loadWallpapers().then(function () { if (ct && ct.id === id) renderChatTheme(); })
      .catch(function (e) { if (ct) { $("ctCats").textContent = ""; $("ctCats").appendChild(el("div", "ct-empty", "Couldn't load wallpapers: " + e.message)); } });
    // pick up changes made on another device
    api("/api/app/theme?conversation_id=" + encodeURIComponent(id)).then(function (res) {
      if (!ct || ct.id !== id || !res) return;
      if (res.set) themes[id] = res.theme; else delete themes[id];
      var s = res.set ? res.theme : {};
      if (themeKey(ct.draft) === themeKey(ct.saved)) {
        ct.saved = { palette: s.palette, color: s.color, wallpaper: s.wallpaper, custom: s.custom };
        ct.draft = { palette: s.palette, color: s.color, wallpaper: s.wallpaper, custom: s.custom };
        renderChatTheme();
      }
    }).catch(function () {});
  }
  function closeChatTheme() {
    if (!ct) { $("themeView").hidden = true; return; }
    if (ct.draft.pendingURL) URL.revokeObjectURL(ct.draft.pendingURL);
    ct = null;
    $("themeView").hidden = true;
    $("ctFile").value = "";
    if (state.current) applyTheme(state.current);
  }
  function ctBack() {
    if (!ct) return;
    if (ct.view === "category") {
      ct.draft = ct.catBefore; ct.view = "main"; ct.cat = null;
      renderChatTheme();
      return;
    }
    closeChatTheme();
  }
  function ctDirty() { return !!ct && themeKey(ct.draft) !== themeKey(ct.saved); }
  function renderChatTheme() {
    if (!ct) return;
    var c = state.convs.find(function (x) { return x.ConversationID === ct.id; });
    var inCat = ct.view === "category";
    $("ctTitle").textContent = inCat ? ct.cat.name : "Chat theme";
    $("ctSub").textContent = inCat ? "Tap a photo to preview it" : (c ? convName(c) + " · " : "") + "Shows on every device you use Messages on";
    var act = $("ctApply");
    act.textContent = inCat ? "Next" : (ct.busy ? "Saving…" : "Apply");
    act.disabled = ct.busy || (inCat ? themeKey(ct.draft) === themeKey(ct.catBefore) : !ctDirty());
    $("ctReset").disabled = ct.busy || themeKey(ct.draft) === themeKey({});
    renderCTPreview();
    $("ctMain").hidden = inCat;
    $("ctCategory").hidden = !inCat;
    var colors = themeColors(ct.draft);
    var panel = $("themeView").querySelector(".ct-options");
    panel.style.background = colors.surface || "";
    if (inCat) renderCTCategory(); else renderCTMain();
  }
  function renderCTPreview() {
    var box = $("ctPreview"), t = ct.draft;
    // Colors go on the whole panel so Apply / Choose a photo / selection
    // rings follow the palette being previewed.
    clearThemeVars($("themeView"));
    paintThemeVars($("themeView"), themeColors(t));
    var url = themeBackground(ct.id, t);
    if (!url && t.wallpaper && !wallpaperIndex[t.wallpaper]) url = "";
    setWallpaper(box, url);
    var c = state.convs.find(function (x) { return x.ConversationID === ct.id; });
    $("ctPrevName").textContent = c && c.IsGroup ? "Alex" : "";
    $("ctPrevName").hidden = !(c && c.IsGroup);
  }
  function swatchStyle(p) {
    // Google-style two-tone circle: light tone on top, sent/received below.
    return "conic-gradient(from -90deg, " + p.light + " 0 50%, " + p.them + " 50% 75%, " + p.me + " 75% 100%)";
  }
  function renderCTMain() {
    var row = $("ctColors");
    row.textContent = "";
    var cur = ct.draft.palette || "";
    var mk = function (p, label) {
      var b = el("button", "ct-swatch" + ((p ? p.id : "") === cur && !(p === null && validHex(ct.draft.color)) ? " selected" : ""));
      b.type = "button";
      b.setAttribute("aria-label", label);
      b.setAttribute("aria-pressed", b.classList.contains("selected") ? "true" : "false");
      var dot = el("span", "ct-swatch-dot");
      dot.style.background = swatchStyle(p || THEME_DEFAULT);
      b.appendChild(dot);
      b.addEventListener("click", function () {
        ct.draft.palette = p ? p.id : "";
        ct.draft.color = "";
        renderChatTheme();
      });
      return b;
    };
    row.appendChild(mk(null, "Default colors"));
    PALETTES.forEach(function (p) { row.appendChild(mk(p, p.name + " colors")); });
    if (validHex(ct.draft.color) && !ct.draft.palette) {
      var legacy = el("button", "ct-swatch selected");
      legacy.type = "button"; legacy.setAttribute("aria-label", "Current custom color");
      var d = el("span", "ct-swatch-dot"); d.style.background = ct.draft.color; legacy.appendChild(d);
      row.appendChild(legacy);
    }
    var cats = $("ctCats");
    cats.textContent = "";
    var hasPhoto = !!(ct.draft.custom || ct.draft.pendingURL);
    $("ctRemovePhoto").hidden = !(hasPhoto || ct.draft.wallpaper);
    $("ctRemovePhoto").textContent = hasPhoto ? "Remove photo" : "Remove wallpaper";
    if (!wallpapers) { cats.appendChild(el("div", "ct-empty", "Loading wallpapers…")); return; }
    wallpapers.forEach(function (cat) {
      var b = el("button", "ct-cat");
      b.type = "button";
      var sel = ct.draft.wallpaper && cat.items.some(function (it) { return it.id === ct.draft.wallpaper; });
      if (sel) b.classList.add("selected");
      var img = el("img", "ct-cat-img");
      img.alt = ""; img.loading = "lazy"; img.decoding = "async"; img.draggable = false;
      img.src = cat.items[0].thumb_url;
      b.appendChild(img);
      b.appendChild(el("span", "ct-cat-name", cat.name));
      b.addEventListener("click", function () {
        ct.catBefore = { palette: ct.draft.palette, color: ct.draft.color, wallpaper: ct.draft.wallpaper, custom: ct.draft.custom, pendingURL: ct.draft.pendingURL };
        ct.view = "category"; ct.cat = cat;
        renderChatTheme();
        $("ctCategory").scrollTop = 0;
      });
      cats.appendChild(b);
    });
  }
  function renderCTCategory() {
    var grid = $("ctPhotos");
    grid.textContent = "";
    ct.cat.items.forEach(function (it, i) {
      var b = el("button", "ct-photo" + (ct.draft.wallpaper === it.id ? " selected" : ""));
      b.type = "button";
      b.setAttribute("aria-label", it.title + " (" + (i + 1) + " of " + ct.cat.items.length + ")");
      var img = el("img");
      img.alt = ""; img.loading = "lazy"; img.decoding = "async"; img.draggable = false;
      img.src = it.thumb_url;
      b.appendChild(img);
      b.addEventListener("click", function () {
        ct.draft.wallpaper = it.id;
        ct.draft.custom = "";
        ct.draft.pendingURL = "";
        renderChatTheme();
      });
      grid.appendChild(b);
    });
    var sel = wallpaperIndex[ct.draft.wallpaper];
    $("ctCredit").textContent = sel && ct.cat.items.indexOf(sel) >= 0
      ? "Photo: " + sel.title + " · " + sel.author + " · " + sel.license : "";
  }
  function ctPrimary() {
    if (!ct || ct.busy) return;
    if (ct.view === "category") { ct.view = "main"; ct.cat = null; renderChatTheme(); return; }
    if (!ctDirty()) return;
    var id = ct.id, d = ct.draft, file = ct.pendingFile;
    ct.busy = true; renderChatTheme();
    var p;
    if (d.pendingURL && file) {
      var fd = new FormData();
      fd.append("conversation_id", id);
      fd.append("file", file, file.name || "photo.jpg");
      p = api("/api/app/theme/background", { method: "POST", body: fd }).then(function (res) {
        themes[id] = res.theme;
        return putTheme(id, { palette: d.palette, color: d.color, custom: true });
      });
    } else {
      p = putTheme(id, { palette: d.palette, color: d.color, wallpaper: d.wallpaper, custom: !!d.custom });
    }
    p.then(function () {
      if (!ct || ct.id !== id) return;
      closeChatTheme();
      toast("Chat theme saved");
    }).catch(function (e) {
      if (!ct || ct.id !== id) return;
      ct.busy = false; renderChatTheme();
      if (e.message !== "login required") toast("Couldn't save the theme: " + e.message, "error");
    });
  }
  function ctReset() {
    if (!ct) return;
    if (ct.draft.pendingURL) URL.revokeObjectURL(ct.draft.pendingURL);
    ct.draft = {}; ct.pendingFile = null;
    renderChatTheme();
  }
  function ctRemovePhoto() {
    if (!ct) return;
    if (ct.draft.pendingURL) URL.revokeObjectURL(ct.draft.pendingURL);
    ct.draft.pendingURL = ""; ct.draft.custom = ""; ct.draft.wallpaper = ""; ct.pendingFile = null;
    renderChatTheme();
  }
  function ctFilePicked() {
    var f = $("ctFile").files && $("ctFile").files[0];
    $("ctFile").value = "";
    if (!f || !ct) return;
    if (f.size > CT_MAX_UPLOAD) { toast("That photo is too large (15 MB max)", "error"); return; }
    if (f.type && !/^image\/(jpeg|png|gif|webp)$/i.test(f.type)) { toast("Use a JPEG, PNG, GIF or WebP photo", "error"); return; }
    if (ct.draft.pendingURL) URL.revokeObjectURL(ct.draft.pendingURL);
    ct.pendingFile = f;
    ct.draft.pendingURL = URL.createObjectURL(f);
    ct.draft.wallpaper = ""; ct.draft.custom = "";
    renderChatTheme();
  }

  // ---------- thread ----------
  function openConversation(id, nameHint) {
    if (state.rec) cancelRecording();
    showEmoji(false);
    showPlusSheet(false);
    closeMsgMenu();
    closeConvMenu();
    closeImageViewer();
    if (state.current !== id) { clearPending(); if (!(state.jump && state.jump.conv === id)) state.msgLimit = null; }
    state.current = id;
    loadConvMeta(id);
    stopSelect(); closeMsgSheet();
    loadStars(id);
    var readOnly = !!state.folder;
    document.body.classList.toggle("readonly-thread", readOnly);
    $("readonlyNote").hidden = !readOnly;
    $("readonlyNote").textContent = readOnly ? "View only · " + state.folder.label + " (Google Messages)" : "";
    document.body.classList.add("has-thread");
    $("threadEmpty").hidden = true;
    $("threadView").hidden = false;
    var c = state.convs.find(function (x) { return x.ConversationID === id; });
    $("threadTitle").textContent = c ? convName(c) : (nameHint || id);
    var tav = $("threadAvatar"); tav.textContent = "";
    if (c) tav.appendChild(buildAvatar(c, "avatar thread-av"));
    renderQuickReplies();
    if (ct) closeChatTheme();
    applyTheme(id);
    refreshTheme(id);
    state.nodes = {};
    clearReply();
    beginScrollState(id, c, readOnly);
    $("messages").textContent = "";
    renderConversations();
    relayoutSoon();
    loadMessages(true);
    fetchTyping();
    // Mark read (here and on the phone) once the chat is actually viewed:
    // syncReadSoon checks visibility. Folders are view only (no mark-read);
    // a chat opened by a search jump is marked only when scrolled to the end.
    readSync.openConv = readOnly || (state.jump && state.jump.conv === id) ? "" : id;
    if (!readOnly) syncReadSoon();
  }
  function closeThread() {
    if (state.rec) cancelRecording();
    showEmoji(false);
    closeImageViewer();
    if (ct) closeChatTheme();
    closeMsgMenu();
    clearReply();
    setComposerFloat(false);
    clearPending();
    state.current = null; document.body.classList.remove("has-thread", "readonly-thread");
    state.msgLimit = null; state.jump = null;
    stopSelect(); closeMsgSheet();
    renderQuickReplies();
    $("threadView").hidden = true; $("threadEmpty").hidden = false;
  }
  // quiet: a background refresh (live event, poll, send follow-up) that
  // skips re-rendering when nothing changed, so polling doesn't redraw.
  var lastRender = { id: null, sig: "" };
  function loadMessages(scroll, quiet) {
    var id = state.current;
    if (!id) return Promise.resolve();
    var lim = state.jump && state.jump.conv === id ? state.jump.limit : (state.msgLimit && state.msgLimit.id === id ? state.msgLimit.n : 80);
    if (lim > 80) state.msgLimit = { id: id, n: lim }; // keep the jump's history on live refreshes
    var path = "/api/conversations/" + encodeURIComponent(id) + "/messages?limit=" + lim;
    if (state.folder) {
      var fc = state.convs.find(function (x) { return x.ConversationID === id; });
      if (fc && !fc.local) {
        if (!scroll) return Promise.resolve(); // live read: on open only
        path = "/api/app/folder/messages?conversation_id=" + encodeURIComponent(id);
      }
    }
    return api(path).then(function (msgs) {
      return Promise.resolve().then(function () {
        if (id !== state.current) return;
        state.serverMsgs = { id: id, msgs: (msgs || []).slice().reverse() };
        if (state.jump && state.jump.conv === id) state.jump.loaded = true;
        if (!state.folder) chime.noteMessages(id, msgs || []);
        if (!state.folder && msgs && msgs.length) patchConv(id, msgs.filter(function (m) { return !/^TOMBSTONE/i.test(m.Status || ""); })[0]);
        var merged = mergeLocal(id, state.serverMsgs.msgs);
        var sig = JSON.stringify(msgs || []) + "|" + localSig(id);
        if (quiet && !scroll && lastRender.id === id && lastRender.sig === sig) return;
        var changed = lastRender.id === id;
        lastRender.id = id; lastRender.sig = sig;
        state.lastMsgs = merged;
        renderMessages(merged, scroll);
        if (changed && !state.folder) refreshConvMeta(); // new messages: fresh lock / E2EE state
      });
    }).catch(function (e) { if (!quiet && e.message !== "login required") toast("Couldn't load messages: " + e.message, "error"); });
  }
  // ---------- attachments ----------
  // Media bytes come from OpenMessage's GET /api/media/<message id> (same
  // origin, so the session cookie authenticates it like api()).
  // Videos get an inline player and photos an inline image (tap for full
  // size); other attachments keep the text label.
  var VIDEO_EXT = /\.(mp4|m4v|mov|webm|3gp|3g2|mkv)(\?|#|$)/i;
  var IMAGE_EXT = /\.(jpe?g|png|gif|webp|bmp|avif|heic|heif)(\?|#|$)/i;
  var BARE_FILENAME = /^[^\s\/\\]+\.[a-z0-9]{2,5}$/i;
  var GENERIC_MEDIA_BODY = { "[Photo]": 1, "[Video]": 1, "[Audio]": 1, "[Voice note]": 1, "[Sticker]": 1, "[Attachment]": 1, "[Image]": 1 };
  function mediaURL(m) { return m.previewURL || "/api/media/" + encodeURIComponent(m.MessageID); }
  function isVideoMessage(m) {
    if (!m || !m.MediaID) return false;
    var mime = String(m.MimeType || "").toLowerCase();
    if (mime.indexOf("video/") === 0) return true;
    if (mime && mime !== "application/octet-stream") return false;
    return VIDEO_EXT.test(String(m.Body || "").trim());
  }
  function isImageMessage(m) {
    if (!m || !m.MediaID) return false;
    var mime = String(m.MimeType || "").toLowerCase();
    if (mime.indexOf("image/") === 0) return true;
    if (mime && mime !== "application/octet-stream") return false;
    return IMAGE_EXT.test(String(m.Body || "").trim());
  }
  // Body text worth showing under a photo: not a generic "[Photo]"-style
  // placeholder and not just the file's name.
  function imageCaption(m) {
    var caption = String(m.Body || "").trim();
    if (!caption || GENERIC_MEDIA_BODY[caption] || BARE_FILENAME.test(caption)) return "";
    return caption;
  }
  function attachmentLink(m, fallbackType) {
    var a = iconEl("a", "body media-link", "attach", m.MimeType || fallbackType || "attachment");
    a.href = mediaURL(m);
    a.target = "_blank";
    a.rel = "noopener";
    return a;
  }
  // Inline player, kept across list refreshes (keyed by message id) so a
  // re-render from live updates doesn't restart or blank a playing video.
  function videoNode(m) {
    var key = "v:" + m.MessageID, cached = state.nodes[key];
    if (cached) return cached;
    var wrap = el("div", "media media-video");
    var v = document.createElement("video");
    v.controls = true;
    v.preload = "metadata";
    v.playsInline = true;
    v.setAttribute("playsinline", "");
    v.setAttribute("aria-label", "Video message");
    v.addEventListener("error", function () {
      // Unplayable / expired session / unsupported codec: back to a link.
      wrap.textContent = "";
      wrap.className = "media media-failed";
      wrap.appendChild(attachmentLink(m, "video"));
    });
    v.src = mediaURL(m);
    wrap.appendChild(v);
    state.nodes[key] = wrap;
    return wrap;
  }
  // Inline photo, cached like videoNode so live re-renders don't reload or
  // flicker it. Tapping opens the full-size viewer (an overlay, not a new
  // tab: the car browser handles tabs poorly).
  function imageNode(m) {
    var key = "i:" + m.MessageID, cached = state.nodes[key];
    if (cached) return cached;
    var wrap = el("button", "media media-image");
    wrap.type = "button";
    wrap.setAttribute("aria-label", "Open photo");
    var img = document.createElement("img");
    img.alt = "Photo";
    img.loading = "lazy";
    img.decoding = "async";
    img.addEventListener("load", function () {
      wrap.classList.add("loaded");
      // Keep the thread pinned to the bottom if it was before the photo grew.
      var box = $("messages");
      if (wrap.isConnected && box.scrollHeight - box.scrollTop - box.clientHeight - wrap.offsetHeight < 200) box.scrollTop = box.scrollHeight;
    });
    img.addEventListener("error", function () {
      // Expired session / missing media / format the browser can't show
      // (e.g. HEIC): back to the attachment link, like the video player.
      var failed = el("div", "media media-failed");
      failed.appendChild(attachmentLink(m, "image"));
      state.nodes[key] = failed;
      if (wrap.parentNode) wrap.parentNode.replaceChild(failed, wrap);
    });
    wrap.addEventListener("click", function () { openImageViewer(m); });
    img.src = mediaURL(m);
    wrap.appendChild(img);
    state.nodes[key] = wrap;
    return wrap;
  }
  // Full-screen photo viewer: tap anywhere or press Escape to close.
  function openImageViewer(m) {
    var v = $("imageViewer"), img = $("imageViewerImg");
    img.src = mediaURL(m);
    var cap = imageCaption(m);
    $("imageViewerCaption").textContent = cap;
    $("imageViewerCaption").hidden = !cap;
    v.hidden = false;
    $("imageViewerClose").focus();
  }
  function closeImageViewer() {
    var v = $("imageViewer");
    if (v.hidden) return false;
    v.hidden = true;
    $("imageViewerImg").removeAttribute("src");
    return true;
  }

  // ---------- links & previews ----------
  // URL detection follows the desktop page (URL_MATCH_REGEX /
  // trimTrailingURLPunctuation / normalizeDetectedURL). Preview cards come from
  // OpenMessage's existing GET /api/link-preview?url= (fetched server-side,
  // SSRF-guarded and cached there; thumbnails are proxied through the same-
  // origin /api/link-preview-image), so the browser never contacts other sites.
  // (Unlike the desktop regex, the scheme/www prefix is an optional prefix of
  // the host rather than an alternative to it, so "https://go.dev" is one link.)
  var URL_RE = /((?:https?:\/\/|www\.)?(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}(?::\d{2,5})?(?:[\/?#][^\s<]*)?)/ig;
  var PREVIEW_MAX_INFLIGHT = 3;
  var PREVIEW_RETRY_MS = 10 * 60 * 1000;
  var previewCache = {};                 // url -> { pending, data, expires, waiters }
  var previewQueue = [], previewInflight = 0;
  function trimURLPunctuation(raw) {
    var core = raw || "", trailing = "";
    while (core) {
      var last = core.charAt(core.length - 1);
      if (/[.,!?;:]/.test(last)) { trailing = last + trailing; core = core.slice(0, -1); continue; }
      if (last === ")") {
        var opens = (core.match(/\(/g) || []).length, closes = (core.match(/\)/g) || []).length;
        if (closes > opens) { trailing = last + trailing; core = core.slice(0, -1); continue; }
      }
      break;
    }
    return { core: core, trailing: trailing };
  }
  function normalizeURL(raw) {
    var t = String(raw || "").trim();
    if (!t) return "";
    return /^[a-z][a-z0-9+.-]*:\/\//i.test(t) ? t : "https://" + t;
  }
  // Split text into [{text}|{url, text}] pieces.
  function splitLinks(text) {
    var out = [], last = 0, m;
    URL_RE.lastIndex = 0;
    while ((m = URL_RE.exec(text)) !== null) {
      if (m.index && /[@\w]/.test(text.charAt(m.index - 1))) continue; // e.g. user@example.com
      var t = trimURLPunctuation(m[0]);
      if (!t.core) continue;
      if (m.index > last) out.push({ text: text.slice(last, m.index) });
      out.push({ url: normalizeURL(t.core), text: t.core });
      last = m.index + t.core.length;
    }
    if (last < text.length) out.push({ text: text.slice(last) });
    return out;
  }
  function bodyNode(text) {
    var d = el("div", "body");
    splitLinks(String(text || "")).forEach(function (p) {
      if (!p.url) { d.appendChild(document.createTextNode(p.text)); return; }
      var a = el("a", "msg-link", p.text);
      a.href = p.url;
      a.target = "_blank";
      a.rel = "noopener noreferrer";
      d.appendChild(a);
    });
    return d;
  }
  function firstURL(text) {
    var parts = splitLinks(String(text || ""));
    for (var i = 0; i < parts.length; i++) if (parts[i].url && /^https?:\/\//i.test(parts[i].url)) return parts[i].url;
    return "";
  }
  function previewCached(url) {
    var e = previewCache[url];
    if (!e || e.pending) return undefined;
    if (e.data) return e.data;
    return Date.now() < e.expires ? null : undefined;
  }
  function previewSettle(url, data) {
    var e = previewCache[url], waiters = (e && e.waiters) || [];
    var ok = data && typeof data === "object" && (data.title || data.description || data.image_url || data.site_name);
    previewCache[url] = { pending: false, data: ok ? data : null, expires: Date.now() + PREVIEW_RETRY_MS, waiters: [] };
    waiters.forEach(function (fn) { try { fn(); } catch (x) {} });
  }
  function previewPump() {
    while (previewInflight < PREVIEW_MAX_INFLIGHT && previewQueue.length) {
      (function (url) {
        previewInflight++;
        api("/api/link-preview?url=" + encodeURIComponent(url))
          .then(function (d) { previewSettle(url, d); }, function () { previewSettle(url, null); })
          .then(function () { previewInflight--; previewPump(); });
      })(previewQueue.shift());
    }
  }
  function previewRequest(url, onDone) {
    if (previewCached(url) !== undefined) return;
    var e = previewCache[url];
    if (!e || !e.pending) {
      e = previewCache[url] = { pending: true, data: null, expires: 0, waiters: [] };
      previewQueue.push(url);
      previewPump();
    }
    e.waiters.push(onDone);
  }
  function fillPreviewCard(slot, url, p) {
    slot.textContent = "";
    var a = el("a", "link-card");
    a.href = p.url || url;
    a.target = "_blank";
    a.rel = "noopener noreferrer";
    var img = String(p.image_url || "");
    if (img && img.charAt(0) === "/" && img.charAt(1) !== "/") {
      var media = el("div", "link-card-media");
      var im = el("img");
      im.alt = "";
      im.loading = "lazy";
      im.decoding = "async";
      im.referrerPolicy = "no-referrer";
      im.addEventListener("error", function () { if (media.parentNode) media.parentNode.removeChild(media); });
      im.src = img;
      media.appendChild(im);
      a.appendChild(media);
    }
    var copy = el("div", "link-card-copy");
    var site = p.site_name || p.domain || "";
    if (site) copy.appendChild(el("div", "link-card-site", fontSafe(site)));
    if (p.title) copy.appendChild(el("div", "link-card-title", fontSafe(p.title)));
    if (p.description) copy.appendChild(el("div", "link-card-desc", fontSafe(p.description)));
    a.appendChild(copy);
    slot.appendChild(a);
  }
  // Preview slot for a message, reused across re-renders (keyed by message id
  // + url) so live refreshes don't blank or reload the card.
  function previewNode(m, url) {
    var key = "p:" + m.MessageID + "|" + url, slot = state.nodes[key];
    if (slot) return slot;
    slot = el("div", "link-card-slot");
    state.nodes[key] = slot;
    var fill = function () {
      var d = previewCached(url);
      if (!d) return;
      var box = $("messages");
      var nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 200;
      fillPreviewCard(slot, url, d);
      if (nearBottom && slot.isConnected) box.scrollTop = box.scrollHeight;
    };
    if (previewCached(url)) fill(); else previewRequest(url, fill);
    return slot;
  }
  function appendBodyWithPreview(bubble, m, text) {
    bubble.appendChild(bodyNode(text));
    var url = firstURL(text);
    if (url) bubble.appendChild(previewNode(m, url));
  }

  // ---------- replies ----------
  // Mirrors the desktop page: the target is { MessageID, SenderName, Body },
  // the strip shows "name: first 80 chars", text sends carry reply_to_id, and
  // quotes above replies show the original's sender + first 60 chars (or
  // "Original message" when it isn't among the loaded messages).
  var REPLY_ICON = "M10 9V5l-7 7 7 7v-4.1c5 0 8.5 1.6 11 5.1-1-5-4-10-11-11z";
  function svgIcon(path, cls) {
    var ns = "http://www.w3.org/2000/svg";
    var svg = document.createElementNS(ns, "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("aria-hidden", "true");
    if (cls) svg.setAttribute("class", cls);
    var p = document.createElementNS(ns, "path");
    p.setAttribute("d", path);
    svg.appendChild(p);
    return svg;
  }
  function msgSnippetSource(m) {
    if (isImageMessage(m)) return imageCaption(m) || "Photo";
    return m.Body || (m.MediaID || m.MimeType ? "Media" : "");
  }
  function msgAuthor(m) {
    if (m.IsFromMe) return "You";
    if (m.SenderName) return m.SenderName;
    var c = state.convs.find(function (x) { return x.ConversationID === state.current; });
    return c && !c.IsGroup ? convName(c) : "Them";
  }
  function setReplyTo(m) {
    state.replyTo = { MessageID: m.MessageID, SenderName: msgAuthor(m), Body: msgSnippetSource(m) };
    $("replyName").textContent = state.replyTo.SenderName;
    $("replySnippet").textContent = (state.replyTo.Body || "Media").replace(/\s+/g, " ").substring(0, 80);
    var box = $("messages"), nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 200;
    $("replyBar").hidden = false;
    if (nearBottom) box.scrollTop = box.scrollHeight;
    $("input").focus();
  }
  function clearReply() {
    state.replyTo = null;
    $("replyBar").hidden = true;
  }
  function replyQuote(m, byID) {
    var orig = byID[String(m.ReplyToID)];
    var q = el("div", "reply-quote" + (orig ? "" : " reply-quote-missing"));
    var body = orig ? (msgSnippetSource(orig) || "Media") : "Original message";
    body = body.replace(/\s+/g, " ");
    if (orig) q.appendChild(el("span", "reply-quote-name", msgAuthor(orig)));
    q.appendChild(el("span", "reply-quote-text", body.length > 60 ? body.substring(0, 60) + "…" : body));
    if (orig) {
      q.addEventListener("click", function () {
        var rows = $("messages").querySelectorAll(".msg-row");
        for (var i = 0; i < rows.length; i++) {
          if (rows[i].dataset.id === String(m.ReplyToID)) {
            rows[i].scrollIntoView({ block: "center", behavior: "smooth" });
            rows[i].classList.add("flash");
            (function (r) { setTimeout(function () { r.classList.remove("flash"); }, 1200); })(rows[i]);
            break;
          }
        }
      });
    }
    return q;
  }

  // ---------- pending send placeholders ----------
  // A send is stored by the server under its temporary ID ("tm-..." from this
  // app, "tmp_..." from server/MCP sends) with
  // status OUTGOING_SENDING until Google's real copy replaces it. If both are
  // ever present (the server cleanup missed it), show only the real one, by
  // the same rule the server uses (db.OutgoingPlaceholderMatches): same
  // conversation, both from me, placeholder still sending, within 3 minutes,
  // and the same trimmed text or media on both sides with a compatible type.
  var PLACEHOLDER_WINDOW_MS = 3 * 60 * 1000;
  function isSendPlaceholder(m) {
    var s = String(m.Status || "").toUpperCase();
    return !!m.IsFromMe && /^(tm-|tmp_)/.test(String(m.MessageID || "")) &&
      !/FAIL|CANCEL/.test(s) && /SENDING|YET_TO_SEND|VALIDATING|AWAITING_RETRY/.test(s);
  }
  function mimeMajor(t) {
    t = String(t || "").toLowerCase().split(";")[0].trim();
    return !t || t === "application/octet-stream" ? "" : t.split("/")[0];
  }
  function placeholderMatches(p, r) {
    if (!r.IsFromMe || /^(tm-|tmp_)/.test(String(r.MessageID || "")) || p.ConversationID !== r.ConversationID) return false;
    if (!p.TimestampMS || !r.TimestampMS || Math.abs(p.TimestampMS - r.TimestampMS) > PLACEHOLDER_WINDOW_MS) return false;
    var pm = !!String(p.MediaID || "").trim(), rm = !!String(r.MediaID || "").trim();
    if (pm && rm) { var a = mimeMajor(p.MimeType), b = mimeMajor(r.MimeType); return !a || !b || a === b; }
    if (pm || rm) return false;
    var body = String(p.Body || "").trim();
    return !!body && body === String(r.Body || "").trim();
  }
  // Drops placeholders that have a real copy; one real message hides at most
  // one placeholder, closest timestamps first. Unmatched ones stay visible.
  function dropEchoedPlaceholders(msgs) {
    var pending = msgs.filter(isSendPlaceholder);
    if (!pending.length) return msgs;
    var pairs = [];
    pending.forEach(function (p) {
      msgs.forEach(function (r) { if (r !== p && placeholderMatches(p, r)) pairs.push([p, r, Math.abs(p.TimestampMS - r.TimestampMS)]); });
    });
    pairs.sort(function (x, y) { return x[2] - y[2]; });
    var drop = [], usedR = [];
    pairs.forEach(function (pr) {
      if (drop.indexOf(pr[0]) >= 0 || usedR.indexOf(pr[1]) >= 0) return;
      drop.push(pr[0]); usedR.push(pr[1]);
    });
    return drop.length ? msgs.filter(function (m) { return drop.indexOf(m) < 0; }) : msgs;
  }

  var RUN_GAP_MS = 15 * 60 * 1000;
  function runKey(m) { return m.IsFromMe ? "me" : String(m.SenderNumber || m.SenderName || "them"); }
  function sameRun(a, b) {
    return !!a && !!b && runKey(a) === runKey(b) && Math.abs((b.TimestampMS || 0) - (a.TimestampMS || 0)) < RUN_GAP_MS &&
      new Date(a.TimestampMS).toDateString() === new Date(b.TimestampMS).toDateString();
  }
  // Small round photo of a group message's sender (initial-letter fallback).
  function senderAvatar(m, c) {
    var name = m.SenderName || m.SenderNumber || "?";
    var av = el("div", "msg-avatar");
    var bg = "hsl(" + hue(m.SenderNumber || name) + " 45% 38%)";
    var num = normalizeParticipantIdentifier(m.SenderNumber);
    var p = conversationParticipants(c).filter(function (x) { return x && !isMe(x); }).find(function (x) {
      return (num && (normalizeParticipantIdentifier(x.number) === num || normalizeParticipantIdentifier(x.phone) === num)) ||
        (m.SenderName && (x.name || x.full_name || x.display_name) === m.SenderName);
    });
    var r = p ? refsFor([p]) : { source: "sms", participantIDs: [], contactIDs: [], numbers: m.SenderNumber ? [m.SenderNumber] : [] };
    r.key = avatarLookupKey(r);
    var letter = (String(name).replace(/[^\p{L}\p{N}]/gu, "").charAt(0) || "#").toUpperCase();
    var lookups = r.key && sourcePlatformOf(c) === "sms" ? [r] : [];
    paintAvatar(av, lookups, letter, bg);
    if (lookups.length) avatarRequest(r, function () { if (av.isConnected) paintAvatar(av, lookups, letter, bg); });
    av.title = name;
    return av;
  }
  function renderMessages(msgs, forceScroll) {
    if (swipe.active && swipe.mode) { lastRender.sig = ""; return; } // mid-swipe: the next refresh renders
    msgs = dropEchoedPlaceholders(msgs);
    var box = $("messages");
    var nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 200;
    box.textContent = "";
    var c = state.convs.find(function (x) { return x.ConversationID === state.current; });
    var lastDay = "";
    var byID = {};
    msgs.forEach(function (m) { if (m && m.MessageID) byID[String(m.MessageID)] = m; });
    state.byID = byID;
    var lastMine = -1;
    msgs.forEach(function (m, i) { if (m.IsFromMe && !/^TOMBSTONE/i.test(m.Status || "")) lastMine = i; });
    state.mineSending = false;
    var menuOpenFor = state.menuFor && state.menuFor.msg.MessageID, menuStillThere = false;
    var desk = isDesk(), groupChat = !!(c && c.IsGroup);
    msgs.forEach(function (m, idx) {
      var day = new Date(m.TimestampMS).toDateString();
      if (day !== lastDay) { box.appendChild(el("div", "day-sep", dayLabel(m.TimestampMS))); lastDay = day; }
      var row = el("div", "msg-row " + (m.IsFromMe ? "me" : "them"));
      // Non-car: Google Messages-style runs (grouped corners, sender name
      // and photo on the first received bubble of a run in group chats).
      // (Car Mode too, at car sizes.)
      var prevSame = idx > 0 && sameRun(msgs[idx - 1], m);
      var nextSame = idx < msgs.length - 1 && sameRun(m, msgs[idx + 1]);
      row.classList.add(prevSame ? (nextSame ? "grp-mid" : "grp-last") : (nextSame ? "grp-first" : "grp-solo"));
      if (scrollState.unreadFromID && m.MessageID === scrollState.unreadFromID && scrollState.conv === state.current) box.appendChild(el("div", "unread-sep", "Unread"));
      if (groupChat && !m.IsFromMe && !prevSame) box.appendChild(el("div", "run-sender", m.SenderName || m.SenderNumber || "Unknown"));
      row.dataset.id = m.MessageID;
      var bubble = el("div", "bubble");
      if (m.ReplyToID) bubble.appendChild(replyQuote(m, byID));

      if (isVideoMessage(m)) {
        bubble.classList.add("has-media");
        bubble.appendChild(videoNode(m));
        var caption = String(m.Body || "").trim();
        if (caption && !GENERIC_MEDIA_BODY[caption] && !VIDEO_EXT.test(caption)) appendBodyWithPreview(bubble, m, m.Body);
      } else if (isImageMessage(m)) {
        bubble.classList.add("has-media", "has-image");
        bubble.appendChild(imageNode(m));
        if (imageCaption(m)) appendBodyWithPreview(bubble, m, m.Body);
      } else if (m.Body) {
        appendBodyWithPreview(bubble, m, m.Body);
      } else {
        bubble.appendChild(m.MediaID ? iconEl("div", "body media-label", "attach", m.MimeType || "attachment") : el("div", "body", ""));
      }
      var meta = el("div", "meta", clockTime(m.TimestampMS) + (m.IsFromMe && /FAIL/.test(m.Status || "") ? " · Failed" : ""));
      if (state.stars[m.MessageID]) { var sm = el("span", "star-mark", "\u2605"); sm.setAttribute("aria-label", "Starred"); meta.insertBefore(sm, meta.firstChild); }
      if (state.selecting && state.selected[m.MessageID]) row.classList.add("selected");
      if (isDesk()) row.appendChild(timeLabel(m));
      bubble.appendChild(meta);
      var rx = reactionsNode(m);
      if (rx) bubble.appendChild(rx);
      var mb = el("button", "msg-menu-btn");
      mb.appendChild(icon("more"));
      mb.type = "button";
      mb.setAttribute("aria-label", "More options");
      mb.setAttribute("aria-haspopup", "menu");
      mb.title = "More";
      mb.addEventListener("click", function () {
        if (state.menuFor && state.menuFor.msg.MessageID === m.MessageID) closeMsgMenu(); else openMsgMenu(mb, m);
      });
      if (menuOpenFor && menuOpenFor === m.MessageID) {
        menuStillThere = true;
        mb.classList.add("open"); mb.setAttribute("aria-expanded", "true");
        state.menuFor.btn = mb;
      }
      if (!state.folder) bindLongPress(bubble, m, mb);
      // Sent messages get the ⋮ menu too (left of the bubble).
      if (m.IsFromMe && !isSendPlaceholder(m) && !m.local) row.appendChild(mb);
      row.appendChild(bubble);
      if (!m.IsFromMe) {
        var rb = el("button", "reply-btn");
        rb.type = "button";
        rb.setAttribute("aria-label", "Reply to " + msgAuthor(m));
        rb.title = "Reply";
        rb.appendChild(svgIcon(REPLY_ICON));
        rb.addEventListener("click", function () { setReplyTo(m); });
        row.appendChild(rb);
        row.appendChild(mb);
      }
      if (groupChat && !m.IsFromMe) {
        var av = prevSame ? el("div", "msg-avatar spacer") : senderAvatar(m, c);
        row.insertBefore(av, row.firstChild);
      }
      box.appendChild(row);
      if (state.detailFor === m.MessageID && isDesk()) box.appendChild(msgDetail(m, c, groupChat));
      var ls = localStatus(m);
      if (m.local && !/FAIL/.test(m.Status)) state.mineSending = true;
      if (ls) {
        var lsn = el(ls.retry ? "button" : "div", "msg-status" + (ls.cls ? " " + ls.cls : ""), ls.text);
        if (ls.retry) { lsn.type = "button"; lsn.title = m.error || "Tap to send again"; lsn.addEventListener("click", function () { retrySend(m); }); }
        box.appendChild(lsn);
      } else if (idx === lastMine) {
        if (isSendPlaceholder(m) || /SENDING|YET_TO_SEND|VALIDATING|PROCESSING|AWAITING_RETRY/.test(String(m.Status || "").toUpperCase())) state.mineSending = true;
        var sn = statusNode(m, c, groupChat);
        if (sn) box.appendChild(sn);
      }
    });
    if (menuOpenFor && !menuStillThere) closeMsgMenu();
    renderTypingRow(false);
    if (state.jump) { if (forceScroll || nearBottom) box.scrollTop = box.scrollHeight; finishJump(); noteTail(msgs); return; }
    applyScroll(msgs, forceScroll, nearBottom);
  }
  // ---------- scroll position ----------
  // Opening a conversation starts at its first unread message (with an
  // "Unread" divider) or at the bottom when everything's read or the last
  // message is yours. Afterwards: your own sends always scroll to the
  // bottom; incoming messages scroll only if you're already near the
  // bottom, otherwise a "New messages" pill appears. "Read up to" is
  // remembered per conversation on this device (when you reach the bottom).
  var SEEN_KEY = "tm.seen.v1", seenTS = {};
  try { seenTS = JSON.parse(localStorage.getItem(SEEN_KEY) || "{}") || {}; } catch (e) { seenTS = {}; }
  var scrollState = { conv: null };
  // ---------- read state on the phone ----------
  // Tells the server (and through it Google Messages, which clears the
  // phone's notifications) that the open chat was read up to its latest
  // message: when it's opened, when new messages arrive while it's scrolled
  // to the end, and when the tab comes back. Never while the page is hidden
  // or for folders; debounced and sent once per latest message.
  var readSync = { timer: 0, sent: {}, openConv: "" };
  function syncReadSoon() {
    clearTimeout(readSync.timer);
    readSync.timer = setTimeout(syncReadNow, 700);
  }
  function syncReadNow() {
    var id = state.current;
    if (!id || state.folder || document.visibilityState !== "visible") return;
    if (scrollState.conv !== id || scrollState.pendingOpen) return; // not rendered yet
    var t = state.lastMsgs && realTail(state.lastMsgs);
    if (!t || !t.MessageID) return;
    var box = $("messages");
    var atEnd = box && box.scrollHeight - box.scrollTop - box.clientHeight < 120;
    if (!atEnd && readSync.openConv !== id) return;
    var key = String(t.MessageID);
    readSync.openConv = "";
    if (readSync.sent[id] === key) return;
    readSync.sent[id] = key;
    postJSON("/api/mark-read", { conversation_id: id, google: true }).catch(function () {
      if (readSync.sent[id] === key) delete readSync.sent[id];
    });
  }
  function markSeen(convID, ts) {
    if (convID && convID === state.current) syncReadSoon();
    if (!convID || !ts || (seenTS[convID] || 0) >= ts) return;
    seenTS[convID] = ts;
    var keys = Object.keys(seenTS);
    if (keys.length > 400) keys.sort(function (a, b) { return seenTS[a] - seenTS[b]; }).slice(0, keys.length - 400).forEach(function (k) { delete seenTS[k]; });
    try { localStorage.setItem(SEEN_KEY, JSON.stringify(seenTS)); } catch (e) {}
  }
  function beginScrollState(id, c, readOnly) {
    if (scrollState.conv === id && !scrollState.pendingOpen) return; // re-open of the open chat
    scrollState = { conv: id, pendingOpen: true, unread: !readOnly && !!(c && c.UnreadCount > 0), seenAt: seenTS[id] || 0, tailID: "", unreadFromID: "", pill: 0 };
    showNewPill(0);
  }
  function realTail(msgs) {
    for (var i = msgs.length - 1; i >= 0; i--) if (!/^TOMBSTONE/i.test(msgs[i].Status || "")) return msgs[i];
    return null;
  }
  function noteTail(msgs) { var t = realTail(msgs); scrollState.tailID = t ? String(t.MessageID) : ""; }
  function firstUnread(msgs, seenAt) {
    var i, from = -1;
    if (seenAt) {
      for (i = 0; i < msgs.length; i++) if (!msgs[i].IsFromMe && !/^TOMBSTONE/i.test(msgs[i].Status || "") && msgs[i].TimestampMS > seenAt) { from = i; break; }
    } else {
      // Never opened here: the incoming run after your last message.
      for (i = msgs.length - 1; i >= 0 && !msgs[i].IsFromMe; i--) if (!/^TOMBSTONE/i.test(msgs[i].Status || "")) from = i;
    }
    return from >= 0 ? msgs[from] : null;
  }
  function rowFor(id) {
    var rows = $("messages").querySelectorAll(".msg-row");
    for (var i = 0; i < rows.length; i++) if (rows[i].dataset.id === String(id)) return rows[i];
    return null;
  }
  function scrollToBottom() {
    var box = $("messages");
    box.scrollTop = box.scrollHeight;
    showNewPill(0);
    var t = state.lastMsgs && realTail(state.lastMsgs);
    if (t && state.current && !state.folder) markSeen(state.current, t.TimestampMS);
  }
  function applyScroll(msgs, forceScroll, nearBottom) {
    var box = $("messages"), sc = scrollState;
    if (sc.conv !== state.current) { beginScrollState(state.current, null, !!state.folder); sc = scrollState; }
    var tail = realTail(msgs);
    if (sc.pendingOpen) {
      if (!msgs.length) { box.scrollTop = box.scrollHeight; return; }
      sc.pendingOpen = false;
      var target = sc.unread && tail && !tail.IsFromMe ? firstUnread(msgs, sc.seenAt) : null;
      if (target) {
        sc.unreadFromID = String(target.MessageID);
        // Re-render once so the divider sits above it, then place it near the top.
        renderMessages(msgs, false);
        var row = rowFor(target.MessageID), sep = box.querySelector(".unread-sep");
        var anchor = sep || row;
        if (anchor) {
          box.scrollTop = Math.max(0, anchor.offsetTop - box.offsetTop - 16);
          noteTail(msgs);
          syncReadSoon();
          if (box.scrollHeight - box.scrollTop - box.clientHeight < 200) scrollToBottom();
          return;
        }
      }
      noteTail(msgs);
      scrollToBottom();
      return;
    }
    var newTail = tail && sc.tailID && String(tail.MessageID) !== sc.tailID;
    var mineTail = tail && tail.IsFromMe;
    if (forceScroll || (newTail && mineTail)) scrollToBottom();
    else if (newTail) {
      if (nearBottom) scrollToBottom();
      else {
        var n = 0;
        for (var i = msgs.length - 1; i >= 0 && String(msgs[i].MessageID) !== sc.tailID; i--) if (!msgs[i].IsFromMe && !/^TOMBSTONE/i.test(msgs[i].Status || "")) n++;
        showNewPill(sc.pill + Math.max(1, n));
      }
    } else if (nearBottom) { box.scrollTop = box.scrollHeight; if (tail && !state.folder) markSeen(state.current, tail.TimestampMS); }
    noteTail(msgs);
  }
  function showNewPill(n) {
    var p = $("newMsgPill");
    if (!p) return;
    scrollState.pill = n;
    if (!n) { p.hidden = true; return; }
    $("newMsgPillText").textContent = n === 1 ? "1 new message" : n + " new messages";
    var box = $("messages");
    p.style.top = Math.max(0, box.offsetTop + box.clientHeight - 80) + "px";
    p.hidden = false;
  }
  // ---------- read receipts (Google Messages-style) ----------
  // Under your latest sent message only, as in Google Messages: a clock
  // while sending, one check in a ring once sent, two overlapping ringed
  // checks once delivered, two filled check circles once read, and a red
  // "!" if it failed. In RCS groups Google's status text names who has
  // read it ("Read by Alice, Bob"): until everyone has, the line shows
  // their photos and names instead of the read icon. A tap on a bubble
  // (non-car) shows its status and time in words.
  var RC_KINDS = { sending: "Sending…", sent: "Sent", delivered: "Delivered", read: "Read", failed: "Not sent" };
  function statusKind(m) {
    var s = String(m.Status || "").toUpperCase();
    if (s === "OUTGOING_DISPLAYED") return "read";
    if (s === "OUTGOING_DELIVERED") return "delivered";
    if (s === "OUTGOING_COMPLETE" || s === "OUTGOING_NOT_DELIVERED_YET") return "sent";
    if (/FAIL|CANCELED/.test(s)) return "failed";
    if (/SENDING|YET_TO_SEND|VALIDATING|PROCESSING|AWAITING_RETRY|REVOCATION/.test(s) || isSendPlaceholder(m)) {
      // Google accepted our POST but hasn't echoed its copy yet: sent.
      return state.posted[m.MessageID] ? "sent" : "sending";
    }
    return "";
  }
  function svgEl(tag, attrs) {
    var e = document.createElementNS("http://www.w3.org/2000/svg", tag);
    Object.keys(attrs).forEach(function (k) { e.setAttribute(k, attrs[k]); });
    return e;
  }
  function rcCircle(kind) {
    var c = el("span", "rc " + kind);
    var svg = svgEl("svg", { viewBox: "0 0 24 24", "aria-hidden": "true" });
    svg.appendChild(svgEl("path", { d: "M6.5 12.5l3.6 3.6L17.6 8.4" }));
    c.appendChild(svg);
    return c;
  }
  function statusIcon(kind) {
    var w = el("span", "rcpt rcpt-" + kind);
    w.setAttribute("role", "img");
    w.setAttribute("aria-label", RC_KINDS[kind]);
    w.title = RC_KINDS[kind];
    if (kind === "sending" || kind === "failed") {
      var c = el("span", "rc " + (kind === "failed" ? "bad" : "ring"));
      var svg = svgEl("svg", { viewBox: "0 0 24 24", "aria-hidden": "true" });
      svg.appendChild(svgEl("path", { d: kind === "failed" ? "M12 6.5v7M12 17.3v.2" : "M12 7.5V12l3 2" }));
      c.appendChild(svg);
      w.appendChild(c);
    } else {
      var fill = kind === "read" ? "fill" : "ring";
      w.appendChild(rcCircle(fill));
      if (kind !== "sent") w.appendChild(rcCircle(fill));
    }
    return w;
  }
  // Names from Google's "Read by Alice, Bob" / "Seen by Alice" (null: not
  // that kind of text). Real data: first names exactly as in the group's
  // participants (first_name).
  function readByNames(m) {
    var r = /^(?:read|seen) by\s+(.+)$/i.exec(String(m.status_text || "").trim());
    if (!r) return null;
    return r[1].split(/\s*,\s*|\s+and\s+|\s*&\s*/).map(function (x) { return x.trim(); }).filter(Boolean);
  }
  // The group member Google means by a "Read by" name: its first name (as
  // on the phone), else the first word of the full name, else the number.
  function memberByName(c, name) {
    var others = conversationParticipants(c).filter(function (x) { return x && !isMe(x); });
    var lo = name.toLowerCase(), num = normalizeParticipantIdentifier(name);
    var tests = [
      function (p) { return String(p.first_name || "").toLowerCase() === lo; },
      function (p) { return String(p.name || "").toLowerCase() === lo; },
      function (p) { return String(p.name || "").split(/\s+/)[0].toLowerCase() === lo; },
      function (p) { return num.length >= 7 && normalizeParticipantIdentifier(p.number) === num; }
    ];
    for (var i = 0; i < tests.length; i++) { var hit = others.find(tests[i]); if (hit) return hit; }
    return null;
  }
  function statusNode(m, c, groupChat) {
    var s = String(m.Status || "").toUpperCase();
    if (s === "OUTGOING_SCHEDULED") return el("div", "msg-status", "Scheduled");
    var kind = statusKind(m);
    if (!kind) return null;
    var box = el("div", "msg-status rcpt-row");
    var names = groupChat && kind !== "failed" && kind !== "sending" ? readByNames(m) : null;
    var others = groupChat ? currentMembers(c).others.length : 0;
    if (names && names.length && !(others && names.length >= others)) {
      box.classList.add("read-by");
      var avs = el("span", "rb-avs");
      names.slice(0, 4).forEach(function (n) {
        var p = memberByName(c, n);
        var av = senderAvatar({ SenderName: p ? (p.name || n) : n, SenderNumber: p ? p.number : "" }, c);
        av.className = "rb-av";
        av.title = n;
        avs.appendChild(av);
      });
      box.appendChild(avs);
      box.appendChild(el("span", "rb-text", String(m.status_text).trim()));
      return box;
    }
    box.appendChild(statusIcon(names && names.length ? "read" : kind));
    return box;
  }
  // Tap details (non-car): status in words + time (+ lock), under the bubble.
  function msgDetail(m, c, groupChat) {
    var d = el("div", "msg-detail " + (m.IsFromMe ? "me" : "them"));
    var parts = [];
    if (m.IsFromMe) {
      var kind = statusKind(m);
      var label = String(m.status_text || "").trim() || RC_KINDS[kind] || "";
      if (label) parts.push(label);
    }
    var t = new Date(m.TimestampMS);
    parts.push((t.toDateString() === new Date().toDateString() ? "" : dayLabel(m.TimestampMS) + ", ") + clockTime(m.TimestampMS));
    d.appendChild(el("span", "", parts.join(" · ")));
    if (msgEncrypted(m)) d.appendChild(lockIcon());
    return d;
  }
  function toggleMsgDetail(row, m) {
    var box = $("messages"), old = box.querySelector(".msg-detail");
    if (old) old.remove();
    if (state.detailFor === m.MessageID) { state.detailFor = null; return; }
    state.detailFor = m.MessageID;
    var c = state.convs.find(function (x) { return x.ConversationID === state.current; });
    var d = msgDetail(m, c, !!(c && c.IsGroup));
    box.insertBefore(d, row.nextSibling);
    if (box.scrollHeight - box.scrollTop - box.clientHeight < d.offsetHeight + 80) box.scrollTop = box.scrollHeight;
  }
  // Status under your latest sent message, from Google's MessageStatusType
  // (stored per message and updated live when Google pushes the change).
  function sentStatus(status) {
    var s = String(status || "").toUpperCase();
    if (s === "OUTGOING_DISPLAYED") return { text: "Read", cls: "read" };
    if (s === "OUTGOING_DELIVERED") return { text: "Delivered" };
    if (s === "OUTGOING_COMPLETE" || s === "OUTGOING_NOT_DELIVERED_YET") return { text: "Sent" };
    if (s === "OUTGOING_SCHEDULED") return { text: "Scheduled" };
    if (/FAIL|CANCELED/.test(s)) return null; // the bubble already says "Failed"
    if (/SENDING|YET_TO_SEND|VALIDATING|PROCESSING|AWAITING_RETRY|REVOCATION/.test(s)) return { text: "Sending…" };
    return null;
  }

  // ---------- compose ----------
  function autosize() {
    var t = $("input");
    t.style.height = "auto";
    t.style.height = Math.min(t.scrollHeight, 260) + "px";
    // No sending while dictating: finish (Done) and review first.
    $("sendBtn").disabled = (!t.value.trim() && !state.pending.length) || state.transcribing || !!state.rec;
  }
  // ---------- optimistic sends ----------
  // Tapping Send clears the box and shows the bubble ("Sending…") right
  // away, before any network call. The bubble is a local message whose id
  // is the send's idempotency key, which is also the id the server stores
  // its placeholder under, so once the server has it (same id) or Google's
  // real copy arrives (same rule as dropEchoedPlaceholders) the local one
  // is dropped: no duplicates. Status then comes from the server rows via
  // the normal refresh path (live events / polling / followSend), and a
  // successful POST (Google accepted it) shows "Sent" meanwhile. Errors
  // leave a "Failed – tap to retry" bubble. Photos/videos show a local
  // preview (object URL) until the server has the real one.
  state.local = {};    // convID -> [local message]
  state.posted = {};   // idempotency key -> true once POST /api/send(-media) succeeded
  state.dismissed = {}; // failed rows replaced by a retry (this session)
  function localSig(convID) {
    return JSON.stringify((state.local[convID] || []).map(function (l) { return [l.MessageID, l.Status, !!state.posted[l.MessageID]]; })) +
      JSON.stringify(Object.keys(state.posted)) + JSON.stringify(Object.keys(state.dismissed));
  }
  function dropLocal(l) {
    var arr = state.local[l.ConversationID] || [];
    var i = arr.indexOf(l);
    if (i >= 0) arr.splice(i, 1);
    if (l.previewURL) setTimeout(function () { try { URL.revokeObjectURL(l.previewURL); } catch (e) {} }, 60000);
  }
  // Server messages (oldest first) + this conversation's local sends that
  // the server doesn't have yet.
  function mergeLocal(convID, msgs) {
    msgs = msgs.filter(function (m) { return !state.dismissed[m.MessageID]; });
    var loc = (state.local[convID] || []).slice();
    if (!loc.length) return msgs;
    var byID = {}, used = [], hide = {};
    msgs.forEach(function (m) { byID[m.MessageID] = m; });
    loc.forEach(function (l) {
      var srv = byID[l.MessageID];
      if (srv) {
        // Server placeholder for a photo: keep the local preview until
        // Google's real copy (with the real media) replaces it.
        if (l.previewURL && isSendPlaceholder(srv)) { hide[srv.MessageID] = 1; return; }
        dropLocal(l); return;
      }
      var echo = msgs.filter(function (r) { return used.indexOf(r) < 0 && placeholderMatches(l, r); })[0];
      if (echo) { used.push(echo); dropLocal(l); }
    });
    loc = state.local[convID] || [];
    return msgs.filter(function (m) { return !hide[m.MessageID]; }).concat(loc);
  }
  function renderCurrent(scroll) {
    var id = state.current;
    if (!id) return;
    var base = state.serverMsgs && state.serverMsgs.id === id ? state.serverMsgs.msgs : [];
    lastRender.id = id; lastRender.sig = ""; // the next refresh always renders
    renderMessages(mergeLocal(id, base), scroll);
  }
  function localStatus(m) {
    if (m.local) {
      if (/FAIL/.test(m.Status)) return { text: "Failed – tap to retry", cls: "failed", retry: true };
      return state.posted[m.MessageID] ? { text: "Sent" } : { text: "Sending…" };
    }
    // A server row for a text send that failed (stored under our key): retry too.
    if (m.IsFromMe && /^tm-/.test(String(m.MessageID || "")) && /FAIL/i.test(m.Status || "") && String(m.Body || "").trim() && !m.MediaID)
      return { text: "Failed – tap to retry", cls: "failed", retry: true };
    return null;
  }
  function postLocal(l) {
    var convID = l.ConversationID;
    var p = l.file
      ? api("/api/send-media", { method: "POST", body: l.form() })
      : postJSON("/api/send", l.req);
    p.then(function () {
      state.posted[l.MessageID] = true;
      if (state.current === convID) { renderCurrent(false); loadMessages(false, true); }
      loadConversations();
      followSend(convID);
    }).catch(function (e) {
      if (e.message === "login required") return;
      l.Status = "OUTGOING_FAILED";
      l.error = e.message;
      if (state.current === convID) renderCurrent(false);
      toast("Not sent: " + e.message, "error");
    });
  }
  function retrySend(m) {
    var convID = m.ConversationID || state.current;
    if (m.local) dropLocal(m); else state.dismissed[m.MessageID] = true;
    var nm = newLocal(convID, m.Body || "", m.ReplyToID || "", m.file || null, m.caption || "");
    if (state.current === convID) renderCurrent(true);
    postLocal(nm);
  }
  function newLocal(convID, text, replyID, file, caption) {
    var key = sendKey();
    var l = { MessageID: key, ConversationID: convID, IsFromMe: true, Body: file ? caption : text, TimestampMS: Date.now(),
      Status: "OUTGOING_SENDING", ReplyToID: replyID || "", local: true };
    if (file) {
      var type = String(file.type || "");
      l.file = file; l.caption = caption;
      l.MediaID = "local"; l.MimeType = type || "application/octet-stream";
      if (/^(image|video)\//.test(type)) { try { l.previewURL = URL.createObjectURL(file); } catch (e) {} }
      l.form = function () {
        var form = new FormData();
        form.append("conversation_id", convID);
        form.append("idempotency_key", key);
        form.append("file", file, file.name || "attachment");
        if (replyID) form.append("reply_to_id", replyID);
        if (caption) form.append("caption", caption);
        return form;
      };
    } else {
      l.req = { conversation_id: convID, message: text, idempotency_key: key };
      if (replyID) l.req.reply_to_id = replyID;
    }
    (state.local[convID] || (state.local[convID] = [])).push(l);
    patchConv(convID, l);
    return l;
  }
  function sendMessage(ev) {
    if (ev) ev.preventDefault();
    var t = $("input"), text = t.value.trim();
    if ((!text && !state.pending.length) || !state.current || state.rec || state.transcribing) return;
    if (state.pending.length) {
      // Pasted / dropped attachments go first, through the same path as
      // the attach button (the first one takes the caption where the
      // platform has captions); text that's left goes as its own message.
      var files = state.pending.map(function (p) { return p.file; });
      clearPending();
      files.forEach(sendMedia);
      text = t.value.trim();
      showEmoji(false);
      if (!text) { autosize(); return; }
    }
    var reply = state.replyTo;
    var l = newLocal(state.current, text, reply ? reply.MessageID : "", null, "");
    t.value = ""; lastSel = null; autosize();
    if (reply) clearReply();
    showEmoji(false);
    renderCurrent(true);   // the bubble shows before any network call
    postLocal(l);
  }
  // ---------- attachments (send) ----------
  // Mirrors the desktop page's POST /api/send-media FormData: conversation_id,
  // idempotency_key, file (+ name), and caption / reply_to_id only where the
  // desktop sends them (captions on WhatsApp non-audio and Signal media, media
  // replies on WhatsApp/Signal). Other routes send the file alone; any typed
  // text stays in the box to send as its own message.
  var MAX_MEDIA_BYTES = 128 << 20; // server's messaging.DefaultMaxMediaBytes
  function sendKey() { return "tm-" + Date.now() + "-" + Math.random().toString(36).slice(2, 10); }
  function onAttachTap() {
    if (!state.current) return;
    var f = $("fileInput");
    f.value = "";
    f.click();
  }
  // ---------- (+) attach sheet + gallery button (non-car composer) ----------
  // Google Messages-style: (+) in the pill opens a bottom sheet (Gallery,
  // Camera, Files: the options this app can really send); the pill's photo
  // button opens the photo/video picker directly. Picked files land in the
  // attachment tray above the box and go out with Send. The sheet closes on
  // a tap outside, on (x), or once an option is picked.
  function showPlusSheet(on) {
    var sh = $("plusSheet"), b = $("plusBtn");
    if (on && (!state.current || document.body.classList.contains("readonly-thread"))) return;
    sh.hidden = !on;
    b.classList.toggle("open", !!on);
    b.setAttribute("aria-expanded", on ? "true" : "false");
    b.setAttribute("aria-label", on ? "Close attachment options" : "More attachment options");
    $("threadView").classList.toggle("plus-open", !!on);
    if (on) showEmoji(false);
  }
  function pickInto(id) {
    showPlusSheet(false);
    if (!state.current) return;
    var f = $(id);
    f.value = "";
    f.click();
  }
  function initPlusSheet() {
    $("plusBtn").addEventListener("click", function () { showPlusSheet($("plusSheet").hidden); });
    $("galleryBtn").addEventListener("click", function () { pickInto("galleryInput"); });
    $("plusGallery").addEventListener("click", function () { pickInto("galleryInput"); });
    $("plusCamera").addEventListener("click", function () { pickInto("cameraInput"); });
    $("plusFiles").addEventListener("click", function () { pickInto("filesInput"); });
    ["galleryInput", "cameraInput", "filesInput"].forEach(function (id) {
      $(id).addEventListener("change", function () {
        var files = Array.prototype.slice.call(this.files || []);
        this.value = "";
        if (files.length && takeFiles(files)) $("input").focus();
      });
    });
    document.addEventListener("pointerdown", function (e) {
      if ($("plusSheet").hidden || $("plusSheet").contains(e.target) || $("plusBtn").contains(e.target)) return;
      showPlusSheet(false);
    }, true);
    document.addEventListener("keydown", function (e) { if (e.key === "Escape" && !$("plusSheet").hidden) showPlusSheet(false); });
  }
  function onFilePicked() {
    var f = $("fileInput"), file = f.files && f.files[0];
    f.value = "";
    if (file) sendMedia(file);
  }

  // ---------- quick replies ----------
  // One-tap canned replies above the message box (per device: on/off and
  // the texts, in Settings). A tap sends right away through the normal
  // optimistic path (as a reply when one is selected); typed text stays.
  var QUICK_DEFAULTS = ["Driving, will text soon", "On my way", "OK", "Call you later"];
  function quickList(s) {
    s = s || loadSettings();
    var l = Array.isArray(s.quickReplies) ? s.quickReplies.filter(function (x) { return typeof x === "string" && x.trim(); }) : null;
    return l && l.length ? l : QUICK_DEFAULTS.slice();
  }
  function quickOn(s) { return (s || loadSettings()).quickRepliesOn !== false; }
  function renderQuickReplies() {
    var box = $("quickReplies"), s = loadSettings();
    box.textContent = "";
    var show = quickOn(s) && !!state.current && !state.folder;
    if (show) quickList(s).forEach(function (txt) {
      var b = el("button", "quick-reply", txt);
      b.type = "button";
      b.addEventListener("mousedown", function (e) { e.preventDefault(); }); // keep the keyboard / caret
      b.addEventListener("click", function () { sendQuick(txt); });
      box.appendChild(b);
    });
    box.hidden = !show;
  }
  function sendQuick(text) {
    text = String(text || "").trim();
    if (!text || !state.current || state.rec || state.transcribing) return;
    var reply = state.replyTo;
    var l = newLocal(state.current, text, reply ? reply.MessageID : "", null, "");
    if (reply) clearReply();
    renderCurrent(true);
    postLocal(l);
  }
  function initQuickReplies() {
    $("setQuickReplies").addEventListener("click", function () {
      var st = loadSettings();
      st.quickRepliesOn = !quickOn(st);
      saveSettings(st);
      applySettings();
    });
    var ed = $("quickRepliesEdit"), t = null;
    var save = function () {
      var lines = ed.value.split("\n").map(function (x) { return x.trim().slice(0, 160); }).filter(Boolean).slice(0, 12);
      var st = loadSettings();
      st.quickReplies = lines.length ? lines : null;
      saveSettings(st);
      renderQuickReplies();
    };
    ed.addEventListener("input", function () { clearTimeout(t); t = setTimeout(save, 400); });
    ed.addEventListener("blur", function () { clearTimeout(t); save(); });
    $("quickRepliesReset").addEventListener("click", function () {
      var st = loadSettings();
      st.quickReplies = null;
      saveSettings(st);
      ed.value = QUICK_DEFAULTS.join("\n");
      renderQuickReplies();
    });
  }

  // ---------- reactions ----------
  // Long-press (touch), right-click or the ⋮ button opens the message menu,
  // whose top row reacts (POST /api/react: Google Messages via libgm, or
  // WhatsApp/Signal). "+" opens the emoji panel to pick any emoji.
  // Reactions show as pills on bubbles and update with the live refresh.
  var QUICK_REACTIONS = ["\ud83d\udc4d", "\u2764\ufe0f", "\ud83d\ude02", "\ud83d\ude2e", "\ud83d\ude22", "\ud83d\ude4f"];
  function parseReactions(m) {
    var r = m && m.Reactions;
    if (!r) return [];
    if (typeof r === "string") { try { r = JSON.parse(r); } catch (e) { return []; } }
    return Array.isArray(r) ? r.filter(function (x) { return x && x.emoji; }) : [];
  }
  // Image reactions (Google Messages "custom"/Emotify reactions) arrive as a
  // UUID: Google only sends the picture's path on the phone, so they show as
  // a picture icon, never the raw ID. Long ASCII tokens (":custom:" from
  // other bridges) get the same icon.
  var REACTION_UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  var CUSTOM_REACTION_ICON = "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z";
  function isCustomReaction(r) {
    var e = String((r && r.emoji) || "").trim();
    return !!(r && r.custom) || REACTION_UUID_RE.test(e) || /^:?[a-z0-9_-]{6,}:?$/i.test(e);
  }
  function reactionText(r) { return isCustomReaction(r) ? "Custom reaction" : String(r.emoji); }
  function reactionGlyph(r) {
    if (!isCustomReaction(r)) return document.createTextNode(String(r.emoji));
    var s = el("span", "reaction-custom");
    s.setAttribute("role", "img");
    s.setAttribute("aria-label", "Custom reaction");
    s.appendChild(svgIcon(CUSTOM_REACTION_ICON));
    return s;
  }
  // Who reacted, as names (actors are Google participant IDs).
  function reactorNames(r, convID) {
    var actors = Array.isArray(r.actors) ? r.actors : [];
    if (!actors.length) return [];
    var c = state.convs.find(function (x) { return x.ConversationID === convID; });
    var people = c ? conversationParticipants(c) : [];
    return actors.map(function (id) {
      var p = people.find(function (q) { return q && String(q.id) === String(id); });
      return p ? (isMe(p) ? "You" : (p.name || p.number || "")) : "";
    }).filter(Boolean);
  }
  function reactionsNode(m) {
    var list = parseReactions(m);
    if (!list.length) return null;
    var box = el("div", "reactions");
    var convID = m.ConversationID || state.current;
    list.forEach(function (r) {
      var n = Number(r.count) || 1;
      var p = el("span", "reaction");
      p.appendChild(reactionGlyph(r));
      if (n > 1) p.appendChild(el("span", "reaction-count", String(n)));
      var names = reactorNames(r, convID);
      p.title = (isCustomReaction(r) ? "Custom reaction · " : "") + (names.length ? names.join(", ") : n + " reaction" + (n > 1 ? "s" : ""));
      box.appendChild(p);
    });
    return box;
  }
  function sendReaction(m, emoji) {
    if (!m || !emoji) return;
    var convID = m.ConversationID || state.current;
    postJSON("/api/react", { conversation_id: convID, message_id: m.MessageID, emoji: emoji, action: "add" }).then(function () {
      if (state.current === convID) loadMessages(false, true);
    }).catch(function (e) { if (e.message !== "login required") toast("Couldn't react: " + e.message, "error"); });
  }
  function initReactions() {
    var row = $("msgMenuReact");
    QUICK_REACTIONS.forEach(function (e) {
      var b = el("button", "react-pick", e);
      b.type = "button";
      b.setAttribute("role", "menuitem");
      b.setAttribute("aria-label", "React " + e);
      b.addEventListener("click", function () {
        var f = state.menuFor;
        closeMsgMenu();
        if (f) sendReaction(f.msg, e);
      });
      row.appendChild(b);
    });
    var more = el("button", "react-pick react-more", "+");
    more.type = "button";
    more.setAttribute("role", "menuitem");
    more.setAttribute("aria-label", "More reactions");
    more.addEventListener("click", function () {
      var f = state.menuFor;
      closeMsgMenu();
      if (!f) return;
      state.reactFor = f.msg;
      showEmoji(true);
    });
    row.appendChild(more);
  }
  // Long-press / right-click on a bubble opens its menu.
  function bindLongPress(bubble, m, btn) {
    var timer = null, x0 = 0, y0 = 0, fired = false;
    var cancel = function () { clearTimeout(timer); timer = null; bubble.classList.remove("longpress"); };
    bubble.addEventListener("pointerdown", function (e) {
      if (e.pointerType === "mouse" || isSendPlaceholder(m) || m.local || state.selecting) return;
      fired = false; x0 = e.clientX; y0 = e.clientY;
      bubble.classList.add("longpress");
      timer = setTimeout(function () {
        timer = null; fired = true; bubble.classList.remove("longpress");
        if (navigator.vibrate) { try { navigator.vibrate(15); } catch (er) {} }
        openMsgMenu(btn, m);
      }, 480);
    });
    bubble.addEventListener("pointermove", function (e) { if (timer && (Math.abs(e.clientX - x0) > 12 || Math.abs(e.clientY - y0) > 12)) cancel(); });
    bubble.addEventListener("pointerup", cancel);
    bubble.addEventListener("pointercancel", cancel);
    bubble.addEventListener("pointerleave", cancel);
    // The tap that ends a long-press must not also open a photo or link.
    bubble.addEventListener("click", function (e) { if (fired) { fired = false; e.preventDefault(); e.stopPropagation(); } }, true);
    // A plain tap (non-car) shows the message's status and time in words.
    bubble.addEventListener("click", function (e) {
      if (!isDesk() || state.selecting || e.defaultPrevented || m.local || isSendPlaceholder(m)) return;
      if (e.target.closest("a, button, img, video, audio, input, .reply-quote, .reactions")) return;
      if (window.getSelection && String(window.getSelection()).length) return;
      var row = bubble.closest(".msg-row");
      if (row) toggleMsgDetail(row, m);
    });
    bubble.addEventListener("contextmenu", function (e) {
      // Android fires contextmenu after our long-press timer already opened
      // the menu: swallow it instead of reopening / toggling.
      if (fired || (sheetOpen() && Date.now() - (state.sheetOpenedAt || 0) < 1500)) { e.preventDefault(); return; }
      if (isSendPlaceholder(m) || m.local || state.selecting) return;
      if (window.getSelection && String(window.getSelection()).length) return; // let text copy work
      e.preventDefault();
      cancel();
      openMsgMenu(btn, m);
    });
  }

  // ---------- search ----------
  // Searches conversation names/participants and message text in the local
  // database (GET /api/search and /api/search/messages). A message result
  // opens its conversation scrolled to that message.
  var search = { q: "", timer: null, seq: 0 };
  function highlight(text, q) {
    var frag = document.createDocumentFragment();
    text = String(text || "");
    var lo = text.toLowerCase(), ql = q.toLowerCase(), i = 0, j;
    if (!ql) { frag.appendChild(document.createTextNode(text)); return frag; }
    // Show the part around the first hit for long messages.
    var first = lo.indexOf(ql);
    if (first > 60) { text = "…" + text.slice(first - 40); lo = text.toLowerCase(); }
    while ((j = lo.indexOf(ql, i)) >= 0) {
      if (j > i) frag.appendChild(document.createTextNode(text.slice(i, j)));
      frag.appendChild(el("mark", "", text.slice(j, j + ql.length)));
      i = j + ql.length;
    }
    if (i < text.length) frag.appendChild(document.createTextNode(text.slice(i)));
    return frag;
  }
  function runSearch(q) {
    var seq = ++search.seq, box = $("searchResults");
    q = q.trim();
    search.q = q;
    document.body.classList.toggle("searching", !!q);
    box.hidden = !q;
    if (!q) { box.textContent = ""; return; }
    if (q.length < 2) { box.textContent = ""; box.appendChild(el("div", "conv-empty", "Keep typing…")); return; }
    var enc = encodeURIComponent(q);
    Promise.all([
      api("/api/search?q=" + enc + "&limit=20").catch(function () { return []; }),
      api("/api/search/messages?q=" + enc + "&limit=60").catch(function (e) { return { error: e.message }; }),
    ]).then(function (res) {
      if (seq !== search.seq) return;
      var convs = Array.isArray(res[0]) ? res[0] : [], msgs = Array.isArray(res[1]) ? res[1] : [];
      var ql = q.toLowerCase();
      // Name matches (server + the loaded list), then message hits.
      var byID = {}, named = [];
      state.convs.forEach(function (c) { byID[c.ConversationID] = c; if (convName(c).toLowerCase().indexOf(ql) >= 0) named.push(c); });
      convs.forEach(function (c) {
        if (named.some(function (x) { return x.ConversationID === c.ConversationID; })) return;
        if (String(c.Name || c.unified_name || "").toLowerCase().indexOf(ql) >= 0 || String(c.Participants || "").toLowerCase().indexOf(ql) >= 0) named.push(c);
        if (!byID[c.ConversationID]) byID[c.ConversationID] = c;
      });
      box.textContent = "";
      if (named.length) {
        box.appendChild(el("div", "search-section", "Conversations"));
        named.slice(0, 12).forEach(function (c) {
          var b = el("button", "conv search-hit");
          b.type = "button";
          var mid = el("div", "conv-mid");
          var nm = el("div", "conv-name"); nm.appendChild(highlight(convName(c), q)); mid.appendChild(nm);
          b.appendChild(mid);
          b.addEventListener("click", function () { clearSearch(); openConversation(c.ConversationID, convName(c)); });
          box.appendChild(b);
        });
      }
      if (msgs.length) {
        box.appendChild(el("div", "search-section", "Messages"));
        msgs.forEach(function (m) {
          var c = byID[m.ConversationID];
          var b = el("button", "conv search-hit");
          b.type = "button";
          var mid = el("div", "conv-mid");
          mid.appendChild(el("div", "conv-name", c ? convName(c) : (m.SenderName || "Conversation")));
          var pv = el("div", "conv-preview");
          if (m.IsFromMe) pv.appendChild(document.createTextNode("You: "));
          else if (c && c.IsGroup && m.SenderName) pv.appendChild(document.createTextNode(m.SenderName + ": "));
          pv.appendChild(highlight(m.Body || "", q));
          mid.appendChild(pv);
          b.appendChild(mid);
          var right = el("div", "conv-right");
          right.appendChild(el("div", "conv-time", dayLabel(m.TimestampMS)));
          b.appendChild(right);
          b.addEventListener("click", function () { jumpToMessage(m, c ? convName(c) : ""); });
          box.appendChild(b);
        });
      }
      if (!named.length && !msgs.length) box.appendChild(el("div", "conv-empty", res[1] && res[1].error ? "Search failed: " + res[1].error : "No results for “" + q + "”"));
    });
  }
  function clearSearch() {
    var i = $("convSearch");
    if (i.value) i.value = "";
    runSearch("");
  }
  // Open a conversation with enough history loaded to show message m, then
  // scroll to it and flash it.
  function jumpToMessage(m, name) {
    var convID = m.ConversationID;
    var go = function (limit) {
      state.jump = { conv: convID, id: m.MessageID, limit: limit };
      if (state.current === convID) loadMessages(false); else openConversation(convID, name);
      if (isNarrow()) clearSearch();
    };
    api("/api/conversations/" + encodeURIComponent(convID) + "/messages?limit=1000&after=" + Math.max(1, (m.TimestampMS || 0) - 1))
      .then(function (after) { go(Math.min(1000, Math.max(80, (after || []).length + 20))); }, function () { go(200); });
  }
  function isNarrow() { return !document.documentElement.classList.contains("desk") || window.innerWidth < 900; }
  function finishJump() {
    var j = state.jump;
    if (!j || j.conv !== state.current || !j.loaded) return;
    state.jump = null;
    var rows = $("messages").querySelectorAll(".msg-row");
    for (var i = 0; i < rows.length; i++) {
      if (rows[i].dataset.id === String(j.id)) {
        var r = rows[i];
        r.scrollIntoView({ block: "center" });
        r.classList.add("flash");
        setTimeout(function () { r.classList.remove("flash"); }, 1600);
        return;
      }
    }
    toast("That message isn't loaded here.");
  }
  function initSearch() {
    var i = $("convSearch");
    i.addEventListener("input", function () {
      clearTimeout(search.timer);
      search.timer = setTimeout(function () { runSearch(i.value); }, 250);
    });
    i.addEventListener("keydown", function (e) {
      if (e.key === "Escape") { clearSearch(); i.blur(); }
      if (e.key === "Enter") { clearTimeout(search.timer); runSearch(i.value); }
    });
    i.addEventListener("search", function () { if (!i.value) clearSearch(); });
  }

  // ---------- health banner ----------
  // GET /api/app/health: Google connection / VPN checks from the server's
  // monitor (which also sends the Web Push alerts). Shown once a check has
  // been failing for 30 s.
  function pollHealth() {
    api("/api/app/health").then(function (h) {
      var now = Date.now(), lines = [];
      ((h && h.checks) || []).forEach(function (c) {
        if (c.ok || !c.down_since_ms || now - c.down_since_ms < 30000) return;
        var what = c.name === "vpn" ? "VPN is down" : "Google Messages is disconnected";
        lines.push(what + (c.detail ? " (" + c.detail + ")" : "") + " since " + clockTime(c.down_since_ms) + ". New messages may be delayed.");
      });
      var b = $("healthBanner");
      b.textContent = lines.join(" ");
      b.hidden = !lines.length;
    }).catch(function () {});
  }
  function initHealth() {
    pollHealth();
    setInterval(function () { if (!document.hidden) pollHealth(); }, 60000);
    document.addEventListener("visibilitychange", function () { if (!document.hidden) pollHealth(); });
  }


  // ---------- long-press menu, Google Messages style (non-car) ----------
  // Long-press / right-click / ⋮ in the compact (non-car) layout: the chat
  // blurs, a copy of the message sits on top with a reaction pill above it
  // and the actions below (or beside, when tall). Car Mode keeps msgMenu.
  var SHEET_REACTIONS = ["\ud83d\udc4d", "\u2764\ufe0f", "\ud83d\ude02", "\ud83d\ude2e", "\ud83d\ude22"];
  var ADD_REACTION_ICON = "M7 9.5C7 8.67 7.67 8 8.5 8s1.5.67 1.5 1.5S9.33 11 8.5 11 7 10.33 7 9.5zm5 8c2.33 0 4.31-1.46 5.11-3.5H6.89c.8 2.04 2.78 3.5 5.11 3.5zm3.5-6.5c.83 0 1.5-.67 1.5-1.5S16.33 8 15.5 8 14 8.67 14 9.5s.67 1.5 1.5 1.5zM22 1h-2v2h-2v2h2v2h2V5h2V3h-2V1zm-2 11c0 4.42-3.58 8-8 8s-8-3.58-8-8 3.58-8 8-8c1.46 0 2.82.4 4 1.08V2.84C14.77 2.3 13.42 2 11.99 2 6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12c0-1.05-.17-2.05-.47-3h-2.13c.38.93.6 1.94.6 3z";
  var STAR_OUTLINE = "M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 9.24zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z";
  var STAR_FILLED = "M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z";
  state.stars = {};
  function isDesk() { return document.documentElement.classList.contains("desk"); }
  function sheetOpen() { return !$("msgSheet").hidden; }
  function msgHasMedia(m) { return !!(m && m.MediaID && m.MediaID !== "local"); }
  function openMsgSheet(btn, m) {
    var row = btn && btn.closest ? btn.closest(".msg-row") : null;
    if (!row) {
      var rows = $("messages").querySelectorAll(".msg-row");
      for (var i = 0; i < rows.length; i++) if (rows[i].dataset.id === String(m.MessageID)) row = rows[i];
    }
    var bubble = row && row.querySelector(".bubble");
    if (!bubble) return;
    closeConvMenu();
    state.sheetFor = m;
    // The finger that long-pressed is still down: its release (pointerup,
    // click, Android's contextmenu) must not hit the backdrop or a menu item
    // that just appeared under it. Only a touch that starts now counts.
    state.sheetArmed = false;
    state.sheetOpenedAt = Date.now();
    var ro = document.body.classList.contains("readonly-thread") || !!state.folder;
    var body = String(m.Body || "").trim();
    $("msgxReact").hidden = ro;
    $("msgxReply").hidden = ro;
    $("msgxDelete").hidden = ro;
    $("msgxSelect").hidden = ro;
    $("msgxForward").hidden = !body && !msgHasMedia(m);
    $("msgxCopy").hidden = !body;
    $("msgxCopyUrl").hidden = !firstURL(body);
    var starred = !!state.stars[m.MessageID], st = $("msgxStar");
    st.querySelector("span").textContent = starred ? "Unstar" : "Star";
    st.querySelector("path").setAttribute("d", starred ? STAR_FILLED : STAR_OUTLINE);
    var clone = $("msgxClone");
    clone.textContent = "";
    clone.className = "msgx-clone msg-row " + (row.classList.contains("me") ? "me" : "them");
    clone.appendChild(bubble.cloneNode(true));
    var sheet = $("msgSheet");
    sheet.hidden = false;
    placeMsgSheet(bubble, row.classList.contains("me"));
    var first = sheet.querySelector(".msgx-item:not([hidden])");
    if (first && isDesk()) try { first.focus({ preventScroll: true }); } catch (e) {}
  }
  function placeMsgSheet(bubble, mine) {
    var vp = viewportProbe(), pr = vp.getBoundingClientRect();
    var k = pr.width ? vp.offsetWidth / pr.width : 1, vw = vp.offsetWidth, vh = vp.offsetHeight;
    var br = bubble.getBoundingClientRect();
    var r = { left: (br.left - pr.left) * k, top: (br.top - pr.top) * k, width: br.width * k, height: br.height * k };
    var react = $("msgxReact"), menu = $("msgxMenu"), clone = $("msgxClone");
    var pad = 20, gap = 16;
    var rh = react.hidden ? 0 : react.offsetHeight, rw = react.offsetWidth, mw = menu.offsetWidth, mh = menu.offsetHeight;
    var rgap = react.hidden ? 0 : gap;
    var hx = function (w, x0, wid) { var x = mine ? x0 + wid - w : x0; return Math.max(pad, Math.min(vw - pad - w, x)); };
    // Menu beside the message when stacking wouldn't fit and there's room.
    var stackH = rh + rgap + r.height + gap + mh;
    var besideX = mine ? r.left - gap - mw : r.left + r.width + gap;
    var beside = stackH > vh - 2 * pad && besideX >= pad && besideX + mw <= vw - pad;
    var bh = beside ? Math.min(r.height, vh - 2 * pad - rh - rgap) : Math.min(r.height, Math.max(60, vh - 2 * pad - rh - rgap - gap - mh));
    var gh = beside ? rh + rgap + Math.max(bh, mh) : rh + rgap + bh + gap + mh;
    var top = Math.max(pad, Math.min(vh - pad - gh, r.top - rh - rgap));
    react.style.left = hx(rw, r.left, r.width) + "px";
    react.style.top = top + "px";
    var bt = top + rh + rgap;
    clone.style.left = r.left + "px";
    clone.style.top = bt + "px";
    clone.style.width = r.width + "px";
    clone.style.height = bh + "px";
    if (beside) {
      menu.style.left = besideX + "px";
      menu.style.top = Math.max(pad, Math.min(vh - pad - mh, bt)) + "px";
    } else {
      menu.style.left = hx(mw, r.left, r.width) + "px";
      menu.style.top = (bt + bh + gap) + "px";
    }
  }
  function closeMsgSheet() {
    var s = $("msgSheet");
    if (s.hidden) return false;
    s.hidden = true;
    try { var sel = window.getSelection(); if (sel && $("msgxClone").contains(sel.anchorNode)) sel.removeAllRanges(); } catch (e) {}
    $("msgxClone").textContent = "";
    state.sheetFor = null;
    return true;
  }
  function sheetAction(fn) {
    return function () {
      var m = state.sheetFor;
      closeMsgSheet();
      if (m) fn(m);
    };
  }
  function copyText(t) {
    t = String(t || "");
    var fallback = function () {
      var ta = el("textarea");
      ta.value = t; ta.setAttribute("readonly", ""); ta.style.cssText = "position:fixed;left:-9999px;top:0";
      document.body.appendChild(ta); ta.select();
      var ok = false; try { ok = document.execCommand("copy"); } catch (e) {}
      ta.remove();
      toast(ok ? "Copied" : "Couldn't copy", ok ? "" : "error");
    };
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(t).then(function () { toast("Copied"); }, fallback);
    else fallback();
  }
  // Stars: kept on this server (Google Messages for Web has no star API).
  function loadStars(convID) {
    state.stars = {};
    api("/api/app/stars?conversation_id=" + encodeURIComponent(convID)).then(function (r) {
      if (state.current !== convID) return;
      state.stars = (r && r.stars) || {};
      if (Object.keys(state.stars).length) renderCurrent(false);
    }).catch(function () {});
  }
  function toggleStar(m) {
    var on = !state.stars[m.MessageID];
    var convID = m.ConversationID || state.current;
    if (on) state.stars[m.MessageID] = { conversation_id: convID }; else delete state.stars[m.MessageID];
    renderCurrent(false);
    postJSON("/api/app/stars", { message_id: m.MessageID, conversation_id: convID, starred: on }).then(function () {
      toast(on ? "Starred (on this server)" : "Unstarred");
    }).catch(function (e) {
      if (on) delete state.stars[m.MessageID]; else state.stars[m.MessageID] = { conversation_id: convID };
      renderCurrent(false);
      toast("Couldn't star: " + e.message, "error");
    });
  }
  // Info
  function protocolText(m) {
    var c = state.convs.find(function (x) { return x.ConversationID === (m.ConversationID || state.current); });
    var p = sourcePlatformOf(c);
    if (p !== "sms") return p.charAt(0).toUpperCase() + p.slice(1);
    var meta = state.convMeta || {};
    var proto = meta.protocol === "RCS" ? "RCS" : meta.protocol === "SMS" ? (msgHasMedia(m) ? "MMS" : "SMS") : "SMS/RCS (unknown)";
    return proto + (meta.e2ee ? " · end-to-end encrypted" : "");
  }
  function showInfo(m) {
    var dl = $("infoList");
    dl.textContent = "";
    var add = function (k, v) { if (!v) return; dl.appendChild(el("dt", "", k)); dl.appendChild(el("dd", "", v)); };
    var when = new Date(m.TimestampMS);
    var full = when.toLocaleString([], { weekday: "short", year: "numeric", month: "short", day: "numeric", hour: "numeric", minute: "2-digit", second: "2-digit" });
    var c = state.convs.find(function (x) { return x.ConversationID === (m.ConversationID || state.current); });
    add("Type", isVideoMessage(m) ? "Video" : isImageMessage(m) ? "Photo" : msgHasMedia(m) ? (m.MimeType || "Attachment") : "Text");
    if (m.IsFromMe) {
      add("From", "You");
      add("To", c ? convName(c) : "");
      add("Sent", full);
      var st = sentStatus(m.Status);
      add("Status", String(m.status_text || "").trim() || (st && st.text) || String(m.Status || "").replace(/^OUTGOING_/, "").replace(/_/g, " ").toLowerCase() || "unknown");
    } else {
      add("From", (m.SenderName || (c ? convName(c) : "")) + (m.SenderNumber && m.SenderNumber !== m.SenderName ? " (" + m.SenderNumber + ")" : ""));
      add("Received", full);
      add("Status", /READ|DISPLAYED/i.test(m.Status || "") ? "Read" : "Received");
    }
    add("Sent via", protocolText(m));
    var rxList = parseReactions(m), rxConv = m.ConversationID || state.current;
    if (rxList.length) {
      add("Reactions", rxList.map(function (r) {
        var n = Number(r.count) || 1, names = reactorNames(r, rxConv);
        return reactionText(r) + (names.length ? " (" + names.join(", ") + ")" : n > 1 ? " ×" + n : "");
      }).join(" · "));
    }
    if (state.stars[m.MessageID]) add("Starred", "Yes (on this server)");
    $("infoView").hidden = false;
    $("infoClose").focus();
  }
  // Forward: pick a conversation, then each message is sent there through
  // the normal optimistic path (media is re-uploaded from the server copy).
  function openForward(msgs) {
    state.fwd = msgs.filter(function (m) { return String(m.Body || "").trim() || msgHasMedia(m); });
    if (!state.fwd.length) { toast("Nothing to forward"); return; }
    $("fwdTitle").textContent = state.fwd.length > 1 ? "Forward " + state.fwd.length + " messages to" : "Forward to";
    $("fwdSearch").value = "";
    renderFwdList();
    $("fwdView").hidden = false;
    if (isDesk() && window.matchMedia && window.matchMedia("(pointer: fine)").matches) $("fwdSearch").focus();
  }
  function closeForward() { $("fwdView").hidden = true; state.fwd = null; }
  function renderFwdList() {
    var box = $("fwdList"), q = $("fwdSearch").value.trim().toLowerCase();
    box.textContent = "";
    var list = state.convs.filter(function (c) { return !q || convName(c).toLowerCase().indexOf(q) >= 0; }).slice(0, 80);
    if (!list.length) { box.appendChild(el("div", "conv-empty", "No matching conversations")); return; }
    list.forEach(function (c) {
      var b = el("button", "conv");
      b.type = "button";
      var mid = el("div", "conv-mid");
      mid.appendChild(el("div", "conv-name", convName(c)));
      b.appendChild(mid);
      b.addEventListener("click", function () { doForward(c); });
      box.appendChild(b);
    });
  }
  function doForward(c) {
    var msgs = (state.fwd || []).slice(), convID = c.ConversationID;
    closeForward();
    if (!msgs.length) return;
    msgs.sort(function (a, b) { return (a.TimestampMS || 0) - (b.TimestampMS || 0); });
    var chain = Promise.resolve(), sent = 0;
    msgs.forEach(function (m) {
      chain = chain.then(function () {
        if (msgHasMedia(m)) {
          return fetch(mediaURL(m), { credentials: "same-origin" }).then(function (r) {
            if (!r.ok) throw new Error("couldn't load the attachment");
            return r.blob();
          }).then(function (blob) {
            if (blob.size > MAX_MEDIA_BYTES) throw new Error("attachment too large");
            var type = blob.type || m.MimeType || "application/octet-stream";
            var ext = (type.split("/")[1] || "bin").replace(/[^a-z0-9].*$/i, "");
            postLocal(newLocal(convID, "", "", new File([blob], "forwarded." + ext, { type: type }), ""));
            sent++;
            var cap = String(m.Body || "").trim();
            if (cap && isImageMessage(m) && imageCaption(m)) postLocal(newLocal(convID, cap, "", null, ""));
          });
        }
        postLocal(newLocal(convID, String(m.Body).trim(), "", null, ""));
        sent++;
      });
    });
    chain.then(function () {
      toast(sent ? "Forwarded to " + convName(c) : "Nothing forwarded");
    }, function (e) { toast("Forward failed: " + e.message, "error"); })
      .then(function () { if (state.current === convID) renderCurrent(true); loadConversations(); });
  }
  // Select more: tap messages to pick them; copy / forward / delete.
  function startSelect(m) {
    state.selecting = true;
    state.selected = {};
    if (m) state.selected[m.MessageID] = true;
    document.body.classList.add("selecting");
    $("selectBar").hidden = false;
    syncSelection();
  }
  function stopSelect() {
    if (!state.selecting) return false;
    state.selecting = false;
    state.selected = {};
    document.body.classList.remove("selecting");
    $("selectBar").hidden = true;
    syncSelection();
    return true;
  }
  function selectedMsgs() {
    var by = state.byID || {};
    return Object.keys(state.selected || {}).map(function (id) { return by[id]; }).filter(Boolean)
      .sort(function (a, b) { return (a.TimestampMS || 0) - (b.TimestampMS || 0); });
  }
  function syncSelection() {
    var rows = $("messages").querySelectorAll(".msg-row");
    for (var i = 0; i < rows.length; i++) rows[i].classList.toggle("selected", !!(state.selecting && state.selected[rows[i].dataset.id]));
    var n = Object.keys(state.selected || {}).length;
    $("selCount").textContent = n + " selected";
    ["selCopy", "selForward", "selDelete"].forEach(function (id) { $(id).disabled = !n; });
  }
  function initMsgSheet() {
    var rx = $("msgxReact");
    SHEET_REACTIONS.forEach(function (e) {
      var b = el("button", "", e);
      b.type = "button";
      b.setAttribute("aria-label", "React " + e);
      b.addEventListener("click", sheetAction(function (m) { sendReaction(m, e); }));
      rx.appendChild(b);
    });
    var more = el("button");
    more.type = "button";
    more.setAttribute("aria-label", "More reactions");
    more.appendChild(svgIcon(ADD_REACTION_ICON));
    more.addEventListener("click", sheetAction(function (m) { state.reactFor = m; showEmoji(true); }));
    rx.appendChild(more);
    var sheetEl = $("msgSheet");
    sheetEl.addEventListener("pointerdown", function () { if (Date.now() - (state.sheetOpenedAt || 0) > 60) state.sheetArmed = true; }, true);
    sheetEl.addEventListener("click", function (e) {
      // e.detail 0: keyboard activation, always allowed.
      if (!state.sheetArmed && e.detail !== 0) { e.preventDefault(); e.stopPropagation(); }
    }, true);
    // The floating copy of the message is selectable (long-press it for
    // Android's Copy / Select all toolbar); elsewhere no native menu.
    sheetEl.addEventListener("contextmenu", function (e) { if (!$("msgxClone").contains(e.target)) e.preventDefault(); }, true);
    $("msgxBackdrop").addEventListener("click", closeMsgSheet);
    $("msgxBackdrop").addEventListener("contextmenu", function () {
      if (state.sheetArmed && Date.now() - (state.sheetOpenedAt || 0) > 800) closeMsgSheet();
    });
    $("msgxReply").addEventListener("click", sheetAction(setReplyTo));
    $("msgxForward").addEventListener("click", sheetAction(function (m) { openForward([m]); }));
    $("msgxCopyUrl").addEventListener("click", sheetAction(function (m) { copyText(firstURL(m.Body || "")); }));
    $("msgxCopy").addEventListener("click", sheetAction(function (m) { copyText(String(m.Body || "").trim()); }));
    $("msgxStar").addEventListener("click", sheetAction(toggleStar));
    $("msgxDelete").addEventListener("click", sheetAction(showConfirm));
    $("msgxSelect").addEventListener("click", sheetAction(startSelect));
    $("msgxInfo").addEventListener("click", sheetAction(showInfo));
    document.addEventListener("keydown", function (e) {
      if (e.key === "Escape" && sheetOpen()) { e.preventDefault(); closeMsgSheet(); }
      if ((e.key === "ArrowDown" || e.key === "ArrowUp") && sheetOpen()) {
        var items = Array.prototype.filter.call($("msgxMenu").querySelectorAll(".msgx-item"), function (b) { return !b.hidden; });
        var i = items.indexOf(document.activeElement);
        i = e.key === "ArrowDown" ? (i + 1) % items.length : (i <= 0 ? items.length - 1 : i - 1);
        if (items[i]) { e.preventDefault(); items[i].focus(); }
      }
    });
    window.addEventListener("resize", closeMsgSheet);
    $("infoClose").addEventListener("click", function () { $("infoView").hidden = true; });
    $("infoView").addEventListener("click", function (e) { if (e.target === $("infoView")) $("infoView").hidden = true; });
    $("fwdClose").addEventListener("click", closeForward);
    $("fwdView").addEventListener("click", function (e) { if (e.target === $("fwdView")) closeForward(); });
    $("fwdSearch").addEventListener("input", renderFwdList);
    $("selCancel").addEventListener("click", stopSelect);
    $("selCopy").addEventListener("click", function () {
      var list = selectedMsgs(), multi = list.length > 1;
      var txt = list.map(function (m) {
        var b = String(m.Body || "").trim() || (msgHasMedia(m) ? "[attachment]" : "");
        return multi ? "[" + clockTime(m.TimestampMS) + "] " + msgAuthor(m) + ": " + b : b;
      }).join("\n");
      copyText(txt);
      stopSelect();
    });
    $("selForward").addEventListener("click", function () { var l = selectedMsgs(); stopSelect(); openForward(l); });
    $("selDelete").addEventListener("click", function () {
      var l = selectedMsgs().filter(function (m) { return !m.local && !isSendPlaceholder(m); });
      if (l.length) showConfirm(l);
    });
    // In select mode a tap toggles the message instead of its normal action.
    $("messages").addEventListener("click", function (e) {
      if (!state.selecting) return;
      var row = e.target.closest(".msg-row");
      if (!row || !row.dataset.id) return;
      e.preventDefault(); e.stopPropagation();
      var id = row.dataset.id;
      if (state.selected[id]) delete state.selected[id]; else state.selected[id] = true;
      syncSelection();
    }, true);
  }


  // ---------- swipe gestures (non-car, touch) ----------
  // Left-to-right on a bubble: drag it right, a reply arrow appears; past
  // the threshold, release starts a quoted reply. Right-to-left anywhere:
  // every row shifts left and shows its time (+ lock when the chat is
  // end-to-end encrypted). Both spring back. The swipe only locks after
  // clear horizontal movement (vertical scrolling stays native via
  // touch-action: pan-y), never starts at the screen edges (Android back
  // gesture), and any movement cancels the long-press.
  var SWIPE_LOCK = 18, SWIPE_EDGE = 28, REPLY_AT = 96, REPLY_MAX = 170, TIME_MAX = 200;
  var swipe = { active: false };
  function swipeReset(animate) {
    var s = swipe, box = $("messages");
    swipe = { active: false };
    if (s.row) {
      var kids = Array.prototype.slice.call(s.row.children);
      kids.forEach(function (k) { if (k !== s.icon) { k.style.transition = animate ? "transform .22s ease" : ""; k.style.transform = ""; } });
      if (s.icon) { s.icon.style.transition = "opacity .15s"; s.icon.style.opacity = "0"; var ic = s.icon; setTimeout(function () { ic.remove(); }, 220); }
      setTimeout(function () { kids.forEach(function (k) { k.style.transition = ""; }); }, 240);
    }
    if (s.mode === "time") {
      box.classList.add("swipe-spring");
      box.style.setProperty("--swipe-x", "0px");
      setTimeout(function () { box.classList.remove("swipe-time", "swipe-spring"); }, 240);
    }
  }
  function timeLabel(m) {
    var lab = el("span", "swipe-time-label", clockTime(m.TimestampMS));
    if (msgEncrypted(m)) lab.appendChild(lockIcon());
    return lab;
  }
  function lockIcon() {
    var lock = svgIcon("M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zM9 6c0-1.66 1.34-3 3-3s3 1.34 3 3v2H9V6zm9 14H6V10h12v10zm-6-3c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2z");
    lock.setAttribute("aria-label", "End-to-end encrypted");
    return lock;
  }
  // Per-message lock: Google's per-message encryption flag (meta.e2ee_ids,
  // see client.MessageEncryption). Messages newer than the last meta load
  // follow the conversation until the next refresh; with only the
  // conversation-level "now encrypted" notice to go on, all messages do.
  function msgEncrypted(m) {
    var meta = state.convMeta;
    if (!meta || /^(tm-|tmp_)/.test(String(m.MessageID || "")) || m.local) return false;
    if (!state.encIDs || state.encIDsFor !== meta) {
      state.encIDs = {}; state.encIDsFor = meta;
      (meta.e2ee_ids || []).forEach(function (id) { state.encIDs[id] = 1; });
    }
    if (state.encIDs[m.MessageID]) return true;
    if (meta.e2ee_source === "messages") return !!meta.e2ee && (m.TimestampMS || 0) > (meta.loadedAt || 0) - 5000;
    return !!meta.e2ee;
  }
  function refreshLocks() {
    var box = $("messages");
    (state.lastMsgs || []).forEach(function (m) {
      var row = box.querySelector('.msg-row[data-id="' + String(m.MessageID).replace(/["\\]/g, "") + '"]');
      var lab = row && row.querySelector(".swipe-time-label");
      if (!lab) return;
      var has = !!lab.querySelector("svg"), want = msgEncrypted(m);
      if (want && !has) lab.appendChild(lockIcon());
      else if (!want && has) lab.querySelector("svg").remove();
    });
  }
  // Re-fetch the meta (locks, mute) without blanking it first.
  var metaRefreshT = null;
  function refreshConvMeta() {
    clearTimeout(metaRefreshT);
    metaRefreshT = setTimeout(function () {
      var id = state.current;
      if (!id) return;
      fetch("/api/app/conversations/meta?conversation_id=" + encodeURIComponent(id), { credentials: "same-origin" })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (m) { if (m && state.current === id) setConvMeta(m); })
        .catch(function () {});
    }, 1500);
  }
  function initSwipe() {
    var box = $("messages");
    box.addEventListener("pointerdown", function (e) {
      if (!isDesk() || e.pointerType === "mouse" || state.selecting || sheetOpen() || !e.isPrimary) return;
      var vw = window.innerWidth;
      if (e.clientX < SWIPE_EDGE || e.clientX > vw - SWIPE_EDGE) return; // Android back gesture zone
      var row = e.target.closest(".msg-row");
      swipe = { active: true, x0: e.clientX, y0: e.clientY, row: null, rowAt: row, mode: "", id: e.pointerId };
    });
    box.addEventListener("pointermove", function (e) {
      var s = swipe;
      if (!s.active || e.pointerId !== s.id) return;
      var k = (function () { var vp = viewportProbe(), pr = vp.getBoundingClientRect(); return pr.width ? vp.offsetWidth / pr.width : 1; })();
      var dx = (e.clientX - s.x0) * k, dy = (e.clientY - s.y0) * k;
      if (!s.mode) {
        if (Math.abs(dy) > SWIPE_LOCK && Math.abs(dy) > Math.abs(dx)) { swipe = { active: false }; return; } // vertical scroll
        if (Math.abs(dx) < SWIPE_LOCK || Math.abs(dx) < 1.6 * Math.abs(dy)) return;
        if (dx > 0) {
          var row = s.rowAt, m = row && state.byID && state.byID[row.dataset.id];
          var ro = document.body.classList.contains("readonly-thread") || !!state.folder;
          if (!row || !m || ro || m.local || isSendPlaceholder(m)) { swipe = { active: false }; return; }
          s.mode = "reply"; s.row = row; s.msg = m;
          s.icon = el("span", "swipe-reply-icon");
          s.icon.appendChild(svgIcon(REPLY_ICON));
          row.appendChild(s.icon);
        } else {
          s.mode = "time";
          box.classList.add("swipe-time");
        }
        s.x0 = e.clientX; dx = 0; // start from the lock point: no jump
      }
      if (s.mode === "reply") {
        var d = Math.max(0, Math.min(REPLY_MAX, dx < REPLY_AT ? dx : REPLY_AT + (dx - REPLY_AT) * 0.35));
        Array.prototype.forEach.call(s.row.children, function (c) { if (c !== s.icon) c.style.transform = "translateX(" + d + "px)"; });
        var p = Math.min(1, d / REPLY_AT);
        s.icon.style.opacity = String(p);
        s.icon.style.transform = "translateY(-50%) scale(" + (0.6 + 0.4 * p) + ")";
        var armed = d >= REPLY_AT;
        if (armed && !s.armed && navigator.vibrate) { try { navigator.vibrate(10); } catch (er) {} }
        s.armed = armed;
        s.icon.classList.toggle("armed", armed);
      } else if (s.mode === "time") {
        var t = Math.max(0, Math.min(TIME_MAX, -dx < TIME_MAX * 0.8 ? -dx : TIME_MAX * 0.8 + (-dx - TIME_MAX * 0.8) * 0.3));
        box.style.setProperty("--swipe-x", t + "px");
      }
      if (e.cancelable) e.preventDefault();
    });
    var end = function (e) {
      var s = swipe;
      if (!s.active || (e && e.pointerId !== s.id)) return;
      if (s.mode) {
        // Swallow the click that ends a swipe (photo viewer, links).
        var swallow = function (ev) { ev.preventDefault(); ev.stopPropagation(); };
        box.addEventListener("click", swallow, true);
        setTimeout(function () { box.removeEventListener("click", swallow, true); }, 0);
      }
      var reply = s.mode === "reply" && s.armed && e && e.type === "pointerup" ? s.msg : null;
      swipeReset(true);
      if (reply) setReplyTo(reply);
    };
    box.addEventListener("pointerup", end);
    box.addEventListener("pointercancel", end);
  }


  // ---------- floating composer (non-car) ----------
  // Measures everything stacked below the message list (reply preview,
  // dictation bar, emoji panel, quick replies, composer) into --dock-h, so
  // the list can run underneath them (see app.css) and still scroll its
  // last message clear of the composer. Keeps the list pinned to the
  // bottom when the dock grows (reply preview, keyboard).
  function initDock() {
    var tv = $("threadView"), box = $("messages"), last = -1;
    var measure = function () {
      var h = 0;
      if (isDesk()) {
        for (var n = box.nextElementSibling; n; n = n.nextElementSibling) {
          if (n.hidden || n.offsetParent === null) continue;
          var cs = getComputedStyle(n);
          h += n.offsetHeight + (parseFloat(cs.marginTop) || 0) + (parseFloat(cs.marginBottom) || 0);
        }
      }
      if (h === last) return;
      // "At the bottom" as of the last scroll: when the dock grows (e.g. the
      // attach sheet opens) the list has already shrunk by now.
      var atBottom = wasBottom || box.scrollHeight - box.scrollTop - box.clientHeight < 40;
      last = h;
      tv.style.setProperty("--dock-h", h + "px");
      if (atBottom) box.scrollTop = box.scrollHeight;
    };
    var wasBottom = true;
    box.addEventListener("scroll", function () { wasBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 40; }, { passive: true });
    if (window.ResizeObserver) {
      var ro = new ResizeObserver(measure);
      for (var n = box.nextElementSibling; n; n = n.nextElementSibling) ro.observe(n);
      ro.observe(tv);
    }
    new MutationObserver(measure).observe(tv, { attributes: true, subtree: true, attributeFilter: ["hidden"] });
    window.addEventListener("resize", measure);
    state.measureDock = measure;
    measure();
  }
  // Layout pass after opening a conversation (a normal tap and the
  // auto-reload's ?c= reopen alike): now, next frame and twice more while a
  // freshly loaded page settles (viewport / insets may still change).
  function relayoutSoon() {
    if (PREFS.settle) PREFS.settle();
    if (state.measureDock) {
      state.measureDock();
      requestAnimationFrame(function () { state.measureDock(); });
      setTimeout(function () { state.measureDock(); }, 300);
      setTimeout(function () { state.measureDock(); }, 1200);
    }
  }

  // ---------- pasted / dropped attachments ----------
  // Images, stickers and GIFs from the keyboard (Gboard commitContent, which
  // Chrome only delivers to contenteditable: see composer.js), the clipboard
  // or a desktop drag-drop wait in a tray above the box (preview chip with a
  // remove button) and go out on Send via sendMedia, like the attach button.
  state.pending = [];  // [{ file, url }]
  var MAX_PENDING = 10;
  function addPending(file) {
    if (!file || !state.current || document.body.classList.contains("readonly-thread")) return false;
    if (file.size > MAX_MEDIA_BYTES) { toast("That file is too large to send (limit 128 MB).", "error"); return false; }
    if (state.pending.length >= MAX_PENDING) { toast("Up to " + MAX_PENDING + " attachments at a time.", "error"); return false; }
    var type = String(file.type || "");
    if (!file.name || file.name === "image.png" && type !== "image/png") {
      var ext = (type.split("/")[1] || "bin").replace(/\+.*$/, "").replace("jpeg", "jpg");
      try { file = new File([file], "pasted-" + Date.now() + "." + ext, { type: type }); } catch (e) {}
    }
    var url = "";
    if (/^(image|video)\//.test(type)) { try { url = URL.createObjectURL(file); } catch (e) {} }
    state.pending.push({ file: file, url: url });
    renderTray();
    return true;
  }
  // A keyboard image that still landed in the box as <img src="blob:/data:">.
  function addPendingFromURL(src) {
    fetch(src).then(function (r) { return r.blob(); }).then(function (b) {
      if (!/^(image|video)\//.test(b.type || "")) return;
      addPending(new File([b], "", { type: b.type }));
    }).catch(function () { toast("Couldn't attach that image.", "error"); });
  }
  function removePending(i) {
    var p = state.pending.splice(i, 1)[0];
    if (p && p.url) { try { URL.revokeObjectURL(p.url); } catch (e) {} }
    renderTray();
  }
  function clearPending() {
    if (!state.pending.length) return;
    state.pending.forEach(function (p) { if (p.url) { try { URL.revokeObjectURL(p.url); } catch (e) {} } });
    state.pending = [];
    renderTray();
  }
  function renderTray() {
    var tray = $("attachTray");
    tray.textContent = "";
    state.pending.forEach(function (p, i) {
      var chip = el("div", "attach-chip");
      var type = String(p.file.type || "");
      if (p.url && type.indexOf("video/") === 0) {
        var v = document.createElement("video"); v.src = p.url; v.muted = true; v.playsInline = true; v.preload = "metadata";
        chip.appendChild(v);
      } else if (p.url) {
        var im = document.createElement("img"); im.src = p.url; im.alt = p.file.name || "Pasted image";
        chip.appendChild(im);
      } else chip.appendChild(el("span", "attach-chip-name", p.file.name || "Attachment"));
      var x = el("button", "attach-chip-x", "\u00d7");
      x.type = "button";
      x.setAttribute("aria-label", "Remove attachment");
      x.addEventListener("click", function (e) { e.preventDefault(); removePending(i); });
      chip.appendChild(x);
      tray.appendChild(chip);
    });
    tray.hidden = !state.pending.length;
    autosize();
  }
  // Files in a paste / drop / keyboard-insert DataTransfer.
  function transferFiles(dt) {
    if (!dt) return [];
    var out = [];
    if (dt.files && dt.files.length) out = Array.prototype.slice.call(dt.files);
    else if (dt.items) Array.prototype.forEach.call(dt.items, function (it) {
      if (it.kind === "file") { var f = it.getAsFile(); if (f) out.push(f); }
    });
    return out.filter(function (f) { return f && f.size > 0; });
  }
  function takeFiles(files) {
    var n = 0;
    files.forEach(function (f) { if (addPending(f)) n++; });
    return n;
  }
  function initPaste() {
    var box = $("input"), form = $("compose");
    box.addEventListener("paste", function (e) {
      var files = transferFiles(e.clipboardData);
      e.preventDefault();
      if (files.length) { takeFiles(files); return; }
      // Text only, as plain text (no pasted formatting in the box).
      var text = e.clipboardData ? e.clipboardData.getData("text/plain") : "";
      if (text) document.execCommand("insertText", false, text.replace(/\r\n?/g, "\n"));
    });
    box.addEventListener("beforeinput", function (e) {
      var it = e.inputType;
      if (it === "insertFromPaste" || it === "insertReplacementText" || it === "insertFromDrop" || it === "insertFromYank") {
        var files = transferFiles(e.dataTransfer);
        if (files.length) { e.preventDefault(); takeFiles(files); }
        return;
      }
      // Some Android keyboards send Enter only as an input event.
      if (it === "insertParagraph" && !e.isComposing) { e.preventDefault(); sendMessage(); }
    });
    var hasFiles = function (e) {
      var t = e.dataTransfer && e.dataTransfer.types;
      return !!t && Array.prototype.indexOf.call(t, "Files") >= 0;
    };
    var depth = 0;
    form.addEventListener("dragenter", function (e) { if (hasFiles(e)) { depth++; box.classList.add("drop-target"); } });
    form.addEventListener("dragleave", function (e) { if (hasFiles(e) && --depth <= 0) { depth = 0; box.classList.remove("drop-target"); } });
    form.addEventListener("dragover", function (e) { if (hasFiles(e)) { e.preventDefault(); e.dataTransfer.dropEffect = "copy"; } });
    form.addEventListener("drop", function (e) {
      depth = 0; box.classList.remove("drop-target");
      var files = transferFiles(e.dataTransfer);
      if (!files.length) return;
      e.preventDefault();
      takeFiles(files);
    });
  }
  function setAttachBusy(busy) {
    state.sendingMedia = busy;
    var b = $("attachBtn");
    b.classList.toggle("busy", busy);
    b.disabled = busy;
    b.setAttribute("aria-label", busy ? "Sending attachment" : "Attach photo or video");
  }
  function sendMedia(file) {
    if (!state.current) return;
    if (file.size > MAX_MEDIA_BYTES) { toast("That file is too large to send (limit 128 MB).", "error"); return; }
    var convID = state.current;
    var c = state.convs.find(function (x) { return x.ConversationID === convID; });
    var platform = String((c && c.source_platform) || "sms").toLowerCase();
    var type = String(file.type || "");
    var captioned = (platform === "whatsapp" && type.indexOf("audio/") !== 0) || platform === "signal";
    var t = $("input"), caption = captioned ? t.value.trim() : "";
    var reply = state.replyTo && (platform === "whatsapp" || platform === "signal") ? state.replyTo : null;
    var l = newLocal(convID, "", reply ? reply.MessageID : "", file, caption);
    if (caption && t.value.trim() === caption) { t.value = ""; lastSel = null; autosize(); }
    if (reply && state.replyTo === reply) clearReply();
    renderCurrent(true);   // local preview with "Sending…" right away
    postLocal(l);
  }

  // ---------- device settings ----------
  // Per-device (localStorage), so the car and a phone can differ.
  var SETTINGS_KEY = "tm.settings";
  function isCarBrowser() { return /tesla|qtcarbrowser/i.test(navigator.userAgent || ""); }
  function loadSettings() {
    var s = null;
    try { s = JSON.parse(localStorage.getItem(SETTINGS_KEY) || "null"); } catch (e) {}
    s = s && typeof s === "object" ? s : {};
    if (typeof s.showAttach !== "boolean") s.showAttach = !isCarBrowser();
    return s;
  }
  function saveSettings(s) { try { localStorage.setItem(SETTINGS_KEY, JSON.stringify(s)); } catch (e) {} }
  // UI zoom: per device (the car and a tablet may want different sizes).
  // The CSS is px-based, so the whole page is scaled with CSS zoom on <html>;
  // vh/vw-based sizes divide by --z so full-height layouts and overlays fit.
  var PREFS = window.TMPrefs; // zoom.js (runs in <head>, before first paint)
  var ZOOM_STEPS = PREFS.ZOOM_STEPS;
  // Default (zoom unset): 50% in Car Mode on the car-sized screen, 100% on
  // phones and in desk mode. "auto" (picked in Settings) fits the layout to
  // the window; see zoom.js. A number is a manual zoom and is kept as chosen.
  function renderZoom() {
    var s = loadSettings();
    var car = PREFS.carMode(s), auto = PREFS.isAuto(s), z = PREFS.effectiveZoom(s), dflt = PREFS.isDefault(s);
    $("zoomValue").textContent = (auto ? "Auto " : "") + z + "%";
    $("zoomDesc").textContent = car
      ? (auto ? "Auto: fits the car layout to this window (" + z + "%)"
        : dflt ? "Default for this screen (" + z + "%) · Auto fits it to the window"
        : "Size of text and buttons on this device · Auto fits it to the window")
      : "Size of text and buttons on this device";
    $("zoomOut").disabled = z <= ZOOM_STEPS[0];
    $("zoomIn").disabled = z >= ZOOM_STEPS[ZOOM_STEPS.length - 1];
    var box = $("zoomSteps");
    if (!box.firstChild) {
      ["auto"].concat(ZOOM_STEPS).forEach(function (v) {
        var b = el("button", "zoom-step" + (v === "auto" ? " zoom-auto" : ""), v === "auto" ? "Auto" : v + "%");
        b.type = "button"; b.dataset.z = v;
        b.addEventListener("click", function () { setZoom(v); });
        box.appendChild(b);
      });
    }
    Array.prototype.forEach.call(box.children, function (b) {
      var isAutoBtn = b.dataset.z === "auto";
      if (isAutoBtn) b.hidden = !car;
      var on = isAutoBtn ? auto : (!auto && +b.dataset.z === z);
      b.classList.toggle("on", on); b.setAttribute("aria-pressed", on ? "true" : "false");
    });
  }
  function applyZoom() {
    PREFS.apply(loadSettings());
    renderZoom();
  }
  function setZoom(z) {
    var s = loadSettings();
    s.zoom = z;
    saveSettings(s);
    closeMsgMenu();
    applyZoom();
  }
  function stepZoom(dir) {
    var z = PREFS.effectiveZoom(loadSettings());
    var next = dir > 0 ? ZOOM_STEPS.filter(function (v) { return v > z; })[0] : ZOOM_STEPS.filter(function (v) { return v < z; }).pop();
    if (next) setZoom(next);
  }
  // zoom.js re-applies Auto on resize; refresh the controls and drop popovers
  // placed for the old size.
  PREFS.onchange = function () { renderZoom(); closeMsgMenu(); closeConvMenu(); };
  PREFS.onlayout = function () { if (state.measureDock) state.measureDock(); };
  // Per-conversation meta: protocol (RCS/SMS) for the composer placeholder,
  // E2EE lock badge on send, and the header bell (mute) state.
  function setConvMeta(m) {
    m = m || {};
    m.loadedAt = Date.now();
    state.convMeta = m;
    var proto = m.protocol || "";
    $("input").placeholder = proto === "RCS" ? "RCS message" : proto === "SMS" ? "Text message" : "Message";
    $("input").setAttribute("aria-label", $("input").placeholder);
    $("sendProto").hidden = proto !== "SMS";
    $("sendLock").hidden = !m.e2ee;
    $("sendBtn").classList.toggle("e2ee", !!m.e2ee);
    setMuteUI(!!m.muted);
    refreshLocks();
  }
  function setMuteUI(muted) {
    var b = $("muteBtn");
    b.classList.toggle("muted", muted);
    b.setAttribute("aria-pressed", muted ? "true" : "false");
    b.setAttribute("aria-label", muted ? "Unmute conversation" : "Mute conversation");
    b.title = muted ? "Unmute notifications" : "Mute notifications";
  }
  function loadConvMeta(id) {
    setConvMeta(null);
    fetch("/api/app/conversations/meta?conversation_id=" + encodeURIComponent(id), { credentials: "same-origin" })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (m) { if (m && state.current === id) setConvMeta(m); })
      .catch(function () {});
  }
  // Notification "Reply" fallback (no inline text input): focus the composer.
  function focusComposer() {
    setTimeout(function () { var t = $("input"); if (t && !document.body.classList.contains("readonly-thread")) t.focus(); }, 150);
  }
  function toggleMute() {
    var id = state.current; if (!id) return;
    var b = $("muteBtn"), want = !b.classList.contains("muted");
    setMuteUI(want);
    fetch("/api/app/conversations/mute", { method: "POST", credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ conversation_id: id, muted: want }) })
      .then(function (r) { if (!r.ok) throw new Error("mute " + r.status); })
      .catch(function () { if (state.current === id) setMuteUI(!want); });
  }

  function applySettings() {
    var s = loadSettings();
    applyZoom();
    $("attachBtn").hidden = !s.showAttach;
    var showEm = s.showEmoji !== false, es2 = $("setEmoji");
    $("emojiBtn").hidden = !showEm;
    if (!showEm) showEmoji(false);
    es2.setAttribute("aria-checked", showEm ? "true" : "false");
    es2.classList.toggle("on", showEm);
    var snd = s.sound !== false, ss = $("setSound");
    ss.setAttribute("aria-checked", snd ? "true" : "false");
    ss.classList.toggle("on", snd);
    var qr = quickOn(s), qs = $("setQuickReplies");
    qs.setAttribute("aria-checked", qr ? "true" : "false");
    qs.classList.toggle("on", qr);
    $("quickRepliesEditRow").hidden = !qr;
    if (document.activeElement !== $("quickRepliesEdit")) $("quickRepliesEdit").value = quickList(s).join("\n");
    renderQuickReplies();
    var showVo = s.showVoice !== false, vs = $("setVoice");
    $("micBtn").hidden = !showVo;
    vs.setAttribute("aria-checked", showVo ? "true" : "false");
    vs.classList.toggle("on", showVo);
    var sw = $("setAttach");
    sw.setAttribute("aria-checked", s.showAttach ? "true" : "false");
    sw.classList.toggle("on", s.showAttach);
    var car = PREFS.carMode(s), cm = $("setCarMode");
    cm.checked = car;
    $("carModeDesc").textContent = car
      ? "Big touch controls for the car screen"
      : "Off: compact layout for a tablet or computer";
    var mr = s.micRecheck !== false, ms = $("setMicRecheck");
    ms.setAttribute("aria-checked", mr ? "true" : "false");
    ms.classList.toggle("on", mr);
    $("micRecheckDesc").textContent = mr
      ? "Checks the mic again when you come back to this page, and retries if the server can't be reached"
      : "Off: checked once when the page loads";
    var lt = s.liveTyping !== false, ls = $("setLiveTyping");
    ls.setAttribute("aria-checked", lt ? "true" : "false");
    ls.classList.toggle("on", lt);
    $("liveTypingDesc").textContent = lt
      ? "Words appear in the box as you speak (browser speech, or the server's live passes), then the server's transcript replaces them when you tap Done"
      : "Off: the text appears after you tap Done";
  }
  // Car Mode (per device, default on): off switches to the denser
  // tablet/desktop layout (html.desk, see zoom.js and app.css).
  // ---------- composer float (Car Mode) ----------
  // The car's on-screen keyboard covers the bottom of the screen, so when
  // the message box is tapped to type, the composer moves up to sit right
  // under the conversation header; it goes back when the box loses focus.
  // Only a tap on the box floats it: programmatic focus (dictation results,
  // reply, opening a chat) doesn't, and it never floats while the mic is
  // listening or transcribing. The emoji picker floats along with it.
  var cf = { on: false, tapAt: 0, offTimer: 0 };
  function micActive() {
    var b = $("micBtn");
    return !!state.rec || !!state.transcribing || b.classList.contains("recording") || b.classList.contains("busy");
  }
  function floatAllowed() {
    return !document.documentElement.classList.contains("desk") && !micActive() &&
      !$("threadView").hidden && !document.body.classList.contains("readonly-thread");
  }
  function setComposerFloat(on) {
    on = !!on && floatAllowed();
    if (cf.offTimer) { clearTimeout(cf.offTimer); cf.offTimer = 0; }
    if (on === cf.on) return;
    cf.on = on;
    var box = $("messages"), nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 200;
    $("threadView").classList.toggle("compose-float", on);
    document.body.classList.toggle("compose-floating", on);
    if (nearBottom) box.scrollTop = box.scrollHeight;
  }
  function scheduleUnfloat() {
    if (!cf.on) return;
    if (cf.offTimer) clearTimeout(cf.offTimer);
    cf.offTimer = setTimeout(function () {
      cf.offTimer = 0;
      var a = document.activeElement;
      if (!micActive() && (a === $("input") || a === $("emojiSearch") || emojiOpen())) return;
      setComposerFloat(false);
    }, 250);
  }
  function initComposerFloat() {
    var inp = $("input");
    var markTap = function () { cf.tapAt = Date.now(); };
    inp.addEventListener("pointerdown", markTap);
    inp.addEventListener("touchstart", markTap, { passive: true });
    inp.addEventListener("mousedown", markTap);
    var tapped = function () { return Date.now() - cf.tapAt < 1500; };
    inp.addEventListener("focus", function () {
      if (cf.on || tapped()) setComposerFloat(true); // cf.on: keep it up (cancels a pending restore)
    });
    // A tap on a box that already had focus (no new focus event).
    inp.addEventListener("click", function () { if (!cf.on && tapped()) setComposerFloat(true); });
    inp.addEventListener("blur", scheduleUnfloat);
    $("emojiSearch").addEventListener("blur", scheduleUnfloat);
  }

  function setCarMode(on) {
    var s = loadSettings();
    s.carMode = !!on;
    saveSettings(s);
    closeMsgMenu();
    closeConvMenu();
    applySettings();
    if (on) scheduleUnfloat(); else setComposerFloat(false);
  }
  function showSettings(open) {
    $("settingsView").hidden = !open;
    if (open) { applySettings(); renderMicCheck(); renderSttInfo(); if (window.TMPWA) window.TMPWA.refresh(); loadGrok(); }
  }

  // Where dictated text goes: the box's selection if it has focus, else
  // where the cursor was when it lost focus (tapping the mic blurs it) as
  // long as the text hasn't changed since, else the end.
  var lastSel = null;
  function rememberSelection() {
    var t = $("input");
    if (t.selectionStart != null) lastSel = { start: t.selectionStart, end: t.selectionEnd, value: t.value };
  }
  function composeSelection() {
    var t = $("input"), n = t.value.length;
    if (document.activeElement === t && t.selectionStart != null) return { start: t.selectionStart, end: t.selectionEnd };
    if (lastSel && lastSel.value === t.value) return { start: Math.min(lastSel.start, n), end: Math.min(lastSel.end, n) };
    return { start: n, end: n };
  }
  function insertText(text) {
    var t = $("input");
    var cur = t.value;
    var sel = composeSelection(), start = sel.start, end = sel.end;
    var before = cur.slice(0, start), after = cur.slice(end);
    var sep = before && !/\s$/.test(before) ? " " : "";
    t.value = before + sep + text + (after && !/^\s/.test(after) ? " " : "") + after;
    var pos = (before + sep + text).length;
    autosize();
    t.focus();
    try { t.setSelectionRange(pos, pos); } catch (e) {}
  }

  // ---------- emoji picker ----------
  // Like Google Messages for Web: Recent + category tabs + search. Tapping
  // an emoji inserts it at the cursor (without focusing the box, so the car's
  // on-screen keyboard stays down) and never sends. Data: emoji-data.js;
  // glyphs come from the bundled Noto Color Emoji font. Per-device switch
  // "Show emoji button" (default on).
  var EMOJI_RECENT_KEY = "tm.emojiRecent", EMOJI_RECENT_MAX = 24;
  var emojiCats = null, emojiAll = null;
  function emojiData() {
    if (emojiCats) return emojiCats;
    emojiCats = []; emojiAll = [];
    (window.TM_EMOJI || []).forEach(function (c) {
      var items = String(c.e || "").split("\u001e").filter(Boolean).map(function (x) {
        var p = x.split("\u001f"); return { e: p[0], n: p[1] || "" };
      });
      emojiCats.push({ id: c.id, label: c.label, items: items });
      emojiAll = emojiAll.concat(items);
    });
    return emojiCats;
  }
  function emojiRecent() {
    try { var r = JSON.parse(localStorage.getItem(EMOJI_RECENT_KEY) || "[]"); return Array.isArray(r) ? r.slice(0, EMOJI_RECENT_MAX) : []; } catch (e) { return []; }
  }
  function pushEmojiRecent(e) {
    var r = emojiRecent().filter(function (x) { return x !== e; });
    r.unshift(e);
    try { localStorage.setItem(EMOJI_RECENT_KEY, JSON.stringify(r.slice(0, EMOJI_RECENT_MAX))); } catch (er) {}
  }
  // Insert at the cursor: the box's selection if focused, else where the
  // cursor was (lastSel), else the end. Keeps lastSel so taps keep adding
  // in order. Doesn't focus the box.
  function insertAtCursor(str) {
    var t = $("input"), sel = composeSelection();
    t.value = t.value.slice(0, sel.start) + str + t.value.slice(sel.end);
    var pos = sel.start + str.length;
    if (document.activeElement === t) { try { t.setSelectionRange(pos, pos); } catch (e) {} }
    lastSel = { start: pos, end: pos, value: t.value };
    autosize();
  }
  function emojiButton(it) {
    var b = el("button", "emoji-cell", it.e);
    b.type = "button";
    b.title = it.n;
    b.setAttribute("aria-label", it.n || it.e);
    b.dataset.e = it.e;
    return b;
  }
  function renderEmojiTabs() {
    var tabs = $("emojiTabs");
    if (tabs.firstChild) return;
    var mk = function (id, label, glyph, isIcon) {
      var b = el("button", "emoji-tab");
      b.type = "button"; b.dataset.sec = id; b.title = label;
      b.setAttribute("role", "tab"); b.setAttribute("aria-label", label);
      if (isIcon) b.appendChild(icon(glyph)); else b.appendChild(el("span", "emoji-tab-glyph", glyph));
      b.appendChild(el("span", "emoji-tab-label", label));
      tabs.appendChild(b);
    };
    mk("recent", "Recent", "clock", true);
    emojiData().forEach(function (c) { mk(c.id, c.label, c.items[0] ? c.items[0].e : "", false); });
  }
  function renderEmojiGrid() {
    var grid = $("emojiGrid"), q = $("emojiSearch").value.trim().toLowerCase();
    grid.textContent = "";
    var section = function (id, label, items) {
      var h = el("div", "emoji-sec", label); h.id = "emojiSec-" + id; grid.appendChild(h);
      var wrap = el("div", "emoji-cells");
      items.forEach(function (it) { wrap.appendChild(emojiButton(it)); });
      grid.appendChild(wrap);
    };
    emojiData();
    if (q) {
      var hits = emojiAll.filter(function (it) { return it.n.indexOf(q) >= 0; }).slice(0, 240);
      if (hits.length) section("search", "Results", hits);
      else grid.appendChild(el("div", "emoji-empty", "No emoji match \"" + q + "\""));
      $("emojiTabs").hidden = true;
      return;
    }
    $("emojiTabs").hidden = false;
    var rec = emojiRecent();
    var names = {};
    emojiAll.forEach(function (it) { names[it.e] = it.n; });
    section("recent", "Recent", rec.length ? rec.map(function (e) { return { e: e, n: names[e] || "" }; }) : []);
    if (!rec.length) grid.lastChild.appendChild(el("div", "emoji-empty", "Emoji you use show up here"));
    emojiCats.forEach(function (c) { section(c.id, c.label, c.items); });
    markEmojiTab();
  }
  function markEmojiTab() {
    var grid = $("emojiGrid"), secs = grid.querySelectorAll(".emoji-sec"), cur = "recent";
    var top = grid.getBoundingClientRect().top + 4;
    for (var i = 0; i < secs.length; i++) if (secs[i].getBoundingClientRect().top <= top) cur = secs[i].id.replace("emojiSec-", "");
    Array.prototype.forEach.call($("emojiTabs").children, function (b) {
      var on = b.dataset.sec === cur;
      b.classList.toggle("on", on); b.setAttribute("aria-selected", on ? "true" : "false");
    });
  }
  function emojiOpen() { return !$("emojiPanel").hidden; }
  function showEmoji(open) {
    var p = $("emojiPanel");
    if (open === emojiOpen()) return;
    if (open) {
      showPlusSheet(false);
      rememberSelection();
      renderEmojiTabs();
      $("emojiSearch").value = "";
      p.hidden = false;
      renderEmojiGrid();
      $("emojiGrid").scrollTop = 0;
      markEmojiTab();
    } else { p.hidden = true; state.reactFor = null; scheduleUnfloat(); }
    $("emojiBtn").classList.toggle("on", open);
    $("emojiBtn").setAttribute("aria-expanded", open ? "true" : "false");
    document.body.classList.toggle("emoji-open", open);
  }
  function initEmoji() {
    $("emojiBtn").addEventListener("mousedown", function (e) { e.preventDefault(); }); // keep the box's cursor
    $("emojiBtn").addEventListener("click", function () { showEmoji(!emojiOpen()); });
    $("emojiClose").addEventListener("click", function () { showEmoji(false); });
    $("emojiGrid").addEventListener("mousedown", function (e) { if (e.target.closest(".emoji-cell")) e.preventDefault(); });
    $("emojiGrid").addEventListener("click", function (e) {
      var b = e.target.closest(".emoji-cell");
      if (!b) return;
      if (state.reactFor) {
        var rm = state.reactFor;
        state.reactFor = null;
        pushEmojiRecent(b.dataset.e);
        showEmoji(false);
        sendReaction(rm, b.dataset.e);
        return;
      }
      insertAtCursor(b.dataset.e);
      pushEmojiRecent(b.dataset.e);
    });
    $("emojiGrid").addEventListener("scroll", function () { if (!$("emojiTabs").hidden) markEmojiTab(); }, { passive: true });
    $("emojiTabs").addEventListener("click", function (e) {
      var b = e.target.closest(".emoji-tab");
      if (!b) return;
      var sec = $("emojiSec-" + b.dataset.sec), grid = $("emojiGrid");
      if (sec) grid.scrollTop += sec.getBoundingClientRect().top - grid.getBoundingClientRect().top;
      markEmojiTab();
    });
    $("emojiSearch").addEventListener("input", renderEmojiGrid);
  }

  // ---------- mic / speech-to-text ----------
  // Engines, chosen by MESSAGES_STT_MODE (config.stt_mode):
  //   auto    - the browser's Web Speech API (live words in the box), falling
  //             back to MediaRecorder -> /api/transcribe if it's missing or fails
  //   builtin - Web Speech API only
  //   server  - MediaRecorder -> /api/transcribe only
  // Either way the text only lands in the compose box; nothing is ever sent.
  var MIME_CANDIDATES = ["audio/webm;codecs=opus", "audio/webm", "audio/ogg;codecs=opus", "audio/mp4;codecs=mp4a.40.2", "audio/mp4"];
  // Web Speech errors that mean "this engine can't work here": fall back to server STT.
  var BUILTIN_FALLBACK_ERRORS = { "not-allowed": 1, "service-not-allowed": 1, "network": 1, "language-not-supported": 1 };
  var SPEECH_ERROR_HINTS = { "audio-capture": "no microphone available to the speech engine", "bad-grammar": "engine rejected the request" };
  function pickMime() {
    if (!window.MediaRecorder || !MediaRecorder.isTypeSupported) return "";
    for (var i = 0; i < MIME_CANDIDATES.length; i++) if (MediaRecorder.isTypeSupported(MIME_CANDIDATES[i])) return MIME_CANDIDATES[i];
    return "";
  }
  function micSupported() {
    return !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia && window.MediaRecorder);
  }
  function speechCtor() { return window.SpeechRecognition || window.webkitSpeechRecognition || null; }
  function sttMode() { var m = state.config.stt_mode; return m === "builtin" || m === "server" ? m : "auto"; }
  function serverSTTEnabled() { return !!state.config.stt_enabled; }
  function builtinLabel() {
    var ua = navigator.userAgent || "";
    if (/Tesla|QtWebEngine/i.test(ua)) return "Car browser speech";
    if (/SamsungBrowser/i.test(ua)) return "Samsung speech";
    if (/Edg\//.test(ua)) return "Edge speech";
    if (/Chrome\//.test(ua)) return "Chrome speech";
    if (/Safari\//.test(ua)) return "Safari speech";
    return "Built-in speech";
  }
  function serverLabel() { var l = state.config.stt_label; return l && l !== "none" ? l : "Server STT"; }
  function speechLang() { return navigator.language || "en-US"; }
  function sttUsable() { return micStatus().ok; }

  // --- mic availability check ---
  // Decides whether the mic button is dimmed (body.no-stt) and what to say
  // when the mic can't work. With "Re-check microphone access" on (the
  // default, per device) it re-runs when the page comes back (visible /
  // focus / pageshow), when the mic permission changes, before a mic tap if
  // the last check said unavailable, and retries a failed config load
  // (2s, 5s, 15s, then on the next resume). Off: one check at startup.
  var CONFIG_RETRY_MS = [2000, 5000, 15000];
  var mic = { configOK: false, configErr: "", loading: null, retryTimer: null, retryIdx: 0,
    perm: "", permStatus: null, lastCheck: 0, status: null, checking: null };
  var MIC_MSG_INSECURE = "The mic needs the secure https:// link. This page was opened over plain http, where browsers block the microphone. Open Messages Enhanced from its https address and try again.";
  var MIC_MSG_BLOCKED = "Microphone is blocked for this site. To allow it: tap the lock / tune icon next to the web address, then Site settings (or Permissions) > Microphone > Allow. Then reload, or open Settings (gear icon) > Check again.";
  function micRecheckOn() { return loadSettings().micRecheck !== false; }
  function liveTypingOn() { return loadSettings().liveTyping !== false; }
  function insecurePage() { return window.isSecureContext === false; } // localhost counts as secure
  function livePerm() { if (mic.permStatus) mic.perm = mic.permStatus.state; return mic.perm; }
  // {ok, reason, text, detail}: reason is "insecure" | "denied" | "config" | "nobuiltin" | "noserver" | "norecord"
  function micStatus() {
    if (insecurePage()) return { ok: false, reason: "insecure", text: "Mic unavailable – needs the https link", detail: MIC_MSG_INSECURE };
    if (livePerm() === "denied") return { ok: false, reason: "denied", text: "Mic unavailable – blocked for this site", detail: MIC_MSG_BLOCKED };
    var mode = sttMode();
    var builtin = mode !== "server" && !!speechCtor();
    var server = mode !== "builtin" && serverSTTEnabled() && micSupported();
    if (builtin && server && skipBuiltin()) return { ok: true, text: "Mic available – server transcription (" + serverLabel() + "); " + builtinLabel() + " skipped after it failed (" + builtinBroken().reason + ")" };
    if (builtin) return { ok: true, text: "Mic available – " + builtinLabel() + (server ? " (server transcription as backup)" : "") };
    if (server) return { ok: true, text: "Mic available – server transcription (" + serverLabel() + ")" };
    if (mode !== "builtin" && !mic.configOK) return { ok: false, reason: "config", text: "Mic unavailable – couldn't load the server's voice settings",
      detail: "Couldn't reach Messages Enhanced to check voice settings" + (mic.configErr ? " (" + mic.configErr + ")" : "") + ". Check the connection and try again." };
    if (mode === "builtin") return { ok: false, reason: "nobuiltin", text: "Mic unavailable – this browser has no built-in speech recognition",
      detail: "Speech isn't available on this browser (no built-in speech recognition). Use the keyboard's mic instead." };
    if (!serverSTTEnabled()) return { ok: false, reason: "noserver", text: "Mic unavailable – no built-in speech here and server transcription isn't set up",
      detail: "Speech isn't available on this browser, and server transcription isn't set up. Use the keyboard's mic instead." };
    return { ok: false, reason: "norecord", text: "Mic unavailable – this browser can't record audio", detail: "This browser can't record audio. Use the keyboard's mic instead." };
  }
  function applyMicState() {
    var st = micStatus();
    mic.status = st;
    document.body.classList.toggle("no-stt", !st.ok);
    return st;
  }
  function loadSTTConfig() {
    if (mic.loading) return mic.loading;
    mic.loading = api("/api/app/config").then(function (c) {
      state.config = c;
      mic.configOK = true; mic.configErr = ""; mic.retryIdx = 0;
      renderSttInfo();
      clearTimeout(mic.retryTimer); mic.retryTimer = null;
    }, function (e) {
      mic.configErr = (e && e.message) || "network error";
      scheduleConfigRetry();
    }).then(function () { mic.loading = null; });
    return mic.loading;
  }
  function scheduleConfigRetry() {
    if (!micRecheckOn() || mic.retryTimer || mic.retryIdx >= CONFIG_RETRY_MS.length) return; // else wait for the next resume
    mic.retryTimer = setTimeout(function () { mic.retryTimer = null; checkMic(); }, CONFIG_RETRY_MS[mic.retryIdx++]);
  }
  function onPermChange() { mic.perm = this.state; applyMicState(); renderMicCheck(); if (mic.perm !== "denied") checkMic(); }
  function watchPerm(on) { if (mic.permStatus) mic.permStatus.onchange = on ? onPermChange : null; }
  function queryMicPermission() {
    if (mic.permStatus) { livePerm(); return Promise.resolve(); }
    if (!navigator.permissions || !navigator.permissions.query) { mic.perm = "unsupported"; return Promise.resolve(); }
    return Promise.resolve().then(function () { return navigator.permissions.query({ name: "microphone" }); }).then(function (ps) {
      mic.permStatus = ps; mic.perm = ps.state;
      watchPerm(micRecheckOn());
    }).catch(function () { mic.perm = "unsupported"; });
  }
  // Config (skipped once loaded unless forced) + permission + builtin speech.
  function checkMic(forceConfig) {
    mic.lastCheck = Date.now();
    var cfg = (!mic.configOK || forceConfig) ? loadSTTConfig() : Promise.resolve();
    mic.checking = Promise.all([cfg, queryMicPermission()]).then(function () {
      mic.checking = null;
      var st = applyMicState();
      renderMicCheck();
      return st;
    });
    return mic.checking;
  }
  function onPageResume() {
    if (!micRecheckOn() || state.rec) return;
    if (Date.now() - mic.lastCheck < 1500) return; // visibilitychange + focus + pageshow arrive together
    clearTimeout(mic.retryTimer); mic.retryTimer = null; mic.retryIdx = 0;
    checkMic(true);
  }
  function renderMicCheck(checking) {
    var out = $("micCheckResult");
    if (!out) return;
    var st = mic.status;
    out.classList.toggle("ok", !checking && !!st && st.ok);
    out.classList.toggle("bad", !checking && !!st && !st.ok);
    if (checking) { out.textContent = "Checking…"; return; }
    if (!st) { out.textContent = ""; return; }
    var perm = mic.perm && mic.perm !== "unsupported" ? mic.perm : "not reported by this browser";
    out.textContent = st.text + (st.ok ? "" : "\n" + st.detail) + "\nMic permission: " + perm +
      (mic.lastCheck ? " · checked " + clockTime(mic.lastCheck, true) : "");
  }
  function micCheckNow() {
    var b = $("micCheckBtn");
    b.disabled = true;
    forgetBuiltinFailure(); sttDbg.builtinErr = null;
    renderMicCheck(true);
    clearTimeout(mic.retryTimer); mic.retryTimer = null; mic.retryIdx = 0;
    var started = Date.now();
    checkMic(true).then(function () {
      // keep "Checking…" visible briefly so the tap registers
      setTimeout(function () { b.disabled = false; renderMicCheck(); }, Math.max(0, 350 - (Date.now() - started)));
    });
  }
  function setMicRecheck(on) {
    var s = loadSettings();
    s.micRecheck = !!on;
    saveSettings(s);
    watchPerm(!!on);
    if (on) checkMic(true);
    else { clearTimeout(mic.retryTimer); mic.retryTimer = null; }
    applySettings();
  }
  // Which engine ran: shown in the recording bar while listening, and in
  // Settings > Debug > Microphone & speech-to-text afterwards (not in the header).
  var sttDbg = { last: null, latency: null, builtinErr: null };
  function showEngine(label, note) {
    $("recEngine").textContent = label;
    sttDbg.last = { label: label, note: note || "", at: Date.now() };
    renderSttInfo();
  }
  // Built-in speech that failed in a way that won't fix itself (no network
  // to Google's speech service in the car, unsupported language, never
  // started) is skipped in auto mode for BUILTIN_SKIP_MS on this device, so
  // the mic goes straight to server transcription (and live typing).
  // Settings > Debug > Check again clears it.
  var BUILTIN_MEMO_KEY = "tm.builtinFailed", BUILTIN_SKIP_MS = 24 * 3600 * 1000;
  var BUILTIN_STICKY = { "network": 1, "language-not-supported": 1, "did not start": 1, "start failed": 1 };
  function builtinBroken() {
    var m = null;
    try { m = JSON.parse(localStorage.getItem(BUILTIN_MEMO_KEY) || sessionStorage.getItem(BUILTIN_MEMO_KEY) || "null"); } catch (e) {}
    if (!m || !m.reason || !(Date.now() - (m.at || 0) < BUILTIN_SKIP_MS)) return null;
    return m;
  }
  function rememberBuiltinFailure(reason) {
    sttDbg.builtinErr = { reason: reason, at: Date.now() };
    if (!BUILTIN_STICKY[reason]) return;
    var v = JSON.stringify({ reason: reason, at: Date.now() });
    try { sessionStorage.setItem(BUILTIN_MEMO_KEY, v); } catch (e) {}
    try { localStorage.setItem(BUILTIN_MEMO_KEY, v); } catch (e) {}
  }
  function forgetBuiltinFailure() {
    try { sessionStorage.removeItem(BUILTIN_MEMO_KEY); } catch (e) {}
    try { localStorage.removeItem(BUILTIN_MEMO_KEY); } catch (e) {}
  }
  // Skip built-in speech? (auto mode only, after a sticky failure)
  function skipBuiltin() { return sttMode() === "auto" && !!builtinBroken() && serverSTTEnabled() && micSupported(); }
  function sttModeText() {
    var m = sttMode();
    if (m === "server") return "server (always the server's transcription)";
    if (m === "builtin") return "builtin (browser speech only)";
    if (state.config.stt_prefer_server) return "auto (" + serverLabel() + " first, browser speech as backup)";
    return "auto (browser speech first, server as backup)";
  }
  function agoText(at) {
    var s = Math.max(0, Math.round((Date.now() - at) / 1000));
    return s < 60 ? s + " s ago" : s < 3600 ? Math.round(s / 60) + " min ago" : clockTime(at);
  }
  function renderSttInfo() {
    if (!$("sttInfo")) return;
    var c = state.config || {};
    var set = function (id, text) { $(id).textContent = text; };
    if (!mic.configOK) {
      set("sttInfoProvider", mic.configErr ? "couldn't load (" + mic.configErr + ")" : "loading…");
    } else {
      set("sttInfoProvider", serverSTTEnabled() ? serverLabel() + (c.stt_provider ? " (" + String(c.stt_provider).split(":")[0] + ")" : "") : "none (server transcription off)");
    }
    set("sttInfoModel", c.stt_model || "-");
    set("sttInfoMode", mic.configOK ? sttModeText() : "-");
    set("sttInfoLive", !liveTypingOn() ? "off on this device"
      : speechLiveOK() && serverSTTEnabled() ? "on (" + builtinLabel() + " while you speak, " + serverLabel() + " for the final text)"
      : !c.stt_live ? "not available on the server"
      : "on (" + serverLabel() + ", every " + ((c.stt_live_step_ms || 700) / 1000) + " s while dictating)");
    var b;
    var broken = builtinBroken();
    if (!speechCtor()) b = "not available in this browser";
    else if (sttMode() === "server") b = builtinLabel() + ": not used (server mode)";
    else if (broken) b = builtinLabel() + ": failed (" + broken.reason + ") " + agoText(broken.at) + ", skipped on this device";
    else if (sttDbg.builtinErr) b = builtinLabel() + ": last error " + sttDbg.builtinErr.reason + " " + agoText(sttDbg.builtinErr.at);
    else b = builtinLabel() + ": available";
    set("sttInfoBuiltin", b);
    var l = sttDbg.last;
    set("sttInfoLast", l ? l.label + (l.note ? " · " + l.note : "") + " · " + agoText(l.at) : "none yet");
    var lt = sttDbg.latency;
    set("sttInfoLatency", !lt ? "none yet"
      : (lt.finalMs != null ? "final " + (lt.finalMs / 1000).toFixed(2) + " s" : "")
        + (lt.audioSecs ? " for " + lt.audioSecs.toFixed(1) + " s of audio" : "")
        + (lt.liveMedianMs != null ? " · live median " + (lt.liveMedianMs / 1000).toFixed(2) + " s (" + lt.liveCount + " passes)" : "")
        + (lt.note ? (lt.finalMs != null ? " · " : "") + lt.note : ""));
  }
  function fmtClock(s) { return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0"); }
  function startTimer(r) {
    var max = state.config.max_record_secs || 60;
    $("recTime").textContent = "0:00 / " + fmtClock(max);
    r.timer = setInterval(function () {
      var s = Math.floor((Date.now() - r.started) / 1000);
      $("recTime").textContent = fmtClock(Math.min(s, max)) + " / " + fmtClock(max);
      if (s >= max) stopRecording();
    }, 250);
  }
  function setMicState(s) {
    var b = $("micBtn");
    b.classList.remove("recording", "busy");
    if (s) b.classList.add(s);
    b.setAttribute("aria-label", s === "recording" ? "Stop dictating" : s === "busy" ? "Transcribing" : "Dictate message");
    b.disabled = s === "busy";
    // Dictation lives in the composer row: the box shows the live words (or
    // "Listening…"), a thin red edge marks recording, and Cancel / Edit /
    // Send replace the mic and Send buttons. The old panel stays hidden
    // (its timer still drives the 1-minute auto-stop, invisibly).
    $("recBar").hidden = true;
    document.body.classList.toggle("rec-ui", s === "recording" || s === "busy");
    document.body.classList.toggle("rec-busy", s === "busy");
    $("recLabel").textContent = s === "busy" ? "Transcribing…" : "Listening… tap Done when finished";
    if (!s) { state.afterRec = ""; }
    $("recEdit").classList.toggle("pending", state.afterRec === "edit");
    $("recSend").classList.toggle("pending", state.afterRec === "send");
    $("recTime").hidden = s === "busy";
    document.body.classList.toggle("is-recording", s === "recording");
    if (s) setComposerFloat(false); // never floated while dictating
    autosize();
  }
  function onMicTap() {
    if (state.transcribing) return;
    if (state.rec) { stopRecording(); return; }
    if (mic.checking && mic.tapWaiting) return;
    var st = mic.status || applyMicState();
    if (!st.ok && micRecheckOn()) {
      // Last check said unavailable: check again first (capped so a slow
      // network never leaves the tap dead).
      mic.tapWaiting = true;
      var done = false;
      var go = function () { if (done) return; done = true; mic.tapWaiting = false; micTapGo(); };
      checkMic(true).then(go);
      setTimeout(go, 3000);
      return;
    }
    micTapGo();
  }
  function micTapGo() {
    if (state.rec || state.transcribing) return;
    var st = applyMicState();
    if (!st.ok && (st.reason === "insecure" || st.reason === "denied")) { toast(st.detail, "error"); return; }
    var mode = sttMode();
    if (mode === "server") { startServer(null); return; }
    // Auto with a cloud Whisper (Groq): server first; after a failed
    // transcription the browser's speech is used for the next 10 minutes.
    if (mode === "auto" && state.config.stt_prefer_server && serverSTTEnabled() && micSupported() &&
      !(state.serverSTTFailedAt && Date.now() - state.serverSTTFailedAt < 600000)) { startServer(null); return; }
    if (skipBuiltin()) { startServer(null, "built-in skipped: " + builtinBroken().reason); return; }
    if (!speechCtor()) {
      if (mode === "builtin") { toast("Speech isn't available on this browser (no built-in speech recognition). Use the keyboard's mic instead.", "error"); return; }
      startServer("not available");
      return;
    }
    startBuiltin();
  }

  // --- built-in (Web Speech API) ---
  function startBuiltin() {
    var Ctor = speechCtor();
    var t = $("input");
    var cur = t.value;
    var start = cur.length, end = cur.length;
    if (document.activeElement === t && t.selectionStart != null) { start = t.selectionStart; end = t.selectionEnd; }
    var before = cur.slice(0, start), after = cur.slice(end);
    var r = {
      kind: "builtin", original: cur, before: before + (before && !/\s$/.test(before) ? " " : ""), after: after,
      committed: "", finals: "", interim: "", heard: false, started: Date.now(), timer: null,
      cancelled: false, stopping: false, done: false, restarts: 0, sr: null, watchdog: null, stopGuard: null,
    };
    function text() { return (r.committed + r.finals + r.interim).replace(/\s+/g, " ").trim(); }
    function render() {
      var tx = text();
      t.value = r.before + tx + (tx && r.after && !/^\s/.test(r.after) ? " " : "") + r.after;
      autosize();
      t.scrollTop = t.scrollHeight;
    }
    r.render = render;
    r.text = text;
    function newRecognizer() {
      var sr = new Ctor();
      sr.lang = speechLang();
      sr.continuous = true;
      sr.interimResults = true;
      sr.maxAlternatives = 1;
      sr.onstart = sr.onaudiostart = function () { clearTimeout(r.watchdog); };
      sr.onresult = function (e) {
        if (r.cancelled || r.done) return;
        clearTimeout(r.watchdog);
        var finals = "", interim = "";
        for (var i = 0; i < e.results.length; i++) {
          var res = e.results[i], tr = res[0] ? res[0].transcript : "";
          if (res.isFinal) finals += tr + " "; else interim += tr + " ";
        }
        r.finals = finals; r.interim = interim;
        if (text()) r.heard = true;
        r.restarts = 0;
        render();
      };
      sr.onerror = function (e) {
        var err = (e && e.error) || "unknown";
        r.lastError = err;
        if (r.cancelled || r.done || err === "aborted") return;
        if (BUILTIN_FALLBACK_ERRORS[err] && !r.heard && !r.stopping) { builtinFailed(r, err); return; }
        if (err === "no-speech") return; // onend restarts until Done/cap
        r.fatal = err;
      };
      sr.onend = function () {
        if (r.cancelled || r.done) return;
        // A session ended: keep its words, then keep listening (continuous
        // until Done) unless the user stopped, the cap hit, or it keeps dying.
        r.committed += r.finals + r.interim; r.finals = ""; r.interim = "";
        if (r.stopping) { finishBuiltin(r); return; }
        if (r.fatal) { finishBuiltin(r, "Built-in speech stopped (" + r.fatal + (SPEECH_ERROR_HINTS[r.fatal] ? ": " + SPEECH_ERROR_HINTS[r.fatal] : "") + ")."); return; }
        if (++r.restarts > 5) { finishBuiltin(r); return; }
        try { r.sr = newRecognizer(); r.sr.start(); } catch (x) { finishBuiltin(r); }
      };
      return sr;
    }
    try {
      r.sr = newRecognizer();
      r.sr.start();
    } catch (e) {
      builtinFailed(r, (e && (e.name || e.message)) || "start failed");
      return;
    }
    state.rec = r;
    setMicState("recording");
    showEngine(builtinLabel(), speechLang());
    startTimer(r);
    // Some embedded browsers expose the API but never start; treat silence
    // from the engine itself (no start/audio/result event) as a failure.
    r.watchdog = setTimeout(function () { if (!r.heard && !r.stopping && !r.cancelled && !r.done) builtinFailed(r, "did not start"); }, 5000);
  }
  function teardownBuiltin(r) {
    r.done = true;
    clearInterval(r.timer); clearTimeout(r.watchdog); clearTimeout(r.stopGuard);
    if (state.rec === r) state.rec = null;
  }
  function builtinFailed(r, reason) {
    rememberBuiltinFailure(reason);
    teardownBuiltin(r);
    try { r.sr && r.sr.abort(); } catch (e) {}
    $("input").value = r.original; autosize();
    setMicState(null);
    if (sttMode() === "builtin") {
      toast("Built-in speech didn't work on this browser (" + reason + "). Use the keyboard's mic instead.", "error");
      showEngine(builtinLabel(), "failed: " + reason);
      return;
    }
    startServer(reason);
  }
  function finishBuiltin(r, errorMsg) {
    if (r.done) return;
    teardownBuiltin(r);
    r.render();
    var act = state.afterRec;
    setMicState(null);
    var t = $("input");
    if (act === "send" && !errorMsg && r.text()) { sendAfterDictation(); return; }
    t.focus();
    if (act === "edit") setComposerFloat(true);
    try { var pos = act === "edit" ? t.value.length : t.value.length - r.after.length; t.setSelectionRange(pos, pos); } catch (e) {}
    if (errorMsg && r.fatal) showEngine(builtinLabel(), "error: " + r.fatal);
    if (errorMsg) toast(errorMsg + (r.text() ? " Check the text so far, then tap Send." : ""), "error");
    else if (r.text()) toast("Check the text, then tap Send");
    else toast("Didn't catch that. Try again.");
  }

  // --- server (MediaRecorder -> /api/transcribe) ---
  // fallbackReason is set when we got here because built-in speech failed.
  // skipNote: built-in speech was skipped (remembered failure), for the info.
  function startServer(fallbackReason, skipNote) {
    var why = fallbackReason ? " (built-in speech: " + fallbackReason + ")" : "";
    if (fallbackReason === "not-allowed" || fallbackReason === "service-not-allowed") {
      if (insecurePage()) { toast(MIC_MSG_INSECURE, "error"); showEngine(builtinLabel(), "failed: " + fallbackReason); return; }
      if (livePerm() === "denied") { toast(MIC_MSG_BLOCKED, "error"); showEngine(builtinLabel(), "failed: " + fallbackReason); applyMicState(); return; }
    }
    if (!serverSTTEnabled() && !mic.configOK && sttMode() !== "builtin") {
      toast("Couldn't reach Messages Enhanced to check voice settings. Check the connection and try again.", "error");
      if (fallbackReason) showEngine(builtinLabel(), "failed: " + fallbackReason);
      return;
    }
    if (!serverSTTEnabled()) {
      toast(fallbackReason ? "Speech isn't available on this browser" + why + ", and server transcription isn't set up. Use the keyboard's mic instead."
        : "Voice input isn't set up on the server. Use the keyboard's mic instead.", "error");
      if (fallbackReason) showEngine(builtinLabel(), "failed: " + fallbackReason);
      return;
    }
    if (!micSupported()) { toast(insecurePage() ? MIC_MSG_INSECURE : "This browser can't record audio" + why + ". Use the keyboard's mic instead.", "error"); return; }
    startRecording(fallbackReason, skipNote);
  }
  function engineNote(r) {
    var parts = [];
    if (r.live) parts.push(r.live.failed ? "live off: " + r.live.failed : "live");
    if (r.fallback) parts.push("built-in: " + r.fallback);
    if (r.skipNote) parts.push(r.skipNote);
    return parts.join(" · ");
  }
  function median(a) {
    if (!a || !a.length) return null;
    var b = a.slice().sort(function (x, y) { return x - y; });
    return b[Math.floor(b.length / 2)];
  }
  function startRecording(fallbackReason, skipNote) {
    var mime = pickMime();
    var live = prepareLive();
    navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 } }).then(function (stream) {
      var rec;
      try {
        rec = mime ? new MediaRecorder(stream, { mimeType: mime, audioBitsPerSecond: 32000 }) : new MediaRecorder(stream);
      } catch (e) {
        rec = new MediaRecorder(stream);
      }
      var r = { kind: "server", stream: stream, recorder: rec, chunks: [], cancelled: false, started: Date.now(), timer: null, fallback: fallbackReason, skipNote: skipNote || "", live: null };
      state.rec = r;
      rec.ondataavailable = function (e) { if (e.data && e.data.size) r.chunks.push(e.data); };
      rec.onstop = function () { finishRecording(r); };
      rec.onerror = function (e) { toast("Recording error: " + ((e.error && e.error.name) || "unknown"), "error"); cancelRecording(); };
      rec.start(250);
      setMicState("recording");
      // Live words: the browser's own speech recognition when it has one
      // (non-car; free, instant), alongside the recording that the server
      // transcribes at the end; otherwise (Car Mode: no browser speech) the
      // server's live passes (Groq, throttled).
      if (speechLiveOK()) startSpeechLive(r, live);
      else if (live) startLive(r, live);
      showEngine(serverLabel(), engineNote(r));
      startTimer(r);
    }).catch(function (e) {
      if (live) closeLiveAudio(live);
      var name = e && e.name;
      var msg = insecurePage() ? MIC_MSG_INSECURE :
        name === "NotAllowedError" ? (livePerm() === "denied" ? MIC_MSG_BLOCKED : "Microphone permission was denied. Tap the mic again and choose Allow. If there's no prompt, it's blocked: tap the lock / tune icon next to the web address, then Microphone > Allow.") :
        name === "NotFoundError" ? "No microphone found." :
        name === "SecurityError" ? MIC_MSG_INSECURE :
        "Can't start the mic: " + (e && (e.message || name));
      toast(msg, "error");
      setMicState(null);
      if (name === "NotAllowedError") { queryMicPermission().then(applyMicState); }
    });
  }
  function releaseMic(r) {
    clearInterval(r.timer);
    if (r.live) stopLive(r.live);
    if (r.speech) stopSpeechLive(r.speech);
    try { r.stream.getTracks().forEach(function (t) { t.stop(); }); } catch (e) {}
  }
  function stopRecording() {
    var r = state.rec;
    if (!r) return;
    if (r.kind === "builtin") {
      if (r.stopping) return;
      r.stopping = true;
      clearInterval(r.timer);
      $("recLabel").textContent = "Finishing…";
      try { r.sr.stop(); } catch (e) { finishBuiltin(r); return; }
      // If the engine never fires onend, finish with what we have.
      r.stopGuard = setTimeout(function () { r.committed += r.finals + r.interim; r.finals = r.interim = ""; finishBuiltin(r); }, 2500);
      return;
    }
    if (r.recorder.state !== "inactive") r.recorder.stop(); else finishRecording(r);
  }
  function cancelRecording() {
    var r = state.rec;
    if (!r) return;
    r.cancelled = true;
    state.rec = null;
    if (r.kind === "builtin") {
      teardownBuiltin(r);
      try { r.sr.abort(); } catch (e) {}
      $("input").value = r.original; autosize();
      setMicState(null);
      return;
    }
    releaseMic(r);
    try { if (r.recorder.state !== "inactive") r.recorder.stop(); } catch (e) {}
    if (r.live && r.live.shown) { $("input").value = r.live.original; autosize(); }
    else if (r.speech && r.speech.shown) { $("input").value = r.speech.original; autosize(); }
    setMicState(null);
  }
  function finishRecording(r) {
    releaseMic(r);
    if (state.rec === r) state.rec = null;
    if (r.cancelled) return;
    var type = (r.recorder.mimeType || (r.chunks[0] && r.chunks[0].type) || "audio/webm");
    var blob = new Blob(r.chunks, { type: type });
    var L = r.live && r.live.shown ? r.live : r.speech && r.speech.shown ? r.speech : null; // live text is in the box
    if (r.speech && r.speech.shown && r.speech.meterOK && r.speech.peak < 0.0005 && r.speech.agree.text()) {
      // The recording came out silent (the phone gave the mic to the browser's
      // speech recognizer): keep its text instead of transcribing silence.
      placeLiveText(r.speech, r.speech.agree.text());
      sttDbg.latency = { finalMs: null, audioSecs: (Date.now() - r.started) / 1000, note: "recording silent; kept " + builtinLabel() + " text" };
      setMicState(null); renderSttInfo();
      toast("Check the text, then tap Send");
      return;
    }
    if (Date.now() - r.started < 400 || blob.size < 200) {
      if (L) { $("input").value = L.original; autosize(); }
      setMicState(null); toast("That was too short. Hold on a moment longer."); return;
    }
    state.transcribing = true;
    setMicState("busy");
    autosize();
    // Final pass over the whole utterance (better than the live windows);
    // with live typing it replaces the live text in place.
    var t0 = Date.now(), audioSecs = (t0 - r.started) / 1000;
    var liveLat = r.live && r.live.lat ? r.live.lat : [];
    var noteLatency = function (finalMs, note) {
      sttDbg.latency = { finalMs: finalMs, audioSecs: audioSecs, liveMedianMs: median(liveLat), liveCount: liveLat.length, note: note || "" };
      renderSttInfo();
    };
    api("/api/transcribe", { method: "POST", headers: { "Content-Type": type }, body: blob }).then(function (res) {
      noteLatency(Date.now() - t0);
      var note = engineNote(r);
      if (res && res.label) showEngine(res.label, note);
      var text = (res && res.text || "").trim();
      if (L) {
        if (text) { placeLiveText(L, text); toast("Check the text, then tap Send"); }
        else if (L.agree.text()) { placeLiveText(L, L.agree.text()); toast("Check the text, then tap Send"); }
        else { placeLiveText(L, ""); toast("Didn't catch that. Try again."); }
        return;
      }
      if (!text) { toast("Didn't catch that. Try again."); return; }
      insertText(text);
      toast("Check the text, then tap Send");
    }).catch(function (e) {
      noteLatency(null, "final failed after " + ((Date.now() - t0) / 1000).toFixed(2) + " s: " + e.message);
      if (L && L.agree.text()) {
        placeLiveText(L, L.agree.text());
        toast("Final transcription failed (" + e.message + "). Check the live text, then tap Send.", "error");
        return;
      }
      if (L) placeLiveText(L, "");
      var fb = sttMode() === "auto" && state.config.stt_prefer_server && speechCtor();
      if (fb) state.serverSTTFailedAt = Date.now();
      toast("Transcription failed: " + e.message + (fb ? ". Tap the mic again to use " + builtinLabel() + "." : ""), "error");
    }).finally(function () {
      state.transcribing = false;
      var act = state.afterRec;
      setMicState(null);
      autosize();
      if (act === "send") sendAfterDictation();
      else if (act === "edit") editAfterDictation();
    });
  }
  // Voice typing "Edit" / "Send": stop listening, then act on the transcript
  // once it's final. Tapping during "Transcribing…" just picks the action.
  function recFinishAs(act) {
    if (!state.rec && !state.transcribing) return;
    state.afterRec = act;
    $("recEdit").classList.toggle("pending", act === "edit");
    $("recSend").classList.toggle("pending", act === "send");
    if (state.rec) stopRecording();
  }
  function editAfterDictation() {
    var t = $("input");
    t.focus();
    setComposerFloat(true); // Car Mode: float under the title, like a tap
    try { t.setSelectionRange(t.value.length, t.value.length); } catch (e) {}
  }
  function sendAfterDictation() {
    var t = $("input");
    if (!t.value.trim()) return;
    if (document.activeElement === t) t.blur(); // no keyboard, no float
    setComposerFloat(false);
    $("toast").hidden = true; // drop "Check the text, then tap Send"
    sendMessage();
  }

  // --- live typing (server STT while you speak) ---
  // Alongside MediaRecorder (which still feeds the final pass), the mic is
  // tapped with an AudioWorklet (ScriptProcessor fallback), downsampled to
  // 16 kHz mono PCM, and the current window (utterance so far, trimmed at
  // committed words; see live-stt.js) is POSTed to /api/transcribe/partial
  // every ~0.7 s, one request at a time. Stable words (LocalAgreement-2) and
  // the tentative tail are shown in the box between the text that was there
  // before. Any failure just stops live updates; the final pass still runs.
  var LIVE = window.TMLive;
  var LIVE_TICK_MS = 250, LIVE_STEP_SAMPLES = 0.7 * 16000, LIVE_MIN_SAMPLES = 0.6 * 16000;
  function liveAvailable() {
    return !!(LIVE && state.config.stt_live && liveTypingOn() && (window.AudioContext || window.webkitAudioContext) && window.fetch);
  }
  // Runs before getUserMedia so the AudioContext is created during the tap.
  function prepareLive() {
    if (!liveAvailable()) return null;
    var AC = window.AudioContext || window.webkitAudioContext, ctx = null;
    try { ctx = new AC({ sampleRate: 16000 }); } catch (e) { try { ctx = new AC(); } catch (e2) { return null; } }
    try { var p = ctx.resume && ctx.resume(); if (p && p.catch) p.catch(function () {}); } catch (e) {}
    return { ctx: ctx };
  }
  function closeLiveAudio(L) {
    try { L.node && L.node.disconnect(); } catch (e) {}
    try { L.src && L.src.disconnect(); } catch (e) {}
    try { L.ctx && L.ctx.state !== "closed" && L.ctx.close(); } catch (e) {}
    L.node = L.src = null;
  }
  function liveId() {
    var a = new Uint8Array(12);
    (window.crypto || {}).getRandomValues ? crypto.getRandomValues(a) : a.forEach(function (_, i) { a[i] = Math.random() * 256; });
    return Array.prototype.map.call(a, function (b) { return ("0" + b.toString(16)).slice(-2); }).join("");
  }
  function startLive(r, L) {
    var t = $("input"), cur = t.value, sel = composeSelection(), start = sel.start, end = sel.end;
    var before = cur.slice(0, start);
    L.original = cur;
    L.before = before + (before && !/\s$/.test(before) ? " " : "");
    L.after = cur.slice(end);
    L.id = liveId(); L.seq = 0; L.applied = 0;
    L.chunks = []; L.total = 0; L.base = 0;   // 16 kHz Int16 chunks; L.base = samples dropped from the front
    L.winStart = 0; L.sentAt = 0; L.inflight = false; L.fails = 0;
    L.agree = new LIVE.Agreement(); L.shown = false; L.dead = false; L.failed = "";
    L.maxSamples = Math.min(state.config.stt_live_max_secs || 14, 14) * 16000 - 8000;
    var ctx = L.ctx;
    function onAudio(f32) {
      if (L.dead) return;
      var pcm = L.resampler.push(f32);
      if (pcm.length) { L.chunks.push(pcm); L.total += pcm.length; }
    }
    try {
      L.src = ctx.createMediaStreamSource(r.stream);
    } catch (e) {
      // Some engines can't feed a 16 kHz context from a 48 kHz mic.
      try { ctx.close(); } catch (x) {}
      try { ctx = L.ctx = new (window.AudioContext || window.webkitAudioContext)(); L.src = ctx.createMediaStreamSource(r.stream); }
      catch (e2) { liveFailed(L, "no audio access"); return; }
    }
    L.resampler = new LIVE.Resampler(ctx.sampleRate);
    var sink = ctx.createGain(); sink.gain.value = 0; sink.connect(ctx.destination);
    function useScriptProcessor() {
      if (L.dead) return;
      try {
        var sp = ctx.createScriptProcessor(4096, 1, 1);
        sp.onaudioprocess = function (e) { onAudio(new Float32Array(e.inputBuffer.getChannelData(0))); };
        L.src.connect(sp); sp.connect(sink); L.node = sp;
      } catch (e) { liveFailed(L, "audio capture unavailable"); }
    }
    if (ctx.audioWorklet && window.AudioWorkletNode) {
      ctx.audioWorklet.addModule("/app/pcm-worklet.js").then(function () {
        if (L.dead) return;
        var node = new AudioWorkletNode(ctx, "tm-pcm-capture", { numberOfInputs: 1, numberOfOutputs: 1, channelCount: 1 });
        node.port.onmessage = function (e) { onAudio(e.data); };
        L.src.connect(node); node.connect(sink); L.node = node;
      }).catch(useScriptProcessor);
    } else useScriptProcessor();
    try { if (ctx.state === "suspended") ctx.resume(); } catch (e) {}
    L.tick = setInterval(function () { liveTick(r, L); }, LIVE_TICK_MS);
    r.live = L;
  }
  function stopLive(L) {
    if (L.stopped) return;
    L.stopped = true; L.dead = true;
    clearInterval(L.tick);
    closeLiveAudio(L);
  }
  // Streaming broke: stop live updates (the final pass still runs on Done).
  function liveFailed(L, why) {
    if (L.dead && L.failed) return;
    L.failed = why || "error";
    stopLive(L);
    if (state.rec && state.rec.live === L) showEngine(serverLabel(), engineNote(state.rec));
  }
  function liveWindow(L, from, to) {
    var out = new Int16Array(to - from), pos = L.base, n = 0;
    for (var i = 0; i < L.chunks.length && pos < to; i++) {
      var c = L.chunks[i], cEnd = pos + c.length;
      if (cEnd > from) {
        var a = Math.max(0, from - pos), b = Math.min(c.length, to - pos);
        out.set(c.subarray(a, b), n); n += b - a;
      }
      pos = cEnd;
    }
    return out.subarray(0, n);
  }
  function liveDropBefore(L, sample) { // free audio that can't be sent again
    while (L.chunks.length && L.base + L.chunks[0].length <= sample) { L.base += L.chunks[0].length; L.chunks.shift(); }
  }
  function liveTick(r, L) {
    if (L.dead || L.inflight || state.rec !== r) return;
    if (L.holdUntil && Date.now() < L.holdUntil) return;
    var step = state.config.stt_live_step_ms ? state.config.stt_live_step_ms / 1000 * 16000 : LIVE_STEP_SAMPLES;
    if (L.total - L.sentAt < step) return;
    if (L.total - L.winStart > L.maxSamples) {
      // Passes are falling behind and no cut point came back: keep what's
      // shown and restart the window near the newest audio.
      L.agree.lockAll();
      L.winStart = Math.max(L.winStart, L.total - 4 * 16000);
      liveDropBefore(L, L.winStart);
    }
    if (L.total - L.winStart < LIVE_MIN_SAMPLES) return;
    var pcm = liveWindow(L, L.winStart, L.total);
    var seq = ++L.seq, winStart = L.winStart, end = L.total;
    L.inflight = true; L.sentAt = end;
    var t0 = Date.now();
    fetch("/api/transcribe/partial", {
      method: "POST", credentials: "same-origin", body: pcm,
      headers: { "Content-Type": "audio/L16;rate=16000;channels=1", "X-STT-Stream": L.id, "X-STT-Seq": String(seq) },
    }).then(function (res) {
      return res.json().catch(function () { return {}; }).then(function (body) { return { status: res.status, ok: res.ok, body: body }; });
    }).then(function (x) {
      L.inflight = false;
      if (L.dead || seq <= L.applied || winStart !== L.winStart) return; // stale
      if (!x.ok) {
        if (x.status === 429 && x.body && x.body.retry_after_ms) L.holdUntil = Date.now() + Math.min(60000, x.body.retry_after_ms);
        if (x.status === 409 || x.status === 429) return; // stale / busy / rate limit: try again later
        if (x.status === 401 || x.status === 404 || x.status === 503 || x.status === 400 || x.status === 413 || x.status === 415 || ++L.fails >= 3) {
          liveFailed(L, (x.body && x.body.error) || "HTTP " + x.status);
        }
        return;
      }
      L.fails = 0; L.applied = seq;
      (L.lat || (L.lat = [])).push(Date.now() - t0);
      L.agree.update(x.body.words || [], (end - winStart) / 16000);
      var cut = L.agree.trim((end - winStart) / 16000);
      if (cut > 0) { L.winStart = Math.min(end, winStart + Math.round(cut * 16000)); liveDropBefore(L, L.winStart); }
      L.lastMs = x.body.ms;
      renderLive(L);
    }).catch(function (e) {
      L.inflight = false;
      if (!L.dead && ++L.fails >= 3) liveFailed(L, (e && e.message) || "network error");
    });
  }
  function renderLive(L) {
    var tx = L.agree.text();
    if (!tx && !L.shown) return;
    var t = $("input");
    t.value = L.before + tx + (tx && L.after && !/^\s/.test(L.after) ? " " : "") + L.after;
    L.shown = true;
    var pos = (L.before + tx).length;
    if (document.activeElement === t) { try { t.setSelectionRange(pos, pos); } catch (e) {} }
    autosize();
    t.scrollTop = t.scrollHeight;
    var tent = L.agree.tentativeText();
    $("input").dataset.liveTentative = tent; // for tests/debugging
  }
  // Puts the final text where the live text was (keeping what was already
  // in the box before/after it) and leaves the cursor after it.
  // --- live typing with the browser's speech recognition ---
  // Runs next to MediaRecorder: interim words go into the box as you speak;
  // on Done the server's transcript of the recording replaces them (kept if
  // the server fails). If the recognizer can't get the mic (some phones give
  // it to only one of the two), the server's live passes take over when
  // available. A level meter on the recorded stream notices a silent
  // recording (the recognizer won the mic) so silence isn't transcribed.
  function speechLiveOK() {
    return !!(speechCtor() && liveTypingOn() && !builtinBroken() && isDesk());
  }
  function startSpeechLive(r, live) {
    var t = $("input"), cur = t.value, sel = composeSelection();
    var before = cur.slice(0, sel.start);
    var S = { kind: "speech", original: cur, before: before + (before && !/\s$/.test(before) ? " " : ""), after: cur.slice(sel.end),
      committed: "", finals: "", interim: "", shown: false, dead: false, restarts: 0, heard: false, peak: 0, meterOK: false, sr: null, meter: null };
    S.agree = { text: function () { return (S.committed + S.finals + S.interim).replace(/\s+/g, " ").trim(); } };
    r.speech = S;
    var Ctor = speechCtor();
    function render() {
      var tx = S.agree.text();
      if (!tx && !S.shown) return;
      t.value = S.before + tx + (tx && S.after && !/^\s/.test(S.after) ? " " : "") + S.after;
      S.shown = true;
      autosize(); t.scrollTop = t.scrollHeight;
    }
    function fail(err) {
      S.failed = err;
      stopSpeechLive(S);
      // Fall back to the server's live passes (Groq) if nothing was heard yet.
      if (!S.heard && live && state.rec === r && !r.live) { startLive(r, live); live = null; }
    }
    function newRec() {
      var sr = new Ctor();
      sr.lang = speechLang(); sr.continuous = true; sr.interimResults = true; sr.maxAlternatives = 1;
      sr.onresult = function (e) {
        if (S.dead) return;
        var finals = "", interim = "";
        for (var i = 0; i < e.results.length; i++) {
          var res = e.results[i], tr = res[0] ? res[0].transcript : "";
          if (res.isFinal) finals += tr + " "; else interim += tr + " ";
        }
        S.finals = finals; S.interim = interim; S.restarts = 0;
        if (S.agree.text()) S.heard = true;
        render();
      };
      sr.onerror = function (e) {
        var err = (e && e.error) || "unknown";
        if (S.dead || err === "aborted" || err === "no-speech") return;
        S.fatal = err;
      };
      sr.onend = function () {
        if (S.dead) return;
        S.committed += S.finals + S.interim; S.finals = ""; S.interim = "";
        if (S.fatal) { fail(S.fatal); return; }
        if (++S.restarts > 5) { fail("stopped"); return; }
        try { S.sr = newRec(); S.sr.start(); } catch (x) { fail("restart failed"); }
      };
      return sr;
    }
    try { S.sr = newRec(); S.sr.start(); } catch (e) { fail("start failed"); return; }
    showEngine(builtinLabel() + " (live) + " + serverLabel(), engineNote(r));
    // Level meter on the recorded stream (uses the AudioContext made during
    // the tap, so it isn't left suspended).
    var ctx = live && live.ctx;
    if (ctx) {
      try {
        var src = ctx.createMediaStreamSource(r.stream), an = ctx.createAnalyser();
        an.fftSize = 2048; src.connect(an);
        var buf = new Float32Array(an.fftSize);
        S.meter = { src: src, an: an, timer: setInterval(function () {
          if (ctx.state !== "running") return;
          an.getFloatTimeDomainData(buf);
          var sum = 0;
          for (var i = 0; i < buf.length; i++) sum += buf[i] * buf[i];
          var rms = Math.sqrt(sum / buf.length);
          S.meterOK = true;
          if (rms > S.peak) S.peak = rms;
        }, 120) };
      } catch (e) { S.meter = null; }
    }
    // The tap's AudioContext stays for the meter / a Groq fallback; closed in stopSpeechLive.
    S.ctxOwner = live;
  }
  function stopSpeechLive(S) {
    if (S.dead) return;
    S.dead = true;
    try { S.sr && S.sr.abort(); } catch (e) {}
    if (S.meter) { clearInterval(S.meter.timer); try { S.meter.src.disconnect(); } catch (e) {} }
    var r = state.rec;
    // Close the tap's AudioContext unless the server live fallback took it.
    if (S.ctxOwner && !(r && r.live === S.ctxOwner)) closeLiveAudio(S.ctxOwner);
  }
  function placeLiveText(L, text) {
    var t = $("input");
    if (!text) { t.value = L.original; autosize(); return; }
    t.value = L.before + text + (L.after && !/^\s/.test(L.after) ? " " : "") + L.after;
    delete t.dataset.liveTentative;
    autosize();
    t.focus();
    var pos = (L.before + text).length;
    try { t.setSelectionRange(pos, pos); } catch (e) {}
  }

  // ---------- pairing ----------
  function showPair(open) {
    $("pairView").hidden = !open;
    if (open) { refreshPairing(); clearInterval(state.pairPoll); state.pairPoll = setInterval(refreshPairing, 1000); }
    else { clearInterval(state.pairPoll); state.pairPoll = null; }
  }
  // Cookies: always a row in Settings > Debug (highlighted when needed, with
  // a "Cookies needed" hint above the collapsed fold);
  // the pairing-screen button only shows when cookies are actually needed:
  // none saved yet (and not dev fake pairing), or Google rejected the saved
  // ones (google.auth_expired). /admin/cookies stays reachable by URL.
  function cookiesNeeded(p) {
    if (!p) return false;
    var st = p.pairing || {}, g = p.google || {};
    if (g.auth_expired || g.AuthExpired) return true;
    return !p.cookies_saved && !st.fake;
  }
  // Inside the Messages Enhanced Android app (a TWA), offer its native cookie
  // sender. The TWA marks itself via the android-app:// referrer on launch;
  // remember it so it survives the login redirect and in-app navigation.
  (function () {
    var KEY = "me_android_twa";
    try {
      if (document.referrer.indexOf("android-app://com.ubermicrostudios.messagesenhanced") === 0) sessionStorage.setItem(KEY, "1");
      if (sessionStorage.getItem(KEY) === "1") $("androidCookiesBtn").hidden = false;
    } catch (e) { /* storage blocked */ }
  })();
  function updateCookiesEntry(p) {
    if (!p || p.cookies_saved === undefined) return;
    var need = cookiesNeeded(p);
    $("cookiesBtn").classList.toggle("needed", need);
    $("cookiesHint").hidden = !need; // the Debug fold itself always starts collapsed
    $("cookiesDesc").textContent = need
      ? "Needed: paste your Google cookies to pair (easier from a computer)"
      : "Saved. Update them here if Google signs you out (easier from a computer)";
  }
  function renderPairing(p) {
    var st = (p && p.pairing) || { state: "idle" };
    var busy = st.state === "starting" || st.state === "emoji";
    $("pairStart").hidden = busy;
    $("pairStart").textContent = st.state === "error" ? "Try again" : "Start pairing";
    $("pairCancel").hidden = !busy;
    $("pairCookies").hidden = busy || !cookiesNeeded(p);
    updateCookiesEntry(p);
    var wrap = $("pairEmojiWrap");
    if (st.state === "emoji" && st.emoji) {
      wrap.hidden = false;
      $("pairTitle").textContent = "Tap this on your phone";
      $("pairEmojiText").textContent = st.emoji;
      var img = $("pairEmojiImg");
      if (st.emoji_svg && img.dataset.src !== st.emoji_svg) {
        img.dataset.src = st.emoji_svg;
        wrap.classList.remove("img-ok");
        img.src = st.emoji_svg;
      }
      $("pairMsg").textContent = "Open Google Messages on your phone and tap the matching emoji.";
    } else {
      wrap.hidden = true;
      $("pairTitle").textContent = st.state === "success" ? "Paired" : "Pair with your phone";
      var msg = st.message || "";
      if (st.state === "idle") msg = p && p.cookies_saved ? "Google cookies are saved. Tap Start, then look for the emoji." : "First paste your Google cookies on the Cookies page (from a desktop).";
      if (st.state === "starting") msg = "Contacting Google… keep your phone nearby and unlocked.";
      $("pairMsg").textContent = msg;
    }
  }
  function refreshPairing() {
    return api("/api/app/pairing").then(function (p) { renderPairing(p); updateStatusFromPairing(p); return p; }).catch(function () {});
  }

  // ---------- status ----------
  // Header status: a small colored dot (green connected, amber connecting /
  // not paired / phone offline, red disconnected); tap it for the text.
  var CONN_DETAIL = {
    "Connected": "Connected to Google Messages",
    "Phone offline": "Connected, but your phone isn't responding (check it's online)",
    "Not paired": "Not paired with your phone yet. Tap Pair phone.",
    "Connecting": "Connecting to Google Messages…",
    "Disconnected": "Disconnected from Google Messages. Retrying…",
  };
  function setConn(text, kind) {
    var b = $("conn");
    b.className = "conn-dot conn-" + kind;
    $("connText").textContent = text;
    b.title = CONN_DETAIL[text] || text;
    b.setAttribute("aria-label", "Connection: " + text);
    b.dataset.state = text;
  }
  function connStatusText() { var b = $("conn"); return b.title || $("connText").textContent; }
  function updateStatusFromPairing(p) {
    updateCookiesEntry(p);
    var g = p && p.google || {};
    var needsPair = g.needs_pairing || g.NeedsPairing || g.needs_repair || g.NeedsRepair;
    var connected = g.connected || g.Connected;
    var connecting = g.connecting || g.Connecting || (p && p.pairing && (p.pairing.state === "starting" || p.pairing.state === "emoji"));
    $("pairBtn").hidden = !needsPair && !(p && p.pairing && p.pairing.state === "emoji");
    if (needsPair) setConn("Not paired", "warn");
    else if (connected) {
      var phone = g.phone_responding !== undefined ? g.phone_responding : g.PhoneResponding;
      setConn(phone === false ? "Phone offline" : "Connected", phone === false ? "warn" : "ok");
    } else if (connecting) setConn("Connecting", "warn");
    else setConn("Disconnected", "bad");
    if (p && p.pairing && p.pairing.state === "emoji" && $("pairView").hidden && !state.pairAutoShown) { state.pairAutoShown = true; openPair(); }
  }

  // ---------- live updates ----------
  // The server's /api/events stream sends *named* events ("event:
  // messages", "conversations", "typing", "status"), which EventSource only
  // delivers to addEventListener(name), never to onmessage. Proxies can also
  // hold the stream back entirely: Cloudflare quick tunnels buffer
  // text/event-stream, so over the car's https link nothing arrives. The
  // server always sends a "status" event right after connecting, so if none
  // has arrived a few seconds after open the stream is treated as not live
  // and the page polls instead (open conversation every 4 s, list every
  // 12 s). Separately, every send is followed up until it leaves "Sending".
  var es = null, refreshTimer = null;
  var sse = { openedAt: 0, lastEvent: 0 };
  var SSE_GRACE_MS = 4000, POLL_MS = 4000;
  function scheduleRefresh(convId) {
    clearTimeout(refreshTimer);
    refreshTimer = setTimeout(function () {
      loadConversations();
      if (state.current && (!convId || convId === state.current)) loadMessages(false, true);
    }, 150);
  }
  function onStreamEvent(e) {
    sse.lastEvent = Date.now();
    var ev; try { ev = JSON.parse(e.data); } catch (x) { return; }
    var type = ev.type || e.type;
    if (type === "conversations" || type === "messages") scheduleRefresh(ev.conversation_id);
    else if (type === "typing") setTyping(ev.conversation_id, ev.sender_number || ev.sender_name, ev.sender_name, !!ev.typing);
  }
  function connectEvents() {
    if (!window.EventSource) return;
    try { es = new EventSource("/api/events"); } catch (e) { return; }
    ["messages", "conversations", "typing", "status", "heartbeat"].forEach(function (t) { es.addEventListener(t, onStreamEvent); });
    es.onmessage = onStreamEvent; // unnamed events, if a server ever sends them
    es.onopen = function () {
      var reconnect = sse.openedAt > 0;
      sse.openedAt = Date.now(); fetchTyping();
      // Events sent while the stream was down are lost: catch up.
      if (reconnect) { loadConversations(); if (state.current) loadMessages(false, true); checkVersion(true); }
    };
    es.onerror = function () { /* browser retries automatically */ };
  }
  // ---------- auto refresh after a deploy ----------
  // The page carries the build version it was served with (window.TM_VERSION,
  // from <meta name="app-version">). Ask the server for the running one on
  // load, on resume, on stream reconnect and every few minutes; when it
  // differs, update the service worker and reload, but only while nothing is
  // in progress (empty composer, no dictation, no sheet or menu open). The
  // open conversation is reopened after the reload.
  var upd = { want: false, last: 0, reloading: false };
  function checkVersion(force) {
    if (!window.TM_VERSION || upd.want || (!force && Date.now() - upd.last < 20000)) return;
    upd.last = Date.now();
    fetch("/api/app/version", { credentials: "same-origin", cache: "no-store" }).then(function (r) { return r.ok ? r.json() : null; }).then(function (j) {
      if (j && j.version && j.version !== window.TM_VERSION) { upd.want = true; maybeReload(); }
    }).catch(function () {});
  }
  function busyForReload() {
    var t = $("input");
    return !!state.rec || !!state.transcribing || !!(t && t.value && t.value.trim()) || state.pending.length > 0 ||
      sheetOpen() || !!state.selecting || !!state.menuFor || !!state.convMenuFor || emojiOpen() || !!ct ||
      ["imageViewer", "trashView", "detailsView", "confirmView", "signOutView", "settingsView", "fwdView", "infoView", "newChatView", "pairView", "appMenu"].some(function (id) { var e = $(id); return e && !e.hidden; });
  }
  function maybeReload() {
    if (!upd.want || upd.reloading || busyForReload()) return;
    upd.reloading = true;
    var url = location.pathname + (state.current && !state.folder ? "?c=" + encodeURIComponent(state.current) : "");
    var go = function () { location.replace(url); };
    var p = window.TMPWA && window.TMPWA.updateSW ? window.TMPWA.updateSW() : Promise.resolve();
    Promise.race([p, new Promise(function (r) { setTimeout(r, 3000); })]).then(go, go);
  }
  setInterval(function () { if (upd.want) maybeReload(); }, 3000);
  setInterval(function () { checkVersion(true); }, 4 * 60 * 1000);
  // Live = connected, and an event arrived since this connection opened and recently.
  function streamLive() {
    // (the server sends a heartbeat every 25 s, so 60 s of silence = stalled)
    return !!es && es.readyState === 1 && sse.lastEvent >= sse.openedAt && Date.now() - sse.lastEvent < 60000;
  }
  var pollTick = 0;
  function pollIfStreamDead() {
    if (document.hidden) return;
    if (streamLive()) return;
    if (es && es.readyState === 1 && Date.now() - sse.openedAt < SSE_GRACE_MS) return; // just opened
    if (state.current) loadMessages(false, true);
    if (pollTick++ % 2 === 0) loadConversations();
  }
  // After a send: re-check the open conversation until the bubble leaves
  // "Sending…" (Google's copy replaced the placeholder), even if live
  // events never come.
  var followTimer = null;
  var FOLLOW_MS = [1200, 2500, 4500, 8000, 13000, 20000, 30000];
  function followSend(convID) {
    clearTimeout(followTimer);
    var i = 0;
    function step() {
      if (state.current !== convID) return;
      loadMessages(false, true).then(function () {
        if (state.current !== convID || !state.mineSending || ++i >= FOLLOW_MS.length) return;
        followTimer = setTimeout(step, FOLLOW_MS[i] - FOLLOW_MS[i - 1]);
      });
    }
    followTimer = setTimeout(step, FOLLOW_MS[0]);
  }

  // ---------- message menu (⋮) + delete ----------
  // One shared popover, positioned next to the tapped ⋮ button. Closes on an
  // outside tap, Escape, or when the message goes away.
  function viewportProbe() {
    var p = $("vpProbe");
    if (!p) {
      p = el("div"); p.id = "vpProbe"; p.setAttribute("aria-hidden", "true");
      p.style.cssText = "position:fixed;inset:0;visibility:hidden;pointer-events:none;z-index:-1";
      document.body.appendChild(p);
    }
    return p;
  }
  function openMsgMenu(btn, m) {
    if (isDesk()) { closeMsgMenu(); openMsgSheet(btn, m); return; }
    closeMsgMenu();
    closeConvMenu();
    state.menuFor = { msg: m, btn: btn };
    btn.classList.add("open");
    btn.setAttribute("aria-expanded", "true");
    var menu = $("msgMenu");
    menu.hidden = false;
    placePopover(menu, btn.getBoundingClientRect());
  }
  // Place a fixed popover below (or above) an anchor rect given in client
  // coordinates. Works in the menu's own CSS px so this stays right under UI
  // zoom: a full-viewport fixed probe gives the rect-units -> CSS px factor.
  function placePopover(menu, br) {
    var vp = viewportProbe(), pr = vp.getBoundingClientRect();
    var k = pr.width ? vp.offsetWidth / pr.width : 1, vw = vp.offsetWidth, vh = vp.offsetHeight;
    var mw = menu.offsetWidth, mh = menu.offsetHeight;
    var r = { left: (br.left - pr.left) * k, top: (br.top - pr.top) * k, bottom: (br.bottom - pr.top) * k };
    var left = Math.max(16, Math.min(vw - mw - 16, r.left));
    var top = r.bottom + 10;
    if (top + mh > vh - 16) top = r.top - mh - 10;
    menu.style.left = left + "px";
    menu.style.top = Math.max(16, top) + "px";
  }
  function closeMsgMenu() {
    var f = state.menuFor;
    state.menuFor = null;
    $("msgMenu").hidden = true;
    if (f && f.btn) { f.btn.classList.remove("open"); f.btn.setAttribute("aria-expanded", "false"); }
  }
  function showConfirm(m) {
    state.confirmFor = m;
    var many = Array.isArray(m) && m.length > 1;
    if (Array.isArray(m)) m = m[0];
    $("confirmTitle").textContent = many ? "Delete " + state.confirmFor.length + " messages?" : "Delete this message?";
    var c = state.convs.find(function (x) { return x.ConversationID === (m.ConversationID || state.current); }) || {};
    var google = sourcePlatformOf(c) === "sms";
    $("confirmDesc").textContent = google
      ? "It's removed from your phone and Messages on the web. The other person still has it."
      : "It's removed from this server only. The other person still has it.";
    $("confirmView").hidden = false;
    $("confirmCancel").focus();
  }
  function hideConfirm() {
    state.confirmFor = null;
    $("confirmView").hidden = true;
    var ok = $("confirmOk");
    ok.disabled = false; ok.textContent = "Delete";
  }
  function confirmDelete() {
    var m = state.confirmFor;
    if (!m) return;
    var ok = $("confirmOk");
    ok.disabled = true; ok.textContent = "Deleting…";
    var list = Array.isArray(m) ? m : [m], res = null, chain = Promise.resolve();
    list.forEach(function (x) {
      chain = chain.then(function () {
        return postJSON("/api/app/messages/delete", { message_id: x.MessageID }).then(function (r) {
          res = r;
          var rows = $("messages").querySelectorAll(".msg-row");
          for (var i = 0; i < rows.length; i++) if (rows[i].dataset.id === String(x.MessageID)) rows[i].remove();
        });
      });
    });
    chain.then(function () {
      hideConfirm();
      stopSelect();
      toast((res && res.scope === "local" ? "Deleted from this server" : "Deleted") + (list.length > 1 ? " (" + list.length + ")" : ""));
      loadMessages(false); loadConversations();
    }).catch(function (e) {
      hideConfirm();
      if (e.message !== "login required") toast("Not deleted: " + e.message, "error");
    });
  }

  // Pin icon (pinned conversations).
  var PIN_ICON = "M16 9V4h1c.55 0 1-.45 1-1s-.45-1-1-1H7c-.55 0-1 .45-1 1s.45 1 1 1h1v5c0 1.66-1.34 3-3 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3z";

  // ---------- refresh from Google ----------
  // On page load the server re-reads the conversation list from Google
  // (names, members, archived/spam/trash state; at most once a minute).
  // Settings > Refresh everything also re-downloads every photo and group
  // icon (at most once every 5 minutes) and shows progress. The server's
  // avatar_version changes whenever a cached photo changes: the page then
  // drops its in-memory photos and repaints.
  var syncState = { avatarVersion: 0, lastCheck: 0, polling: null };
  function refreshPost(scope) {
    return fetch("/api/app/refresh", { method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ scope: scope }) })
      .then(function (r) {
        if (r.status === 401) throw new Error("login required");
        return r.json().catch(function () { return {}; }).then(function (b) { b = b || {}; b._status = r.status; return b; });
      });
  }
  function resetAvatarCache() {
    var old = [];
    Object.keys(avatarCache).forEach(function (k) {
      var e = avatarCache[k];
      if (e && e.pending) return;
      if (e && e.url) old.push(e.url);
      delete avatarCache[k];
    });
    // Revoke the old photos once the repaint has replaced them.
    setTimeout(function () { old.forEach(function (u) { try { URL.revokeObjectURL(u); } catch (x) {} }); }, 30000);
    profileKey = "";
    if (!state.folder) renderConversations();
    if (state.current) renderCurrent(false);
  }
  function noteAvatarVersion(v) {
    v = +v || 0;
    if (!v) return;
    var changed = syncState.avatarVersion && v !== syncState.avatarVersion;
    syncState.avatarVersion = v;
    if (changed) resetAvatarCache();
  }
  function checkAvatarVersion(force) {
    if (!force && Date.now() - syncState.lastCheck < 10 * 60 * 1000) return;
    syncState.lastCheck = Date.now();
    api("/api/app/refresh").then(function (st) { noteAvatarVersion(st && st.avatar_version); }).catch(function () {});
  }
  function refreshDesc(text) { var d = $("refreshAllDesc"); if (d) d.textContent = text; }
  function refreshProgressText(st) {
    if (!st) return "";
    if (st.running) {
      if (st.stage === "Updating photos") return "Updating photos… " + Math.max(0, st.photos_queued - st.photos_left) + " of " + st.photos_queued;
      return (st.stage || "Refreshing") + "… " + (st.conversations ? st.conversations + " conversations" : "");
    }
    if (st.error) return "Refresh failed: " + st.error;
    if (st.finished_at_ms) return "Done: " + st.conversations + " conversations, " + st.photos_queued + " photos checked · " + new Date(st.finished_at_ms).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
    return "";
  }
  function pollRefresh(showProgress) {
    clearTimeout(syncState.polling);
    api("/api/app/refresh").then(function (st) {
      if (showProgress) refreshDesc(refreshProgressText(st));
      noteAvatarVersion(st.avatar_version);
      if (st.running) { syncState.polling = setTimeout(function () { pollRefresh(showProgress); }, 1500); return; }
      if (showProgress) {
        $("refreshAllBtn").disabled = false;
        if (!st.error) toast("Everything is up to date", "ok");
      }
      loadConversations();
    }).catch(function () { if (showProgress) { $("refreshAllBtn").disabled = false; refreshDesc("Couldn't check the refresh status"); } });
  }
  function refreshOnLoad() {
    refreshPost("list").then(function (st) {
      noteAvatarVersion(st.avatar_version);
      if (st._status === 202) syncState.polling = setTimeout(function () { pollRefresh(false); }, 3000);
    }).catch(function () {});
  }
  function refreshEverything() {
    var btn = $("refreshAllBtn");
    btn.disabled = true;
    refreshDesc("Starting…");
    refreshPost("all").then(function (st) {
      if (st._status === 429) {
        btn.disabled = false;
        var mins = Math.max(1, Math.ceil((st.retry_after_sec || 60) / 60));
        refreshDesc("Refreshed recently. Try again in " + mins + " minute" + (mins === 1 ? "" : "s") + ".");
        return;
      }
      if (st._status >= 400) { btn.disabled = false; refreshDesc("Couldn't refresh: " + (st.error || "HTTP " + st._status)); return; }
      refreshDesc(refreshProgressText(st) || "Refreshing…");
      pollRefresh(true);
    }).catch(function (e) { btn.disabled = false; refreshDesc("Couldn't refresh: " + e.message); });
  }

  // ---------- @Grok / @Groq (server-wide settings) ----------
  var grokState = null;
  function renderBotSwitch(btn, on, available) {
    btn.setAttribute("aria-checked", on ? "true" : "false");
    btn.classList.toggle("on", on);
    btn.disabled = !available;
  }
  function renderGrok() {
    var st = grokState;
    if (!st) return;
    var on = !!st.enabled, gq = !!st.groq_enabled, anyKey = !!(st.key_configured || st.groq_key_configured);
    renderBotSwitch($("grokEnable"), on, st.key_configured);
    renderBotSwitch($("groqEnable"), gq, st.groq_key_configured);
    var radios = document.querySelectorAll('input[name="grokTrigger"]');
    for (var i = 0; i < radios.length; i++) { radios[i].checked = radios[i].value === (st.trigger || "me"); radios[i].disabled = !anyKey; }
    $("grokTriggerRow").classList.toggle("disabled", !anyKey);
    $("grokDesc").textContent = !st.key_configured
      ? "Not available yet: the server has no xAI API key (XAI_API_KEY)"
      : on ? "On · " + st.replies_today + " of " + st.daily_limit + " replies used today" : "Grok answers in the conversation, starting with “🤖 From Grok:”";
    $("groqDesc").textContent = !st.groq_key_configured
      ? "Not available: the server has no Groq API key (GROQ_API_KEY)"
      : gq ? "On · " + st.groq_replies_today + " of " + st.groq_daily_limit + " replies used today" + (st.groq_search ? " · live web search" : " · no live search")
      : "Groq answers in the conversation, starting with “🤖 From Groq:” (free tier)";
    if (st.daily_limit && st.groq_daily_limit) {
      $("grokLimitsDesc").textContent = "Each bot replies at most once per chat every 30 seconds (@Grok " + st.daily_limit + " a day, @Groq " + st.groq_daily_limit + " a day). Applies to every signed-in device.";
    }
  }
  function loadGrok() {
    api("/api/app/grok").then(function (st) { grokState = st; renderGrok(); }).catch(function () {});
  }
  function saveGrok(enabled, trigger, groqEnabled) {
    var body = { enabled: enabled, trigger: trigger };
    if (typeof groqEnabled === "boolean") body.groq_enabled = groqEnabled;
    postJSON("/api/app/grok", body).then(function (st) { grokState = st; renderGrok(); })
      .catch(function (e) { toast("Couldn't save the @Grok/@Groq setting: " + e.message, "error"); loadGrok(); });
  }

  // ---------- folders (view only) ----------
  // Archived / Spam / Blocked are listed live from Google Messages
  // (GET /api/app/folder). No archive / unarchive / spam / block actions.
  var FOLDER_LABELS = { archived: "Archived", spam: "Spam", blocked: "Blocked" };
  function enterFolder(name) {
    if (!FOLDER_LABELS[name]) return;
    closeThread();
    state.folder = { name: name, label: FOLDER_LABELS[name], loading: true, error: "" };
    document.body.classList.add("in-folder");
    $("convHeadTitle").hidden = true;
    $("folderBanner").hidden = false;
    $("folderName").textContent = state.folder.label;
    state.convs = [];
    renderConversations();
    loadFolder();
  }
  function loadFolder() {
    var f = state.folder;
    if (!f) return Promise.resolve();
    return api("/api/app/folder?name=" + encodeURIComponent(f.name)).then(function (list) {
      if (state.folder !== f) return;
      f.loading = false; f.error = "";
      state.convs = list || [];
      renderConversations();
    }).catch(function (e) {
      if (state.folder !== f || e.message === "login required") return;
      f.loading = false; f.error = e.message;
      state.convs = [];
      renderConversations();
    });
  }
  function exitFolder() {
    if (!state.folder) return;
    closeThread();
    state.folder = null;
    document.body.classList.remove("in-folder");
    $("convHeadTitle").hidden = false;
    $("folderBanner").hidden = true;
    state.convs = [];
    renderConversations();
    loadConversations();
  }

  // ---------- typing ----------
  // Google pushes typing start/stop (SSE "typing" events); the server also
  // keeps them for 15s (GET /api/app/typing) so a reload or a missed stop
  // event can't leave dots stuck.
  var TYPING_TTL = 15000, typingTimer = null;
  function setTyping(convID, key, name, on, ttl) {
    if (!convID) return;
    var m = state.typing[convID] || (state.typing[convID] = {});
    key = key || name || "?";
    if (on) m[key] = { name: name || "", expires: Date.now() + (ttl || TYPING_TTL) };
    else delete m[key];
    typingChanged();
  }
  function pruneTyping() {
    var now = Date.now(), changed = false;
    Object.keys(state.typing).forEach(function (cid) {
      var m = state.typing[cid];
      Object.keys(m).forEach(function (k) { if (m[k].expires <= now) { delete m[k]; changed = true; } });
      if (!Object.keys(m).length) delete state.typing[cid];
    });
    return changed;
  }
  function typingNames(convID) {
    var m = state.typing[convID];
    if (!m) return [];
    var now = Date.now();
    return Object.keys(m).filter(function (k) { return m[k].expires > now; }).map(function (k) { return m[k].name; });
  }
  function typingLabel(names, group) {
    var named = names.filter(Boolean);
    if (!group || !named.length) return "typing";
    return (named.length > 1 ? named[0] + " +" + (named.length - 1) : named[0]) + " is typing";
  }
  // "Name is typing" followed by three animated dots sized to the text.
  function typingText(cls, label) {
    var span = el("span", cls);
    span.appendChild(document.createTextNode(label));
    var dots = el("span", "typing-dots typing-dots-inline");
    dots.setAttribute("aria-hidden", "true");
    dots.appendChild(el("i")); dots.appendChild(el("i")); dots.appendChild(el("i"));
    span.appendChild(dots);
    return span;
  }
  function typingChanged() {
    pruneTyping();
    renderConversations();
    renderTypingRow(true);
    clearInterval(typingTimer); typingTimer = null;
    if (Object.keys(state.typing).length) {
      typingTimer = setInterval(function () { if (pruneTyping()) typingChanged(); }, 1000);
    }
  }
  function renderTypingRow(scrollIfNear) {
    var box = $("messages");
    var old = box.querySelector(".typing-row");
    var names = state.current && !state.folder ? typingNames(state.current) : [];
    if (!names.length) { if (old) old.remove(); return; }
    var nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 200;
    var c = state.convs.find(function (x) { return x.ConversationID === state.current; });
    var row = old || el("div", "msg-row them typing-row");
    row.textContent = "";
    row.setAttribute("aria-label", typingLabel(names, c && c.IsGroup));
    var bubble = el("div", "bubble");
    if (c && c.IsGroup && names.filter(Boolean).length) {
      // Group: who's typing, with the dots inline at text size.
      row.classList.add("typing-named");
      bubble.appendChild(typingText("typing-name", typingLabel(names, true)));
    } else {
      row.classList.remove("typing-named");
      var dots = el("span", "typing-dots");
      dots.appendChild(el("i")); dots.appendChild(el("i")); dots.appendChild(el("i"));
      bubble.appendChild(dots);
    }
    row.appendChild(bubble);
    box.appendChild(row); // keep it last
    if (scrollIfNear && nearBottom) box.scrollTop = box.scrollHeight;
  }
  function fetchTyping() {
    return api("/api/app/typing").then(function (res) {
      var next = {}, now = Date.now();
      ((res && res.typing) || []).forEach(function (t) {
        var m = next[t.conversation_id] || (next[t.conversation_id] = {});
        m[t.sender_number || t.sender_name || "?"] = { name: t.sender_name || "", expires: now + (t.expires_in_ms || 0) };
      });
      state.typing = next;
      typingChanged();
    }).catch(function () {});
  }

  // ---------- start chat ----------
  // Full panel like Messages for web: To: field (live filter; phone numbers
  // and emails get "Send to …"), group mode with chips, Top contacts (most
  // recent 1:1 chats, up to 8) and All contacts A to Z. Picking someone opens
  // the existing conversation, or gets/creates one through Google
  // (POST /api/app/conversations/start). Nothing is sent from here.
  var nc = { group: false, chips: [], data: null, loading: false, error: "", busy: false };
  function digitsOf(v) { return String(v || "").replace(/\D+/g, ""); }
  function looksLikeAddress(q) {
    q = String(q || "").trim();
    if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(q)) return true;
    return /^\+?[\d\s().-]+$/.test(q) && digitsOf(q).length >= 3;
  }
  function contactKey(c) { var d = digitsOf(c.number); return d.length >= 10 ? d.slice(-10) : (d || String(c.number || "").toLowerCase()); }
  function openNewChat() {
    if (state.folder) exitFolder();
    closeMsgMenu();
    nc.group = false; nc.chips = []; nc.error = ""; nc.busy = false;
    $("newChatTo").value = "";
    $("newChatView").hidden = false;
    renderNewChat();
    $("newChatTo").focus();
    nc.loading = !nc.data;
    api("/api/app/contacts").then(function (d) {
      nc.data = d || { top: [], all: [] }; nc.loading = false; nc.error = "";
      renderNewChat();
    }).catch(function (e) {
      nc.loading = false; if (!nc.data) nc.error = e.message;
      renderNewChat();
    });
  }
  function closeNewChat() { $("newChatView").hidden = true; }
  function contactAvatar(c, cls) {
    var name = c.name || c.number || "?";
    var bg = "hsl(" + hue(c.number || name) + " 45% 38%)";
    var av = el("div", cls);
    var r = refsFor([{ id: c.participant_id, contact_id: c.contact_id, number: c.number }]);
    r.key = avatarLookupKey(r);
    var lookups = r.key ? [r] : [];
    paintAvatar(av, lookups, initials(name), bg);
    lookups.forEach(function (x) { avatarRequest(x, function () { if (av.isConnected) paintAvatar(av, lookups, initials(name), bg); }); });
    return av;
  }
  function isChip(c) { var k = contactKey(c); return nc.chips.some(function (x) { return contactKey(x) === k; }); }
  function contactRow(c) {
    var b = el("button", "contact-row" + (nc.group && isChip(c) ? " selected" : ""));
    b.type = "button";
    b.appendChild(contactAvatar(c, "avatar"));
    var mid = el("div", "contact-mid");
    mid.appendChild(el("div", "contact-name", c.name || c.number));
    if (c.name && c.name !== c.number) mid.appendChild(el("div", "contact-num", c.number));
    b.appendChild(mid);
    if (nc.group) { var ck = el("span", "contact-check"); ck.appendChild(icon("check")); b.appendChild(ck); }
    b.addEventListener("click", function () { pickContact(c); });
    return b;
  }
  function renderNewChat() {
    $("newChatTitle").textContent = nc.group ? "New group conversation" : "New conversation";
    var g = $("newChatGroup");
    g.textContent = "";
    g.appendChild(icon(nc.group ? "close" : "group"));
    g.appendChild(el("span", null, nc.group ? "Cancel group" : "Start group conversation"));
    g.classList.toggle("on", nc.group);
    var next = $("newChatNext");
    next.hidden = !nc.group;
    next.disabled = nc.busy || nc.chips.length < 2;
    next.textContent = nc.busy ? "Opening…" : nc.chips.length >= 2 ? "Next (" + nc.chips.length + ")" : "Next";
    var chips = $("newChatChips");
    chips.textContent = "";
    if (nc.group) nc.chips.forEach(function (c) {
      var chip = el("button", "newchat-chip");
      chip.type = "button";
      chip.setAttribute("aria-label", "Remove " + (c.name || c.number));
      chip.appendChild(document.createTextNode(c.name || c.number));
      var cx = el("b"); cx.appendChild(icon("close")); chip.appendChild(cx);
      chip.addEventListener("click", function () { toggleChip(c); });
      chips.appendChild(chip);
    });
    var list = $("newChatList");
    list.textContent = "";
    var q = $("newChatTo").value.trim();
    var data = nc.data || { top: [], all: [] };
    if (q) {
      if (looksLikeAddress(q)) {
        var sendRow = el("button", "contact-row contact-send");
        sendRow.type = "button";
        var sav = el("div", "avatar", q.indexOf("@") >= 0 ? null : "#");
        if (q.indexOf("@") >= 0) sav.appendChild(icon("mail", "avatar-ico"));
        sendRow.appendChild(sav);
        var mid = el("div", "contact-mid");
        mid.appendChild(el("div", "contact-name", (nc.group ? "Add " : "Send to ") + q));
        sendRow.appendChild(mid);
        sendRow.addEventListener("click", function () { pickTyped(q); });
        list.appendChild(sendRow);
      }
      var ql = q.toLowerCase(), qd = digitsOf(q);
      var matches = (data.all || []).filter(function (c) {
        return String(c.name || "").toLowerCase().indexOf(ql) >= 0 || (qd.length >= 3 && digitsOf(c.number).indexOf(qd) >= 0);
      }).slice(0, 60);
      matches.forEach(function (c) { list.appendChild(contactRow(c)); });
      if (!matches.length && !looksLikeAddress(q)) list.appendChild(el("div", "newchat-empty", nc.loading ? "Loading contacts…" : "No matching contacts"));
      return;
    }
    if (nc.loading && !nc.data) { list.appendChild(el("div", "newchat-empty", "Loading contacts…")); return; }
    if (nc.error && !nc.data) { list.appendChild(el("div", "newchat-empty", "Couldn't load contacts: " + nc.error)); return; }
    if ((data.top || []).length) {
      list.appendChild(el("div", "newchat-section", "Top contacts"));
      var grid = el("div", "top-contacts");
      data.top.forEach(function (c) {
        var b = el("button", "top-contact" + (nc.group && isChip(c) ? " selected" : ""));
        b.type = "button";
        b.appendChild(contactAvatar(c, "avatar"));
        b.appendChild(el("div", "top-contact-name", c.name || c.number));
        b.addEventListener("click", function () { pickContact(c); });
        grid.appendChild(b);
      });
      list.appendChild(grid);
    }
    list.appendChild(el("div", "newchat-section", "All contacts"));
    if (!(data.all || []).length) { list.appendChild(el("div", "newchat-empty", "No contacts yet. Type a phone number above.")); return; }
    var lastLetter = null;
    data.all.forEach(function (c) {
      var ch = String(c.name || c.number || "#").trim().charAt(0).toUpperCase();
      var letter = /\p{L}/u.test(ch) ? ch : "#";
      if (letter !== lastLetter) { list.appendChild(el("div", "newchat-letter", letter)); lastLetter = letter; }
      list.appendChild(contactRow(c));
    });
  }
  function toggleChip(c) {
    var k = contactKey(c);
    var i = nc.chips.findIndex(function (x) { return contactKey(x) === k; });
    if (i >= 0) nc.chips.splice(i, 1); else nc.chips.push(c);
    renderNewChat();
  }
  function pickContact(c) {
    if (nc.busy) return;
    if (nc.group) {
      toggleChip(c);
      if ($("newChatTo").value) { $("newChatTo").value = ""; renderNewChat(); }
      $("newChatTo").focus();
      return;
    }
    if (c.conversation_id) { finishNewChat(c.conversation_id, c.name); return; }
    startChat([c.number], c.name);
  }
  function pickTyped(q) {
    if (nc.group) {
      if (!isChip({ number: q })) nc.chips.push({ name: q, number: q });
      $("newChatTo").value = "";
      renderNewChat();
      $("newChatTo").focus();
      return;
    }
    var k = contactKey({ number: q });
    var known = ((nc.data && nc.data.all) || []).find(function (c) { return contactKey(c) === k; });
    if (known) { pickContact(known); return; }
    startChat([q], q);
  }
  function startChat(numbers, name) {
    if (nc.busy) return;
    nc.busy = true; renderNewChat();
    toast("Opening conversation…");
    postJSON("/api/app/conversations/start", { numbers: numbers }).then(function (res) {
      nc.busy = false;
      finishNewChat(res.conversation_id, res.name || name);
    }).catch(function (e) {
      nc.busy = false; renderNewChat();
      if (e.message !== "login required") toast("Couldn't open the conversation: " + e.message, "error");
    });
  }
  function finishNewChat(id, name) {
    closeNewChat();
    $("toast").hidden = true;
    loadConversations().then(function () {
      openConversation(id, name);
      $("input").focus();
    });
  }

  // ---------- routing ----------
  function route() {
    $("toast").hidden = true;
    showPair(location.hash === "#pair");
  }
  function openPair() { $("toast").hidden = true; showPair(true); }
  function closePair() {
    showPair(false);
    if (location.hash) history.replaceState(history.state, "", location.pathname + location.search);
  }

  // ---------- back button / gesture ----------
  // One "guard" history entry sits on top of the page's own entry whenever
  // anything back-closable is open (conversation, folder, settings, a sheet,
  // a menu, the photo viewer...). Back pops the guard; popstate then closes
  // the topmost layer and re-adds the guard if more are still open. With
  // nothing open there's no guard, so Back leaves the page / Android app as
  // usual. Never more than one entry, so no duplicates pile up. Closing a
  // layer with an on-screen button removes the guard with history.back().
  var nav = { guard: false, pendingBack: false, timer: 0 };
  function layerOpen() {
    return !$("imageViewer").hidden || !$("trashView").hidden || !$("detailsView").hidden ||
      emojiOpen() || !$("confirmView").hidden || !$("signOutView").hidden || !$("settingsView").hidden ||
      !!state.menuFor || !!state.convMenuFor || !$("appMenu").hidden || sheetOpen() || !$("fwdView").hidden || !$("infoView").hidden || !!state.selecting || !$("newChatView").hidden || !!ct || !$("pairView").hidden ||
      !!state.rec || !!state.current || !!state.folder;
  }
  // Close the topmost layer; false when nothing was open.
  function closeTopLayer() {
    if (closeImageViewer()) return true;
    if (!$("trashView").hidden) { if (!$("trashOk").disabled) hideTrash(); return true; }
    if (!$("detailsView").hidden) { closeDetails(); return true; }
    if (emojiOpen()) { showEmoji(false); return true; }
    if (!$("confirmView").hidden) { if (!$("confirmOk").disabled) hideConfirm(); return true; }
    if (!$("signOutView").hidden) { $("signOutView").hidden = true; return true; }
    if (!$("settingsView").hidden) { showSettings(false); return true; }
    if (!$("infoView").hidden) { $("infoView").hidden = true; return true; }
    if (!$("fwdView").hidden) { closeForward(); return true; }
    if (closeMsgSheet()) return true;
    if (state.menuFor) { closeMsgMenu(); return true; }
    if (closeAppMenu()) return true;
    if (state.convMenuFor) { closeConvMenu(); return true; }
    if (!$("newChatView").hidden) { closeNewChat(); return true; }
    if (ct) { ctBack(); return true; }
    if (!$("pairView").hidden) { closePair(); return true; }
    if (state.rec) { cancelRecording(); return true; }
    if (stopSelect()) return true;
    if (state.current) { closeThread(); renderConversations(); return true; }
    if (state.folder) { exitFolder(); return true; }
    return false;
  }
  function navSync() {
    nav.timer = 0;
    if (nav.pendingBack) return; // finishes in onPopState
    var open = layerOpen();
    try {
      if (open && !nav.guard) { history.pushState({ me: "guard" }, ""); nav.guard = true; }
      else if (!open && nav.guard) { nav.pendingBack = true; history.back(); }
    } catch (e) { /* history API unavailable: buttons still work */ }
  }
  function scheduleNavSync() { if (!nav.timer) nav.timer = setTimeout(navSync, 0); }
  function onPopState(e) {
    var onGuard = !!(e.state && e.state.me === "guard");
    // A typed #pair hash is handled by hashchange.
    if (!onGuard && !(e.state && e.state.me) && location.hash === "#pair") return;
    if (nav.pendingBack) { nav.pendingBack = false; nav.guard = onGuard; navSync(); return; }
    if (onGuard) { nav.guard = true; navSync(); return; } // Forward onto a stale guard
    nav.guard = false;
    closeTopLayer();
    navSync();
  }
  function initNav() {
    try {
      if (history.state && history.state.me === "guard") nav.guard = true; // reloaded on the guard
      else history.replaceState({ me: "base" }, "");
    } catch (e) { return; }
    window.addEventListener("popstate", onPopState);
    new MutationObserver(scheduleNavSync).observe(document.body, { attributes: true, subtree: true, attributeFilter: ["hidden", "class"] });
    scheduleNavSync();
  }

  // ---------- init ----------
  function init() {
    window.TMTextbox($("input"), { onImage: addPendingFromURL });
    initPaste();
    initQuickReplies();
    initReactions();
    initSearch();
    initHealth();
    initMsgSheet();
    initSwipe();
    initDock();
    hydrateIcons();
    $("conn").addEventListener("click", function () { toast(connStatusText()); });
    $("compose").addEventListener("submit", sendMessage);
    $("input").addEventListener("input", autosize);
    $("input").addEventListener("blur", rememberSelection);
    $("input").addEventListener("keydown", function (e) {
      if (e.key === "Enter" && !e.shiftKey && !e.isComposing) { e.preventDefault(); sendMessage(); }
    });
    $("micBtn").addEventListener("click", onMicTap);
    $("attachBtn").addEventListener("click", onAttachTap);
    $("fileInput").addEventListener("change", onFilePicked);
    initPlusSheet();
    $("settingsBtn").addEventListener("click", function () { showSettings(true); });
    $("settingsClose").addEventListener("click", function () { showSettings(false); });
    $("settingsView").addEventListener("click", function (e) { if (e.target === $("settingsView")) showSettings(false); });
    $("setCarMode").addEventListener("change", function () { setCarMode(this.checked); });
    $("setAttach").addEventListener("click", function () {
      var st = loadSettings();
      st.showAttach = !st.showAttach;
      saveSettings(st);
      applySettings();
    });
    $("setEmoji").addEventListener("click", function () {
      var st = loadSettings();
      st.showEmoji = st.showEmoji === false;
      saveSettings(st);
      applySettings();
    });
    $("setVoice").addEventListener("click", function () {
      var st = loadSettings();
      st.showVoice = st.showVoice === false;
      saveSettings(st);
      applySettings();
    });
    $("muteBtn").addEventListener("click", toggleMute);
    $("setSound").addEventListener("click", function () {
      var st = loadSettings();
      st.sound = st.sound === false;
      saveSettings(st);
      applySettings();
      if (st.sound) chime.play(true);
    });
    $("testSound").addEventListener("click", function () { chime.play(true); });
    chime.initUnlock();
    initEmoji();
    initComposerFloat();
    $("setMicRecheck").addEventListener("click", function () { setMicRecheck(!micRecheckOn()); });
    $("setLiveTyping").addEventListener("click", function () {
      var st = loadSettings();
      st.liveTyping = !liveTypingOn();
      saveSettings(st);
      applySettings();
    });
    $("micCheckBtn").addEventListener("click", micCheckNow);
    $("zoomOut").addEventListener("click", function () { stepZoom(-1); });
    $("zoomIn").addEventListener("click", function () { stepZoom(1); });
    applySettings();
    // Sign out lives in Settings; it asks first, then POSTs /logout.
    $("refreshAllBtn").addEventListener("click", refreshEverything);
    $("grokEnable").addEventListener("click", function () {
      if (!grokState || !grokState.key_configured) return;
      saveGrok(!grokState.enabled, grokState.trigger || "me", !!grokState.groq_enabled);
    });
    $("groqEnable").addEventListener("click", function () {
      if (!grokState || !grokState.groq_key_configured) return;
      saveGrok(!!grokState.enabled, grokState.trigger || "me", !grokState.groq_enabled);
    });
    Array.prototype.forEach.call(document.querySelectorAll('input[name="grokTrigger"]'), function (r) {
      r.addEventListener("change", function () { if (r.checked && grokState) saveGrok(!!grokState.enabled, r.value, !!grokState.groq_enabled); });
    });
    $("newMsgPill").addEventListener("click", function () {
      var box = $("messages");
      try { box.scrollTo({ top: box.scrollHeight, behavior: "smooth" }); } catch (e) { box.scrollTop = box.scrollHeight; }
      showNewPill(0);
    });
    $("messages").addEventListener("scroll", function () {
      var box = $("messages");
      if (box.scrollHeight - box.scrollTop - box.clientHeight < 120) {
        if (scrollState.pill) showNewPill(0);
        var t = state.lastMsgs && realTail(state.lastMsgs);
        if (t && state.current && !state.folder && scrollState.conv === state.current && !scrollState.pendingOpen) markSeen(state.current, t.TimestampMS);
      }
    }, { passive: true });
    $("signOutBtn").addEventListener("click", function () { showSettings(false); $("signOutView").hidden = false; });
    $("signOutCancel").addEventListener("click", function () { $("signOutView").hidden = true; });
    $("signOutView").addEventListener("click", function (e) { if (e.target === $("signOutView")) $("signOutView").hidden = true; });
    $("recEdit").addEventListener("click", function () { recFinishAs("edit"); });
    $("recSend").addEventListener("click", function () { recFinishAs("send"); });
    $("recCancel").addEventListener("click", cancelRecording);
    $("convMenuTheme").addEventListener("click", function () {
      closeConvMenu();
      openChatTheme();
    });
    $("convMenuDetails").addEventListener("click", function () {
      var f = state.convMenuFor;
      closeConvMenu();
      if (f) openDetails(f.conv);
    });
    $("convMenuArchive").addEventListener("click", function () {
      var f = state.convMenuFor, archived = this.dataset.archived === "1";
      closeConvMenu();
      if (f) setConvArchived(f.conv, !archived);
    });
    $("convMenuTrash").addEventListener("click", function () {
      var f = state.convMenuFor;
      closeConvMenu();
      if (f) askTrash(f.conv);
    });
    $("detailsClose").addEventListener("click", closeDetails);
    $("detailsView").addEventListener("click", function (e) { if (e.target === $("detailsView")) closeDetails(); });
    $("trashCancel").addEventListener("click", hideTrash);
    $("trashOk").addEventListener("click", confirmTrash);
    $("trashView").addEventListener("click", function (e) { if (e.target === $("trashView") && !$("trashOk").disabled) hideTrash(); });
    $("ctBack").addEventListener("click", ctBack);
    $("ctApply").addEventListener("click", ctPrimary);
    $("ctReset").addEventListener("click", ctReset);
    $("ctRemovePhoto").addEventListener("click", ctRemovePhoto);
    $("ctChoose").addEventListener("click", function () { $("ctFile").click(); });
    $("ctFile").addEventListener("change", ctFilePicked);
    $("replyCancel").addEventListener("click", function () { clearReply(); $("input").focus(); });
    $("backBtn").addEventListener("click", function () { closeThread(); renderConversations(); });
    // message menu + delete confirm
    $("msgMenuDelete").addEventListener("click", function () {
      var f = state.menuFor;
      closeMsgMenu();
      if (f) showConfirm(f.msg);
    });
    $("confirmCancel").addEventListener("click", hideConfirm);
    $("confirmOk").addEventListener("click", confirmDelete);
    $("confirmView").addEventListener("click", function (e) { if (e.target === $("confirmView") && !$("confirmOk").disabled) hideConfirm(); });
    // conversation menu: ⋮ in the thread header, or long-press a row
    $("convMenuBtn").addEventListener("click", function () {
      if (state.convMenuFor && state.convMenuFor.btn === this) { closeConvMenu(); return; }
      var id = state.current;
      var c = state.convs.find(function (x) { return x.ConversationID === id; });
      openConvMenu(c, this.getBoundingClientRect(), this);
    });
    $("convMenuMute").addEventListener("click", function () { closeConvMenu(); toggleMute(); });
    $("convMenuSettings").addEventListener("click", function () { closeConvMenu(); showSettings(true); });
    // Non-car conversation list: Settings sits under the top bar's ⋮.
    // The app menu (Settings) opens from the profile photo (non-car) or, in
    // Car Mode with no conversation open, the ⋮ in the empty conversation
    // pane (where the conversation header's ⋮ sits once one is open).
    ["appMenuBtn", "profileBtn", "emptyMenuBtn"].forEach(function (id) {
      $(id).addEventListener("click", function () {
        var m = $("appMenu");
        if (!m.hidden) { closeAppMenu(); return; }
        closeConvMenu(); closeMsgMenu();
        m.hidden = false; this.setAttribute("aria-expanded", "true");
        placePopover(m, this.getBoundingClientRect());
      });
    });
    // Non-car list: the search box hides behind the magnifier.
    $("searchBtn").addEventListener("click", function () {
      if (document.body.classList.contains("search-open") && !$("convSearch").value) { document.body.classList.remove("search-open"); return; }
      document.body.classList.add("search-open");
      $("convSearch").focus();
    });
    $("convSearch").addEventListener("blur", function () {
      setTimeout(function () { if (!$("convSearch").value && document.activeElement !== $("convSearch")) document.body.classList.remove("search-open"); }, 150);
    });
    // Start chat FAB: shrinks to its icon while scrolling down, grows back
    // on scrolling up or when scrolling stops; a scroll-to-top arrow shows
    // once the list is scrolled.
    (function () {
      var list = document.querySelector(".convlist"), fab = $("fabStart"), top = $("scrollTopBtn"), lastY = 0, idle = null;
      list.addEventListener("scroll", function () {
        var y = list.scrollTop;
        if (y > lastY + 3) fab.classList.add("shrunk"); else if (y < lastY - 3) fab.classList.remove("shrunk");
        lastY = y;
        clearTimeout(idle);
        idle = setTimeout(function () { fab.classList.remove("shrunk"); }, 700);
        top.hidden = y < 320;
      }, { passive: true });
      top.addEventListener("click", function () { list.scrollTo({ top: 0, behavior: "smooth" }); });
      fab.addEventListener("click", function () { openNewChat(); });
    })();
    $("appMenuSettings").addEventListener("click", function () { closeAppMenu(); showSettings(true); });
    document.addEventListener("pointerdown", function (e) {
      if ($("appMenu").hidden || $("appMenu").contains(e.target) || $("appMenuBtn").contains(e.target) || $("profileBtn").contains(e.target) || $("emptyMenuBtn").contains(e.target)) return;
      closeAppMenu();
    }, true);
    $("convMenuPin").addEventListener("click", function () {
      var f = state.convMenuFor;
      closeConvMenu();
      if (f && !this.disabled) setConvPinned(f.conv, !convPins(f.conv).local);
    });
    document.addEventListener("pointerdown", function (e) {
      if (!state.convMenuFor) return;
      if ($("convMenu").contains(e.target) || (state.convMenuFor.btn && state.convMenuFor.btn.contains(e.target))) return;
      closeConvMenu();
    }, true);
    $("convItems").parentNode.addEventListener("scroll", function () { if (state.convMenuFor) closeConvMenu(); }, { passive: true });
    document.addEventListener("pointerdown", function (e) {
      if (!state.menuFor) return;
      if ($("msgMenu").contains(e.target) || (state.menuFor.btn && state.menuFor.btn.contains(e.target))) return;
      closeMsgMenu();
    }, true);
    $("messages").addEventListener("scroll", function () { if (state.menuFor) closeMsgMenu(); }, { passive: true });
    document.addEventListener("keydown", function (e) {
      if (e.key !== "Escape") return;
      if (closeImageViewer()) { /* photo viewer first: it's on top */ }
      else if (!$("trashView").hidden) { if (!$("trashOk").disabled) hideTrash(); }
      else if (!$("detailsView").hidden) closeDetails();
      else if (emojiOpen()) showEmoji(false);
      else if (!$("confirmView").hidden) { if (!$("confirmOk").disabled) hideConfirm(); }
      else if (!$("signOutView").hidden) $("signOutView").hidden = true;
      else if (!$("settingsView").hidden) showSettings(false);
      else if (state.menuFor) closeMsgMenu();
      else if (state.convMenuFor) closeConvMenu();
      else if (!$("newChatView").hidden) closeNewChat();
      else if (ct) ctBack();
      else return;
      e.preventDefault();
    });
    // folders (Settings -> Folders)
    Array.prototype.forEach.call(document.querySelectorAll(".folder-row"), function (b) {
      b.addEventListener("click", function () { showSettings(false); enterFolder(b.dataset.folder); });
    });
    $("folderBack").addEventListener("click", exitFolder);
    $("cookiesHint").addEventListener("click", function () {
      $("debugFold").open = true;
      $("cookiesBtn").scrollIntoView({ block: "center" });
    });
    // full-size photo viewer: any tap closes it
    $("imageViewer").addEventListener("click", function () { closeImageViewer(); });
    // start chat
    $("newChatBtn").addEventListener("click", openNewChat);
    $("newChatBack").addEventListener("click", closeNewChat);
    $("newChatGroup").addEventListener("click", function () {
      nc.group = !nc.group;
      if (!nc.group) nc.chips = [];
      renderNewChat();
      $("newChatTo").focus();
    });
    $("newChatNext").addEventListener("click", function () {
      if (nc.chips.length >= 2) startChat(nc.chips.map(function (c) { return c.number; }), nc.chips.map(function (c) { return c.name || c.number; }).join(", "));
    });
    $("newChatTo").addEventListener("input", renderNewChat);
    $("newChatTo").addEventListener("keydown", function (e) {
      if (e.key === "Backspace" && !e.target.value && nc.group && nc.chips.length) { nc.chips.pop(); renderNewChat(); return; }
      if (e.key !== "Enter" || e.isComposing) return;
      e.preventDefault();
      var q = e.target.value.trim();
      if (!q) return;
      if (looksLikeAddress(q)) { pickTyped(q); return; }
      var first = $("newChatList").querySelector(".contact-row");
      if (first) first.click();
    });
    $("pairBtn").addEventListener("click", openPair);
    $("pairClose").addEventListener("click", closePair);
    $("pairStart").addEventListener("click", function () {
      postJSON("/api/app/pairing/start").then(renderPairing).catch(function (e) { toast(e.message, "error"); refreshPairing(); });
    });
    $("pairCancel").addEventListener("click", function () { postJSON("/api/app/pairing/cancel").then(renderPairing); });
    var img = $("pairEmojiImg");
    img.addEventListener("load", function () { $("pairEmojiWrap").classList.add("img-ok"); });
    img.addEventListener("error", function () { $("pairEmojiWrap").classList.remove("img-ok"); });
    window.addEventListener("hashchange", route);
    // Tapping a notification while the page is open (pwa.js relays it).
    window.addEventListener("tm-open-conversation", function (e) {
      var id = e.detail && e.detail.conv;
      if (!id) return;
      showSettings(false);
      if (state.folder) exitFolder();
      loadConversations().then(function () {
        openConversation(id);
        if (e.detail.reply) focusComposer();
      });
    });
    document.addEventListener("visibilitychange", function () {
      if (document.hidden) return;
      syncReadSoon();
      refreshProfilePhoto(false);
      checkAvatarVersion(false);
      checkVersion();
      loadThemes(); onPageResume();
      loadConversations();
      if (state.current) loadMessages(false, true);
    });
    window.addEventListener("focus", onPageResume);
    window.addEventListener("focus", syncReadSoon);
    window.addEventListener("focus", function () { checkVersion(); });
    setTimeout(function () { checkVersion(true); }, 4000);
    window.addEventListener("pageshow", onPageResume);

    checkMic(true);
    refreshOnLoad();
    setInterval(function () { checkAvatarVersion(false); }, 10 * 60 * 1000);
    loadConversations().then(function () {
      var m = /[?&]c=([^&]+)/.exec(location.search);
      if (m) {
        var reply = /[?&]reply=1/.test(location.search);
        // Drop ?c= so a reload or Back doesn't reopen it; Back returns to the list.
        try { history.replaceState(history.state, "", location.pathname + location.hash); } catch (e) {}
        openConversation(decodeURIComponent(m[1]));
        if (reply) focusComposer();
      }
    });
    loadThemes();
    fetchTyping();
    refreshPairing();
    setInterval(refreshPairing, 15000);
    setInterval(pollIfStreamDead, POLL_MS);
    connectEvents();
    route();
    initNav();
    autosize();
  }
  init();
})();
