// Clean links: strips tracking query parameters from URLs in text.
// A parameter is removed when its name matches the rule regex anchored at
// the start of the name (so "utm" removes utm_source, "t" removes t and tag),
// case-insensitive. Everything else in the URL stays byte-for-byte.
(function (root) {
  "use strict";
  var DEFAULT_RULE = "ref|utm|source|clickfrom|sku|spm|pf_rd|_ga|share_|ShareTm|_encoding|psc|t|s|wprov|si|a";
  function compile(rule) {
    var r = String(rule == null ? "" : rule).trim();
    if (!r) return null;
    try { return new RegExp("^(?:" + r + ")", "i"); } catch (e) { return null; }
  }
  function validRule(rule) { return !!compile(rule); }
  function cleanURL(url, rule) {
    var re = compile(rule == null ? DEFAULT_RULE : rule);
    if (!re) return url;
    var hash = "", i = url.indexOf("#");
    if (i >= 0) { hash = url.slice(i); url = url.slice(0, i); }
    var q = url.indexOf("?");
    if (q < 0) return url + hash;
    var base = url.slice(0, q), parts = url.slice(q + 1).split("&");
    var keep = parts.filter(function (p) {
      if (!p) return false;
      var name = p.split("=")[0];
      try { name = decodeURIComponent(name.replace(/\+/g, " ")); } catch (e) {}
      return !re.test(name);
    });
    return base + (keep.length ? "?" + keep.join("&") : "") + hash;
  }
  var URL_RE = /\bhttps?:\/\/[^\s<>"']+/gi;
  // Returns { text, count } where count is the number of URLs changed.
  function cleanText(text, rule) {
    var n = 0;
    var out = String(text || "").replace(URL_RE, function (u) {
      var tail = "", m = u.match(/[.,;:!?)\]]+$/);
      if (m && !/\?[^?]*$/.test(m[0])) { tail = m[0]; u = u.slice(0, -tail.length); }
      var c = cleanURL(u, rule);
      if (c !== u) n++;
      return c + tail;
    });
    return { text: out, count: n };
  }
  var api = { DEFAULT_RULE: DEFAULT_RULE, validRule: validRule, cleanURL: cleanURL, cleanText: cleanText };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (root) root.MELinkClean = api;
})(typeof window !== "undefined" ? window : null);
