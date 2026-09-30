/* Tesla Messages: car-friendly client for the OpenMessage API.
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
  var toastTimer = null;
  function toast(msg, kind) {
    var t = $("toast");
    t.textContent = msg;
    t.className = "toast" + (kind ? " toast-" + kind : "");
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.hidden = true; }, kind === "error" ? 6000 : 3500);
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
    if (d.toDateString() === now.toDateString()) return d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
    var y = new Date(now); y.setDate(now.getDate() - 1);
    if (d.toDateString() === y.toDateString()) return "Yesterday";
    if (now - d < 6 * 864e5) return d.toLocaleDateString([], { weekday: "short" });
    return d.toLocaleDateString([], { month: "short", day: "numeric" });
  }
  function dayLabel(ms) {
    var d = new Date(ms), now = new Date(), y = new Date(now);
    y.setDate(now.getDate() - 1);
    if (d.toDateString() === now.toDateString()) return "Today";
    if (d.toDateString() === y.toDateString()) return "Yesterday";
    return d.toLocaleDateString([], { weekday: "long", month: "short", day: "numeric" });
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
    var others = all.filter(function (p) { return p && !isMe(p); });
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
        var q = "source=" + encodeURIComponent(r.source);
        if (r.participantIDs[0]) q += "&participant_id=" + encodeURIComponent(r.participantIDs[0]);
        if (r.contactIDs[0]) q += "&contact_id=" + encodeURIComponent(r.contactIDs[0]);
        if (r.numbers[0]) q += "&phone=" + encodeURIComponent(r.numbers[0]);
        // Same auth as api(): the tesla session cookie, same-origin. A 401 is
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
    if (!photos.length) { av.textContent = fallbackText; return; }
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
    var fallback = c.IsGroup ? "👥" : initials(convName(c));
    var bg = "hsl(" + hue(c.ConversationID) + " 45% 38%)";
    var av = el("div", cls);
    var lookups = avatarLookups(c);
    paintAvatar(av, lookups, fallback, bg);
    var update = function () { if (av.isConnected) paintAvatar(av, lookups, fallback, bg); };
    lookups.forEach(function (r) { avatarRequest(r, update); });
    return av;
  }

  // ---------- conversations ----------
  function loadConversations(force) {
    // Folder lists are read live from Google (read-only); refresh them only
    // on entry, not on every live-update ping.
    if (state.folder) return force ? loadFolder() : Promise.resolve();
    return api("/api/conversations?limit=100").then(function (list) {
      if (state.folder) return;
      state.convs = (list || []).filter(function (c) { return c.tab !== "archive"; });
      renderConversations();
    }).catch(function (e) { if (e.message !== "login required") toast("Couldn't load conversations: " + e.message, "error"); });
  }
  function renderConversations() {
    var box = $("convItems");
    box.textContent = "";
    if (!state.convs.length) {
      var f = state.folder;
      box.appendChild(el("div", "conv-empty" + (f && f.error ? " conv-error" : ""),
        !f ? "No conversations yet" : f.loading ? "Loading " + f.label + "…" : f.error ? "Couldn't load " + f.label + ": " + f.error : "No " + f.label.toLowerCase() + " conversations"));
      return;
    }
    state.convs.forEach(function (c) {
      var b = el("button", "conv" + (c.ConversationID === state.current ? " active" : "") + (c.UnreadCount > 0 ? " unread" : ""));
      b.type = "button";
      b.dataset.id = c.ConversationID;
      var av = buildAvatar(c, "avatar");
      var mid = el("div", "conv-mid");
      var nameRow = el("div", "conv-name", convName(c));
      if (PLATFORM[c.source_platform]) nameRow.appendChild(el("span", "tag", PLATFORM[c.source_platform]));
      mid.appendChild(nameRow);
      var typers = typingNames(c.ConversationID);
      if (typers.length) mid.appendChild(el("div", "conv-preview typing", typingLabel(typers, c.IsGroup)));
      else mid.appendChild(el("div", "conv-preview", c.last_message_preview || ""));
      var right = el("div", "conv-right");
      right.appendChild(el("div", "conv-time", fmtTime(c.LastMessageTS)));
      if (c.UnreadCount > 0) right.appendChild(el("div", "badge", String(c.UnreadCount)));
      b.appendChild(av); b.appendChild(mid); b.appendChild(right);
      b.addEventListener("click", function () { openConversation(c.ConversationID); });
      box.appendChild(b);
    });
  }

  // ---------- per-conversation theme ----------
  // Client-side only: the choice lives in localStorage ("tm.theme.<conv id>")
  // and is applied as CSS variables on #threadView. Every sent-bubble/accent
  // color keeps white text at >= 4.5:1 contrast; received bubbles stay dark.
  var THEME_KEY = "tm.theme.";
  var THEME_PRESETS = [
    { id: "default", name: "Default" },
    { id: "ocean", name: "Ocean", me: "#1c6e8c", accent: "#0e7490", them: "#1e3a4c" },
    { id: "sunset", name: "Sunset", me: "#c2410c", accent: "#be185d", them: "#3b2a1f" },
    { id: "forest", name: "Forest", me: "#237a47", accent: "#15803d", them: "#1f3326" },
    { id: "mono", name: "Mono", me: "#4b5563", accent: "#6b7280", them: "#2a2d33" },
  ];
  var THEME_SWATCHES = ["#1d4ed8", "#0f766e", "#15803d", "#a16207", "#c2410c", "#b91c1c", "#be185d", "#7c3aed", "#475569"];
  var THEME_DEFAULT_ME = "#2f6fe4", THEME_DEFAULT_THEM = "#262b33";
  function darken(hex, f) {
    var n = parseInt(hex.slice(1), 16);
    var r = Math.round(((n >> 16) & 255) * f), g = Math.round(((n >> 8) & 255) * f), b = Math.round((n & 255) * f);
    return "#" + ((1 << 24) | (r << 16) | (g << 8) | b).toString(16).slice(1);
  }
  function validHex(v) { return typeof v === "string" && /^#[0-9a-f]{6}$/i.test(v); }
  function loadTheme(id) {
    try {
      var t = JSON.parse(localStorage.getItem(THEME_KEY + id) || "null");
      return t && typeof t === "object" ? t : null;
    } catch (e) { return null; }
  }
  function saveTheme(id, t) {
    try {
      if (t) localStorage.setItem(THEME_KEY + id, JSON.stringify(t));
      else localStorage.removeItem(THEME_KEY + id);
    } catch (e) {}
  }
  // Resolve a stored choice to { me, accent, them } (null = default look).
  function themeColors(t) {
    if (!t) return null;
    if (t.preset) {
      for (var i = 0; i < THEME_PRESETS.length; i++) {
        var p = THEME_PRESETS[i];
        if (p.id === t.preset) return p.me ? { me: p.me, accent: p.accent, them: p.them } : null;
      }
      return null;
    }
    if (validHex(t.color)) return { me: t.color, accent: t.color, them: null };
    return null;
  }
  function applyTheme(id) {
    var v = $("threadView").style, colors = themeColors(id ? loadTheme(id) : null);
    ["--me", "--accent", "--accent-2", "--them"].forEach(function (k) { v.removeProperty(k); });
    $("threadView").classList.toggle("themed", !!colors);
    if (!colors) return;
    v.setProperty("--me", colors.me);
    v.setProperty("--accent", colors.accent);
    v.setProperty("--accent-2", darken(colors.accent, 0.8));
    if (colors.them) v.setProperty("--them", colors.them);
  }
  function themeChoiceKey(t) { return !t ? "preset:default" : t.preset ? "preset:" + t.preset : "color:" + String(t.color).toLowerCase(); }
  function renderThemeSheet() {
    var id = state.current, cur = themeChoiceKey(loadTheme(id));
    var c = state.convs.find(function (x) { return x.ConversationID === id; });
    $("themeTitle").textContent = "Chat colors" + (c ? " · " + convName(c) : "");
    var presets = $("themePresets");
    presets.textContent = "";
    THEME_PRESETS.forEach(function (p) {
      var b = el("button", "theme-preset");
      b.type = "button";
      var key = "preset:" + p.id;
      if (key === cur) b.classList.add("selected");
      b.setAttribute("aria-pressed", key === cur ? "true" : "false");
      var prev = el("span", "theme-preview");
      var them = el("span", "theme-chip"); them.style.background = p.them || THEME_DEFAULT_THEM;
      var me = el("span", "theme-chip"); me.style.background = p.me || THEME_DEFAULT_ME;
      prev.appendChild(them); prev.appendChild(me);
      b.appendChild(prev);
      b.appendChild(el("span", "theme-name", p.name));
      b.addEventListener("click", function () { chooseTheme(p.id === "default" ? null : { preset: p.id }); });
      presets.appendChild(b);
    });
    var sw = $("themeSwatches");
    sw.textContent = "";
    THEME_SWATCHES.forEach(function (hex) {
      var b = el("button", "theme-swatch");
      b.type = "button";
      b.style.background = hex;
      b.setAttribute("aria-label", "Bubble color " + hex);
      var key = "color:" + hex;
      if (key === cur) { b.classList.add("selected"); b.textContent = "✓"; }
      b.setAttribute("aria-pressed", key === cur ? "true" : "false");
      b.addEventListener("click", function () { chooseTheme({ color: hex }); });
      sw.appendChild(b);
    });
  }
  function chooseTheme(t) {
    if (!state.current) return;
    saveTheme(state.current, t);
    applyTheme(state.current);
    renderThemeSheet();
  }
  function showThemeSheet(open) {
    if (open && !state.current) return;
    $("themeView").hidden = !open;
    if (open) renderThemeSheet();
  }

  // ---------- thread ----------
  function openConversation(id, nameHint) {
    if (state.rec) cancelRecording();
    closeMsgMenu();
    closeImageViewer();
    state.current = id;
    var readOnly = !!state.folder;
    document.body.classList.toggle("readonly-thread", readOnly);
    $("readonlyNote").hidden = !readOnly;
    $("readonlyNote").textContent = readOnly ? "View only · " + state.folder.label + " (Google Messages)" : "";
    document.body.classList.add("has-thread");
    $("threadEmpty").hidden = true;
    $("threadView").hidden = false;
    var c = state.convs.find(function (x) { return x.ConversationID === id; });
    $("threadTitle").textContent = c ? convName(c) : (nameHint || id);
    showThemeSheet(false);
    applyTheme(id);
    state.nodes = {};
    clearReply();
    $("messages").textContent = "";
    renderConversations();
    loadMessages(true);
    fetchTyping();
    // Folders are view only: opening one must not change it (no mark-read).
    if (!readOnly) postJSON("/api/mark-read", { conversation_id: id }).catch(function () {});
  }
  function closeThread() {
    if (state.rec) cancelRecording();
    closeImageViewer();
    showThemeSheet(false);
    closeMsgMenu();
    clearReply();
    state.current = null; document.body.classList.remove("has-thread", "readonly-thread");
    $("threadView").hidden = true; $("threadEmpty").hidden = false;
  }
  function loadMessages(scroll) {
    var id = state.current;
    if (!id) return Promise.resolve();
    var path = "/api/conversations/" + encodeURIComponent(id) + "/messages?limit=80";
    if (state.folder) {
      var fc = state.convs.find(function (x) { return x.ConversationID === id; });
      if (fc && !fc.local) {
        if (!scroll) return Promise.resolve(); // live read: on open only
        path = "/api/tesla/folder/messages?conversation_id=" + encodeURIComponent(id);
      }
    }
    return api(path).then(function (msgs) {
      if (id !== state.current) return;
      renderMessages((msgs || []).slice().reverse(), scroll);
    }).catch(function (e) { if (e.message !== "login required") toast("Couldn't load messages: " + e.message, "error"); });
  }
  // ---------- attachments ----------
  // Media bytes come from OpenMessage's GET /api/media/<message id> (same
  // origin, so the tesla session cookie authenticates it like api()).
  // Videos get an inline player and photos an inline image (tap for full
  // size); other attachments keep the text label.
  var VIDEO_EXT = /\.(mp4|m4v|mov|webm|3gp|3g2|mkv)(\?|#|$)/i;
  var IMAGE_EXT = /\.(jpe?g|png|gif|webp|bmp|avif|heic|heif)(\?|#|$)/i;
  var BARE_FILENAME = /^[^\s\/\\]+\.[a-z0-9]{2,5}$/i;
  var GENERIC_MEDIA_BODY = { "[Photo]": 1, "[Video]": 1, "[Audio]": 1, "[Voice note]": 1, "[Sticker]": 1, "[Attachment]": 1, "[Image]": 1 };
  function mediaURL(m) { return "/api/media/" + encodeURIComponent(m.MessageID); }
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
    var a = el("a", "body media-link", "📎 " + (m.MimeType || fallbackType || "attachment"));
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
    if (site) copy.appendChild(el("div", "link-card-site", site));
    if (p.title) copy.appendChild(el("div", "link-card-title", p.title));
    if (p.description) copy.appendChild(el("div", "link-card-desc", p.description));
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
  // A send is stored by the server under its idempotency key ("tm-...") with
  // status OUTGOING_SENDING until Google's real copy replaces it. If both are
  // ever present (the server cleanup missed it), show only the real one, by
  // the same rule the server uses (db.OutgoingPlaceholderMatches): same
  // conversation, both from me, placeholder still sending, within 3 minutes,
  // and the same trimmed text or media on both sides with a compatible type.
  var PLACEHOLDER_WINDOW_MS = 3 * 60 * 1000;
  function isSendPlaceholder(m) {
    var s = String(m.Status || "").toUpperCase();
    return !!m.IsFromMe && String(m.MessageID || "").indexOf("tm-") === 0 &&
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

  function renderMessages(msgs, forceScroll) {
    msgs = dropEchoedPlaceholders(msgs);
    var box = $("messages");
    var nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 200;
    box.textContent = "";
    var c = state.convs.find(function (x) { return x.ConversationID === state.current; });
    var lastDay = "";
    var byID = {};
    msgs.forEach(function (m) { if (m && m.MessageID) byID[String(m.MessageID)] = m; });
    var lastMine = -1;
    msgs.forEach(function (m, i) { if (m.IsFromMe && !/^TOMBSTONE/i.test(m.Status || "")) lastMine = i; });
    var menuOpenFor = state.menuFor && state.menuFor.msg.MessageID, menuStillThere = false;
    msgs.forEach(function (m, idx) {
      var day = new Date(m.TimestampMS).toDateString();
      if (day !== lastDay) { box.appendChild(el("div", "day-sep", dayLabel(m.TimestampMS))); lastDay = day; }
      var row = el("div", "msg-row " + (m.IsFromMe ? "me" : "them"));
      row.dataset.id = m.MessageID;
      var bubble = el("div", "bubble");
      if (m.ReplyToID) bubble.appendChild(replyQuote(m, byID));
      if (!m.IsFromMe && c && c.IsGroup && m.SenderName) bubble.appendChild(el("div", "sender", m.SenderName));
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
        bubble.appendChild(el("div", "body", m.MediaID ? "📎 " + (m.MimeType || "attachment") : ""));
      }
      var meta = el("div", "meta", new Date(m.TimestampMS).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" }) + (m.IsFromMe && /FAIL/.test(m.Status || "") ? " · Failed" : ""));
      bubble.appendChild(meta);
      row.appendChild(bubble);
      if (!m.IsFromMe) {
        var rb = el("button", "reply-btn");
        rb.type = "button";
        rb.setAttribute("aria-label", "Reply to " + msgAuthor(m));
        rb.title = "Reply";
        rb.appendChild(svgIcon(REPLY_ICON));
        rb.addEventListener("click", function () { setReplyTo(m); });
        row.appendChild(rb);
        var mb = el("button", "msg-menu-btn", "⋮");
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
        row.appendChild(mb);
      }
      box.appendChild(row);
      if (idx === lastMine) {
        var st = sentStatus(m.Status);
        if (st) box.appendChild(el("div", "msg-status" + (st.cls ? " " + st.cls : ""), st.text));
      }
    });
    if (menuOpenFor && !menuStillThere) closeMsgMenu();
    renderTypingRow(false);
    if (forceScroll || nearBottom) box.scrollTop = box.scrollHeight;
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
    $("sendBtn").disabled = !t.value.trim() || state.transcribing || !!state.rec;
  }
  function sendMessage(ev) {
    if (ev) ev.preventDefault();
    var t = $("input"), text = t.value.trim();
    if (!text || !state.current || state.rec || state.transcribing) return;
    var btn = $("sendBtn");
    btn.disabled = true;
    var reply = state.replyTo;
    var req = {
      conversation_id: state.current,
      message: text,
      idempotency_key: "tm-" + Date.now() + "-" + Math.random().toString(36).slice(2, 10),
    };
    if (reply) req.reply_to_id = reply.MessageID;
    postJSON("/api/send", req).then(function () {
      t.value = ""; autosize();
      if (reply && state.replyTo === reply) clearReply();
      loadMessages(true); loadConversations();
    }).catch(function (e) {
      toast("Not sent: " + e.message, "error");
    }).finally(function () { autosize(); });
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
    if (state.sendingMedia || !state.current) return;
    var f = $("fileInput");
    f.value = "";
    f.click();
  }
  function onFilePicked() {
    var f = $("fileInput"), file = f.files && f.files[0];
    f.value = "";
    if (file) sendMedia(file);
  }
  function setAttachBusy(busy) {
    state.sendingMedia = busy;
    var b = $("attachBtn");
    b.classList.toggle("busy", busy);
    b.disabled = busy;
    b.setAttribute("aria-label", busy ? "Sending attachment" : "Attach photo or video");
  }
  function sendMedia(file) {
    if (state.sendingMedia || !state.current) return;
    if (file.size > MAX_MEDIA_BYTES) { toast("That file is too large to send (limit 128 MB).", "error"); return; }
    var convID = state.current;
    var c = state.convs.find(function (x) { return x.ConversationID === convID; });
    var platform = String((c && c.source_platform) || "sms").toLowerCase();
    var type = String(file.type || "");
    var captioned = (platform === "whatsapp" && type.indexOf("audio/") !== 0) || platform === "signal";
    var t = $("input"), caption = captioned ? t.value.trim() : "";
    var reply = state.replyTo && (platform === "whatsapp" || platform === "signal") ? state.replyTo : null;
    var form = new FormData();
    form.append("conversation_id", convID);
    form.append("idempotency_key", sendKey());
    form.append("file", file, file.name || "attachment");
    if (reply) form.append("reply_to_id", reply.MessageID);
    if (caption) form.append("caption", caption);
    var kind = type.indexOf("image/") === 0 ? "photo" : type.indexOf("video/") === 0 ? "video" : type.indexOf("audio/") === 0 ? "audio" : "attachment";
    setAttachBusy(true);
    toast("Sending " + kind + "…");
    api("/api/send-media", { method: "POST", body: form }).then(function () {
      if (caption && t.value.trim() === caption) { t.value = ""; autosize(); }
      if (reply && state.replyTo === reply) clearReply();
      toast("Sent");
      if (state.current === convID) loadMessages(true);
      loadConversations();
    }).catch(function (e) {
      if (e.message !== "login required") toast("Not sent: " + e.message, "error");
    }).then(function () { setAttachBusy(false); });
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
  function applySettings() {
    var s = loadSettings();
    $("attachBtn").hidden = !s.showAttach;
    var sw = $("setAttach");
    sw.setAttribute("aria-checked", s.showAttach ? "true" : "false");
    sw.classList.toggle("on", s.showAttach);
  }
  function showSettings(open) {
    $("settingsView").hidden = !open;
    if (open) applySettings();
  }

  function insertText(text) {
    var t = $("input");
    var cur = t.value;
    var start = t.selectionStart != null ? t.selectionStart : cur.length;
    var end = t.selectionEnd != null ? t.selectionEnd : cur.length;
    if (document.activeElement !== t) { start = end = cur.length; }
    var before = cur.slice(0, start), after = cur.slice(end);
    var sep = before && !/\s$/.test(before) ? " " : "";
    t.value = before + sep + text + (after && !/^\s/.test(after) ? " " : "") + after;
    var pos = (before + sep + text).length;
    autosize();
    t.focus();
    try { t.setSelectionRange(pos, pos); } catch (e) {}
  }

  // ---------- mic / speech-to-text ----------
  // Engines, chosen by TESLA_STT_MODE (config.stt_mode):
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
    if (/Tesla|QtWebEngine/i.test(ua)) return "Tesla speech";
    if (/SamsungBrowser/i.test(ua)) return "Samsung speech";
    if (/Edg\//.test(ua)) return "Edge speech";
    if (/Chrome\//.test(ua)) return "Chrome speech";
    if (/Safari\//.test(ua)) return "Safari speech";
    return "Built-in speech";
  }
  function serverLabel() { var l = state.config.stt_label; return l && l !== "none" ? l : "Server STT"; }
  function speechLang() { return navigator.language || "en-US"; }
  function sttUsable() {
    var mode = sttMode();
    var builtin = mode !== "server" && !!speechCtor();
    var server = mode !== "builtin" && serverSTTEnabled() && micSupported();
    return builtin || server;
  }
  // Tiny "which engine" indicator: in the recording bar while listening, and
  // in the thread header afterwards (so it can be checked during testing).
  function showEngine(label, note) {
    $("recEngine").textContent = label;
    var e = $("sttEngine");
    e.textContent = "🎤 " + label + (note ? " · " + note : "");
    e.title = e.textContent;
    e.hidden = false;
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
    $("recBar").hidden = s !== "recording" && s !== "busy";
    $("recLabel").textContent = s === "busy" ? "Transcribing…" : "Listening… tap Done when finished";
    $("recDone").hidden = s === "busy";
    $("recTime").hidden = s === "busy";
    document.body.classList.toggle("is-recording", s === "recording");
    autosize();
  }
  function onMicTap() {
    if (state.transcribing) return;
    if (state.rec) { stopRecording(); return; }
    var mode = sttMode();
    if (mode === "server") { startServer(null); return; }
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
    setMicState(null);
    var t = $("input");
    t.focus();
    try { var pos = t.value.length - r.after.length; t.setSelectionRange(pos, pos); } catch (e) {}
    if (errorMsg && r.fatal) showEngine(builtinLabel(), "error: " + r.fatal);
    if (errorMsg) toast(errorMsg + (r.text() ? " Check the text so far, then tap Send." : ""), "error");
    else if (r.text()) toast("Check the text, then tap Send");
    else toast("Didn't catch that. Try again.");
  }

  // --- server (MediaRecorder -> /api/transcribe) ---
  // fallbackReason is set when we got here because built-in speech failed.
  function startServer(fallbackReason) {
    var why = fallbackReason ? " (built-in speech: " + fallbackReason + ")" : "";
    if (!serverSTTEnabled()) {
      toast(fallbackReason ? "Speech isn't available on this browser" + why + ", and server transcription isn't set up. Use the keyboard's mic instead."
        : "Voice input isn't set up on the server. Use the keyboard's mic instead.", "error");
      if (fallbackReason) showEngine(builtinLabel(), "failed: " + fallbackReason);
      return;
    }
    if (!micSupported()) { toast("This browser can't record audio" + why + ". Use the keyboard's mic instead.", "error"); return; }
    startRecording(fallbackReason);
  }
  function startRecording(fallbackReason) {
    var mime = pickMime();
    navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 } }).then(function (stream) {
      var rec;
      try {
        rec = mime ? new MediaRecorder(stream, { mimeType: mime, audioBitsPerSecond: 32000 }) : new MediaRecorder(stream);
      } catch (e) {
        rec = new MediaRecorder(stream);
      }
      var r = { kind: "server", stream: stream, recorder: rec, chunks: [], cancelled: false, started: Date.now(), timer: null, fallback: fallbackReason };
      state.rec = r;
      rec.ondataavailable = function (e) { if (e.data && e.data.size) r.chunks.push(e.data); };
      rec.onstop = function () { finishRecording(r); };
      rec.onerror = function (e) { toast("Recording error: " + ((e.error && e.error.name) || "unknown"), "error"); cancelRecording(); };
      rec.start(250);
      setMicState("recording");
      showEngine(serverLabel(), fallbackReason ? "built-in: " + fallbackReason : "");
      startTimer(r);
    }).catch(function (e) {
      var name = e && e.name;
      var msg = name === "NotAllowedError" ? "Microphone permission was denied. Allow it in the browser prompt and try again." :
        name === "NotFoundError" ? "No microphone found." :
        name === "SecurityError" || !window.isSecureContext ? "The mic needs HTTPS." :
        "Can't start the mic: " + (e && (e.message || name));
      toast(msg, "error");
      setMicState(null);
    });
  }
  function releaseMic(r) {
    clearInterval(r.timer);
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
    setMicState(null);
  }
  function finishRecording(r) {
    releaseMic(r);
    if (state.rec === r) state.rec = null;
    if (r.cancelled) return;
    var type = (r.recorder.mimeType || (r.chunks[0] && r.chunks[0].type) || "audio/webm");
    var blob = new Blob(r.chunks, { type: type });
    if (Date.now() - r.started < 400 || blob.size < 200) { setMicState(null); toast("That was too short. Hold on a moment longer."); return; }
    state.transcribing = true;
    setMicState("busy");
    autosize();
    api("/api/transcribe", { method: "POST", headers: { "Content-Type": type }, body: blob }).then(function (res) {
      if (res && res.label) showEngine(res.label, r.fallback ? "built-in: " + r.fallback : "");
      var text = (res && res.text || "").trim();
      if (!text) { toast("Didn't catch that. Try again."); return; }
      insertText(text);
      toast("Check the text, then tap Send");
    }).catch(function (e) {
      toast("Transcription failed: " + e.message, "error");
    }).finally(function () {
      state.transcribing = false;
      setMicState(null);
      autosize();
    });
  }

  // ---------- pairing ----------
  function showPair(open) {
    $("pairView").hidden = !open;
    if (open) { refreshPairing(); clearInterval(state.pairPoll); state.pairPoll = setInterval(refreshPairing, 1000); }
    else { clearInterval(state.pairPoll); state.pairPoll = null; }
  }
  // Cookies entry points (header button, pairing-screen button) only show
  // when cookies are actually needed: none saved yet (and not dev fake
  // pairing), or Google rejected the saved ones (google.auth_expired).
  // /admin/cookies itself stays reachable by URL.
  function cookiesNeeded(p) {
    if (!p) return false;
    var st = p.pairing || {}, g = p.google || {};
    if (g.auth_expired || g.AuthExpired) return true;
    return !p.cookies_saved && !st.fake;
  }
  function updateCookiesEntry(p) {
    if (p && p.cookies_saved !== undefined) $("cookiesBtn").hidden = !cookiesNeeded(p);
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
      $("pairTitle").textContent = st.state === "success" ? "Paired ✓" : "Pair with your phone";
      var msg = st.message || "";
      if (st.state === "idle") msg = p && p.cookies_saved ? "Google cookies are saved. Tap Start, then look for the emoji." : "First paste your Google cookies on the Cookies page (from a desktop).";
      if (st.state === "starting") msg = "Contacting Google… keep your phone nearby and unlocked.";
      $("pairMsg").textContent = msg;
    }
  }
  function refreshPairing() {
    return api("/api/tesla/pairing").then(function (p) { renderPairing(p); updateStatusFromPairing(p); return p; }).catch(function () {});
  }

  // ---------- status ----------
  function updateStatusFromPairing(p) {
    updateCookiesEntry(p);
    var g = p && p.google || {};
    var pill = $("conn");
    var needsPair = g.needs_pairing || g.NeedsPairing || g.needs_repair || g.NeedsRepair;
    var connected = g.connected || g.Connected;
    $("pairBtn").hidden = !needsPair && !(p && p.pairing && p.pairing.state === "emoji");
    if (needsPair) { pill.textContent = "Not paired"; pill.className = "pill pill-warn"; }
    else if (connected) {
      var phone = g.phone_responding !== undefined ? g.phone_responding : g.PhoneResponding;
      pill.textContent = phone === false ? "Phone offline" : "Connected";
      pill.className = "pill " + (phone === false ? "pill-warn" : "pill-ok");
    } else { pill.textContent = "Disconnected"; pill.className = "pill pill-bad"; }
    if (p && p.pairing && p.pairing.state === "emoji" && $("pairView").hidden && !state.pairAutoShown) { state.pairAutoShown = true; location.hash = "#pair"; }
  }

  // ---------- live updates ----------
  var es = null, refreshTimer = null;
  function scheduleRefresh(convId) {
    clearTimeout(refreshTimer);
    refreshTimer = setTimeout(function () {
      loadConversations();
      if (state.current && (!convId || convId === state.current)) loadMessages(false);
    }, 150);
  }
  function connectEvents() {
    if (!window.EventSource) return;
    try { es = new EventSource("/api/events"); } catch (e) { return; }
    es.onmessage = function (e) {
      var ev; try { ev = JSON.parse(e.data); } catch (x) { return; }
      if (ev.type === "conversations" || ev.type === "messages") scheduleRefresh(ev.conversation_id);
      else if (ev.type === "typing") setTyping(ev.conversation_id, ev.sender_number || ev.sender_name, ev.sender_name, !!ev.typing);
    };
    es.onopen = function () { fetchTyping(); };
    es.onerror = function () { /* browser retries automatically */ };
  }

  // ---------- message menu (⋮) + delete ----------
  // One shared popover, positioned next to the tapped ⋮ button. Closes on an
  // outside tap, Escape, or when the message goes away.
  function openMsgMenu(btn, m) {
    closeMsgMenu();
    state.menuFor = { msg: m, btn: btn };
    btn.classList.add("open");
    btn.setAttribute("aria-expanded", "true");
    var menu = $("msgMenu");
    menu.hidden = false;
    var r = btn.getBoundingClientRect(), mw = menu.offsetWidth, mh = menu.offsetHeight;
    var left = Math.max(16, Math.min(window.innerWidth - mw - 16, r.left));
    var top = r.bottom + 10;
    if (top + mh > window.innerHeight - 16) top = r.top - mh - 10;
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
    var c = state.convs.find(function (x) { return x.ConversationID === m.ConversationID; }) || {};
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
    postJSON("/api/tesla/messages/delete", { message_id: m.MessageID }).then(function (res) {
      hideConfirm();
      var rows = $("messages").querySelectorAll(".msg-row");
      for (var i = 0; i < rows.length; i++) if (rows[i].dataset.id === String(m.MessageID)) rows[i].remove();
      toast(res && res.scope === "local" ? "Deleted from this server" : "Deleted");
      loadMessages(false); loadConversations();
    }).catch(function (e) {
      hideConfirm();
      if (e.message !== "login required") toast("Not deleted: " + e.message, "error");
    });
  }

  // ---------- folders (view only) ----------
  // Archived / Spam / Blocked are listed live from Google Messages
  // (GET /api/tesla/folder). No archive / unarchive / spam / block actions.
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
    return api("/api/tesla/folder?name=" + encodeURIComponent(f.name)).then(function (list) {
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
  // keeps them for 15s (GET /api/tesla/typing) so a reload or a missed stop
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
    if (!group || !named.length) return "typing…";
    return (named.length > 1 ? named[0] + " +" + (named.length - 1) : named[0]) + " is typing…";
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
    var dots = el("span", "typing-dots");
    dots.appendChild(el("i")); dots.appendChild(el("i")); dots.appendChild(el("i"));
    bubble.appendChild(dots);
    if (c && c.IsGroup && names.filter(Boolean).length) bubble.appendChild(el("span", "typing-name", typingLabel(names, true)));
    row.appendChild(bubble);
    box.appendChild(row); // keep it last
    if (scrollIfNear && nearBottom) box.scrollTop = box.scrollHeight;
  }
  function fetchTyping() {
    return api("/api/tesla/typing").then(function (res) {
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
  // (POST /api/tesla/conversations/start). Nothing is sent from here.
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
    api("/api/tesla/contacts").then(function (d) {
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
    if (nc.group) b.appendChild(el("span", "contact-check", "✓"));
    b.addEventListener("click", function () { pickContact(c); });
    return b;
  }
  function renderNewChat() {
    $("newChatTitle").textContent = nc.group ? "New group conversation" : "New conversation";
    var g = $("newChatGroup");
    g.textContent = nc.group ? "✕ Cancel group" : "👥 Start group conversation";
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
      chip.appendChild(el("b", null, "✕"));
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
        sendRow.appendChild(el("div", "avatar", q.indexOf("@") >= 0 ? "✉" : "#"));
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
    postJSON("/api/tesla/conversations/start", { numbers: numbers }).then(function (res) {
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

  // ---------- init ----------
  function init() {
    $("compose").addEventListener("submit", sendMessage);
    $("input").addEventListener("input", autosize);
    $("input").addEventListener("keydown", function (e) {
      if (e.key === "Enter" && !e.shiftKey && !e.isComposing) { e.preventDefault(); sendMessage(); }
    });
    $("micBtn").addEventListener("click", onMicTap);
    $("attachBtn").addEventListener("click", onAttachTap);
    $("fileInput").addEventListener("change", onFilePicked);
    $("settingsBtn").addEventListener("click", function () { showSettings(true); });
    $("settingsClose").addEventListener("click", function () { showSettings(false); });
    $("settingsView").addEventListener("click", function (e) { if (e.target === $("settingsView")) showSettings(false); });
    $("setAttach").addEventListener("click", function () {
      var st = loadSettings();
      st.showAttach = !st.showAttach;
      saveSettings(st);
      applySettings();
    });
    applySettings();
    $("recDone").addEventListener("click", stopRecording);
    $("recCancel").addEventListener("click", cancelRecording);
    $("threadHeader").addEventListener("click", function (e) {
      if ($("backBtn").contains(e.target)) return;
      showThemeSheet(true);
    });
    $("themeClose").addEventListener("click", function () { showThemeSheet(false); });
    $("themeReset").addEventListener("click", function () { chooseTheme(null); });
    $("themeView").addEventListener("click", function (e) { if (e.target === $("themeView")) showThemeSheet(false); });
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
    document.addEventListener("pointerdown", function (e) {
      if (!state.menuFor) return;
      if ($("msgMenu").contains(e.target) || (state.menuFor.btn && state.menuFor.btn.contains(e.target))) return;
      closeMsgMenu();
    }, true);
    $("messages").addEventListener("scroll", function () { if (state.menuFor) closeMsgMenu(); }, { passive: true });
    document.addEventListener("keydown", function (e) {
      if (e.key !== "Escape") return;
      if (closeImageViewer()) { /* photo viewer first: it's on top */ }
      else if (!$("confirmView").hidden) { if (!$("confirmOk").disabled) hideConfirm(); }
      else if (state.menuFor) closeMsgMenu();
      else if (!$("newChatView").hidden) closeNewChat();
      else return;
      e.preventDefault();
    });
    // folders (Settings -> Folders)
    Array.prototype.forEach.call(document.querySelectorAll(".folder-row"), function (b) {
      b.addEventListener("click", function () { showSettings(false); enterFolder(b.dataset.folder); });
    });
    $("folderBack").addEventListener("click", exitFolder);
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
    $("pairBtn").addEventListener("click", function () { location.hash = "#pair"; });
    $("pairClose").addEventListener("click", function () { history.replaceState(null, "", location.pathname); route(); });
    $("pairStart").addEventListener("click", function () {
      postJSON("/api/tesla/pairing/start").then(renderPairing).catch(function (e) { toast(e.message, "error"); refreshPairing(); });
    });
    $("pairCancel").addEventListener("click", function () { postJSON("/api/tesla/pairing/cancel").then(renderPairing); });
    var img = $("pairEmojiImg");
    img.addEventListener("load", function () { $("pairEmojiWrap").classList.add("img-ok"); });
    img.addEventListener("error", function () { $("pairEmojiWrap").classList.remove("img-ok"); });
    window.addEventListener("hashchange", route);

    api("/api/tesla/config").then(function (c) {
      state.config = c;
      document.body.classList.toggle("no-stt", !sttUsable());
    }).catch(function () {});
    loadConversations().then(function () {
      var m = /[?&]c=([^&]+)/.exec(location.search);
      if (m) openConversation(decodeURIComponent(m[1]));
    });
    fetchTyping();
    refreshPairing();
    setInterval(refreshPairing, 15000);
    setInterval(function () { if (!es || es.readyState === 2) scheduleRefresh(); }, 20000);
    connectEvents();
    route();
    autosize();
  }
  init();
})();
