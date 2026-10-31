// Parsers for Google Messages special content: contacts (vCard), calendar
// invites (.ics), RCS location pushes, RCS rich cards / carousels /
// suggestions (GSMA bot message JSON), one-time codes, payment amounts and
// system notices (tombstones). Pure functions, no DOM; shared by app.js.
(function (root) {
  "use strict";

  function kind(mime, body) {
    var m = String(mime || "").toLowerCase(), b = String(body || "").trim().toLowerCase();
    if (/vcard|x-vcard|directory/.test(m) || /\.vcf$/.test(b)) return "vcard";
    if (/calendar|x-vcalendar/.test(m) || /\.ics$/.test(b)) return "ics";
    if (/rcspushlocation/.test(m)) return "pushloc";
    if (/botmessage|openrichcard|xbotmessage|botsuggestion/.test(m)) return "richcard";
    if (/^audio\//.test(m)) return "audio";
    if (/^video\//.test(m)) return "video";
    if (/^image\//.test(m)) return "image";
    return "file";
  }

  function unfold(text) { return String(text || "").replace(/\r\n/g, "\n").replace(/\n[ \t]/g, ""); }
  function unescapeV(s) { return String(s || "").replace(/\\n/gi, "\n").replace(/\\([,;\\])/g, "$1").trim(); }
  function props(text) {
    var out = [];
    unfold(text).split("\n").forEach(function (line) {
      var i = line.indexOf(":");
      if (i <= 0) return;
      var head = line.slice(0, i).split(";"), name = head[0].replace(/^item\d+\./i, "").toUpperCase();
      out.push({ name: name, params: head.slice(1).join(";").toUpperCase(), value: line.slice(i + 1) });
    });
    return out;
  }

  function vcard(text) {
    var ps = props(text), name = "", n = "", phones = [], emails = [], org = "";
    ps.forEach(function (p) {
      if (p.name === "FN" && !name) name = unescapeV(p.value);
      else if (p.name === "N" && !n) n = unescapeV(p.value.split(";").slice(0, 2).reverse().join(" "));
      else if (p.name === "TEL") phones.push(unescapeV(p.value).replace(/^tel:/i, ""));
      else if (p.name === "EMAIL") emails.push(unescapeV(p.value));
      else if (p.name === "ORG" && !org) org = unescapeV(p.value.split(";")[0]);
    });
    name = name || n.trim() || org || phones[0] || "Contact";
    return { name: name, phones: phones, emails: emails, org: org };
  }

  function icsDate(v, params) {
    var m = /^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})?(Z)?)?$/.exec(String(v || "").trim());
    if (!m) return null;
    if (!m[4]) return { ms: new Date(+m[1], m[2] - 1, +m[3]).getTime(), allDay: true };
    var args = [+m[1], m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0)];
    return { ms: m[7] ? Date.UTC.apply(null, args) : new Date(args[0], args[1], args[2], args[3], args[4], args[5]).getTime(), allDay: false };
  }
  function ics(text) {
    var ev = null, inEvent = false;
    props(text).forEach(function (p) {
      if (p.name === "BEGIN" && /VEVENT/i.test(p.value)) { inEvent = true; ev = ev || {}; return; }
      if (p.name === "END" && /VEVENT/i.test(p.value)) { inEvent = false; return; }
      if (!inEvent || !ev) return;
      if (p.name === "SUMMARY" && !ev.title) ev.title = unescapeV(p.value);
      else if (p.name === "LOCATION" && !ev.location) ev.location = unescapeV(p.value);
      else if (p.name === "DESCRIPTION" && !ev.description) ev.description = unescapeV(p.value);
      else if (p.name === "DTSTART" && !ev.start) ev.start = icsDate(p.value, p.params);
      else if (p.name === "DTEND" && !ev.end) ev.end = icsDate(p.value, p.params);
    });
    if (!ev) return null;
    ev.title = ev.title || "Event";
    return ev;
  }

  function pushLocation(xml) {
    var s = String(xml || ""), m = /<(?:[A-Za-z0-9]+:)?pos[^>]*>\s*(-?\d+(?:\.\d+)?)[\s,]+(-?\d+(?:\.\d+)?)\s*</.exec(s);
    if (!m) return null;
    var l = /\blabel="([^"]*)"/.exec(s) || /<(?:[A-Za-z0-9]+:)?(?:note|label)[^>]*>([\s\S]*?)</.exec(s);
    var label = l ? l[1].replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&apos;/g, "'").trim() : "";
    return { href: "https://maps.google.com/?q=" + m[1] + "," + m[2], label: label };
  }

  // GSMA RCS bot message (Universal Profile): {message:{generalPurposeCard |
  // generalPurposeCardCarousel, suggestions?}} or a bare suggestion list.
  function suggestionList(arr) {
    var out = [];
    (arr || []).forEach(function (s) {
      var r = s && (s.reply || s.action);
      if (!r || !r.displayText) return;
      var url = r.urlAction && r.urlAction.openUrl && r.urlAction.openUrl.url;
      var dial = r.dialerAction && (r.dialerAction.dialPhoneNumber || {}).phoneNumber;
      var map = r.mapAction && r.mapAction.showLocation && r.mapAction.showLocation.location;
      var href = url || (dial ? "tel:" + dial : "") || (map && map.latitude != null ? "https://maps.google.com/?q=" + map.latitude + "," + map.longitude : "");
      out.push({ text: String(r.displayText), reply: !!s.reply, href: /^(https?:|tel:)/i.test(href) ? href : "" });
    });
    return out.slice(0, 11);
  }
  function cardOf(c) {
    var ct = (c && c.content) || c || {};
    var media = ct.media && (ct.media.mediaUrl || ct.media.thumbnailUrl);
    return { title: ct.title ? String(ct.title) : "", description: ct.description ? String(ct.description) : "", media: /^https:/i.test(media || "") ? media : "", suggestions: suggestionList(ct.suggestions) };
  }
  function richCard(json) {
    var o = json;
    if (typeof o === "string") { try { o = JSON.parse(o); } catch (e) { return null; } }
    if (!o || typeof o !== "object") return null;
    var msg = o.message || o, cards = [];
    var gp = msg.generalPurposeCard, car = msg.generalPurposeCardCarousel;
    if (gp) cards.push(cardOf(gp));
    if (car && car.content) car.content.forEach(function (c) { cards.push(cardOf(c)); });
    var sugg = suggestionList(o.suggestions || msg.suggestions);
    if (!cards.length && !sugg.length) return null;
    return { cards: cards, carousel: !!car, suggestions: sugg };
  }

  // One-time codes: 4-8 digits (or 3-3 with a dash/space) near a code word.
  var OTP_WORD = /(code|otp|passcode|pin|verification|verify|one[- ]time|2fa|security|login|sign[- ]in|c[oó]digo|token)/i;
  function otp(text) {
    var t = String(text || "");
    if (!OTP_WORD.test(t) || t.length > 400) return "";
    var m = /(?:^|[^\d$€£.,:\/])(\d{3}[- ]\d{3}|\d{4,8})(?![\d%]|[.,]\d|\s?(?:am|pm)\b)/i.exec(t);
    if (!m) return "";
    var code = m[1].replace(/[- ]/g, "");
    if (/^(19|20)\d{2}$/.test(code) && !/code|otp/i.test(t.slice(Math.max(0, m.index - 20), m.index + 30))) return "";
    return code;
  }
  // Payment amounts in payment-style messages (sent you, paid, requested, due).
  var PAY_WORD = /(paid|payment|sent you|you sent|requested|request(?:ed)? from|received|refund|charged|due|balance|venmo|zelle|cash app|paypal|google pay|apple cash)/i;
  function payment(text) {
    var t = String(text || "");
    if (!PAY_WORD.test(t) || t.length > 400) return "";
    var m = /(?:[$€£]\s?\d[\d,]*(?:\.\d{2})?|\d[\d,]*(?:\.\d{2})?\s?(?:USD|EUR|GBP))/.exec(t);
    return m ? m[0].replace(/\s+/g, " ") : "";
  }

  // System notices (Google Messages tombstones).
  var NOTICE_FALLBACK = [
    [/MISSED_VIDEO_CALL/, "call", "Missed video call"],
    [/MISSED.*CALL|CALL.*MISSED/, "call", "Missed call"],
    [/E2EE_TO|SWITCH_TO_TEXT|TO_TEXT|TO_RCS$|TO_MMS/, "unlock", "Messages are no longer end-to-end encrypted"],
    [/ENCRYPTED|E2EE/, "lock", "Messages are end-to-end encrypted"],
    [/IDENTITY_CHANGED/, "shield", "Security info changed"],
    [/VERIFIED/, "shield", "Verified sender"],
    [/SCAM|SPAM/, "warn", "This may be a scam"],
    [/JOINED/, "group", "Someone joined"],
    [/LEFT|REMOVED/, "group", "Someone left"],
    [/RENAMED|NAME_CLEARED|ICON_CHANGED|THEME_CHANGE/, "edit", "Group details changed"],
    [/GROUP_CREATED|BROADCAST_CREATED/, "group", "Group created"],
    [/SATELLITE/, "info", "Satellite messaging"],
    [/CHATBOT/, "info", "Business chat"],
    [/PROTOCOL_SWITCH_TO_RCS|ONE_ON_ONE_RCS/, "info", "Chatting with RCS"],
    [/SMS_CREATED|SWITCH_TO_GROUP_MMS|SMS/, "info", "Chatting with SMS/MMS"],
  ];
  function isNotice(status) { return /TOMBSTONE/i.test(String(status || "")); }
  function notice(status, body) {
    var s = String(status || "").toUpperCase(), icon = "info", fallback = "";
    for (var i = 0; i < NOTICE_FALLBACK.length; i++) if (NOTICE_FALLBACK[i][0].test(s)) { icon = NOTICE_FALLBACK[i][1]; fallback = NOTICE_FALLBACK[i][2]; break; }
    var text = String(body || "").trim() || fallback;
    return text ? { icon: icon, text: text } : null;
  }

  var api = { kind: kind, vcard: vcard, ics: ics, pushLocation: pushLocation, richCard: richCard, otp: otp, payment: payment, isNotice: isNotice, notice: notice };
  if (typeof module === "object" && module.exports) module.exports = api; else root.MERich = api;
})(this);
