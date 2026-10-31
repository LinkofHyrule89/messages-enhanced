// Per-conversation composer drafts, kept only in this browser (localStorage,
// keyed by conversation ID). Never sent to the server. Mention chips are
// encoded in the composer's .value text, so they come back with it.
(function (root) {
  "use strict";
  var PREFIX = "me.draft.";
  var MAX = 20000;
  function normalize(text) {
    var t = text == null ? "" : String(text);
    if (!t.trim()) return null;
    return t.length > MAX ? t.slice(0, MAX) : t;
  }
  function store(s) {
    if (s) return s;
    try { return root.localStorage || null; } catch (e) { return null; }
  }
  function get(id, s) {
    s = store(s);
    if (!id || !s) return "";
    try { return s.getItem(PREFIX + id) || ""; } catch (e) { return ""; }
  }
  function put(id, text, s) {
    s = store(s);
    if (!id || !s) return false;
    var v = normalize(text);
    try {
      if ((s.getItem(PREFIX + id) || null) === v) return false;
      if (v == null) s.removeItem(PREFIX + id); else s.setItem(PREFIX + id, v);
      return true;
    } catch (e) { return false; }
  }
  function clear(id, s) { return put(id, null, s); }
  function listPreview(text) {
    return "Draft: " + String(text || "").replace(/[\n\u00a0]+/g, " ").replace(/ {2,}/g, " ").trim();
  }
  var api = { PREFIX: PREFIX, MAX: MAX, normalize: normalize, get: get, put: put, clear: clear, listPreview: listPreview };
  root.MEDrafts = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof window !== "undefined" ? window : globalThis);
