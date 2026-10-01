/*
 * Pure helpers for the Messages Enhanced cookie sender. No chrome.* or DOM use,
 * so this file can be unit-tested in Node (see tests/lib.test.js) and reused
 * by popup.js in the browser.
 *
 * SECURITY: nothing in this file (or popup.js) ever logs or displays a
 * cookie value. Only cookie *names* are surfaced.
 */
(function (root) {
  'use strict';

  const DEFAULT_APP = 'http://localhost:7117';

  // Must match internal/webapp/cookies.go in the app.
  const REQUIRED = ['SID', 'HSID', 'SSID', 'OSID', 'APISID', 'SAPISID'];
  const OPTIONAL = [
    '__Secure-1PSID', '__Secure-3PSID', '__Secure-1PSIDTS', '__Secure-3PSIDTS',
    '__Secure-1PAPISID', '__Secure-3PAPISID', 'NID', 'SIDCC',
  ];
  const WANTED = REQUIRED.concat(OPTIONAL);

  const PRIMARY_URL = 'https://messages.google.com/';
  const FALLBACK_URL = 'https://www.google.com/';
  const MESSAGES_WEB_URL = 'https://messages.google.com/web/';

  const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]', '::1']);

  /**
   * Normalize what the user typed into an app address.
   * Accepts "localhost:7117", "http://localhost:7117", "https://tm.example.com".
   * Returns { ok:true, origin, isLoopback, isHttps, hostPattern } or
   * { ok:false, error }.
   */
  function normalizeAppAddress(input) {
    let s = String(input == null ? '' : input).trim();
    if (s === '') return { ok: false, error: 'Enter the app address.' };
    if (/^[a-z][a-z0-9+.-]*:\/\//i.test(s) && !/^https?:\/\//i.test(s)) {
      return { ok: false, error: 'Address must start with http:// or https://.' };
    }
    if (!/^https?:\/\//i.test(s)) s = 'http://' + s;
    let u;
    try {
      u = new URL(s);
    } catch (e) {
      return { ok: false, error: 'That does not look like a valid address.' };
    }
    if (u.protocol !== 'http:' && u.protocol !== 'https:') {
      return { ok: false, error: 'Address must start with http:// or https://.' };
    }
    if (!u.hostname) return { ok: false, error: 'Address is missing a host.' };
    const isLoopback = LOOPBACK_HOSTS.has(u.hostname.toLowerCase());
    return {
      ok: true,
      origin: u.origin,
      isLoopback: isLoopback,
      isHttps: u.protocol === 'https:',
      // host_permissions match pattern (scheme + host + /*), used to
      // request an optional permission at runtime for non-default hosts.
      // Port is omitted: Chrome match patterns without a port match any port.
      hostPattern: u.protocol + '//' + u.hostname + '/*',
    };
  }

  /** Build the POST target from an app origin. */
  function cookiesEndpoint(origin) {
    return origin.replace(/\/+$/, '') + '/admin/cookies';
  }

  function loginUrl(origin) {
    return origin.replace(/\/+$/, '') + '/login';
  }

  /**
   * Reduce a list of chrome.cookies.Cookie objects (from one or more getAll
   * calls) into a { name: value } map for just the cookies the app wants.
   * Later entries do NOT overwrite earlier non-empty ones, so the primary
   * (messages.google.com) query wins over the www.google.com fallback.
   * `cookieArrays` is an array of arrays.
   */
  // For a required cookie, which host should win when the same name exists on
  // several google hosts. OSID exists on many (mail/drive/messages/...) but the
  // app needs the messages.google.com one; the other five live on .google.com.
  const HOST_PREF = { OSID: ['messages.google.com'] };
  const DEFAULT_PREF = ['google.com'];
  function stripDot(d) { return String(d == null ? '' : d).replace(/^\./, ''); }

  function selectCookies(cookieArrays) {
    const wanted = new Set(WANTED);
    const all = [];
    for (const arr of cookieArrays) {
      if (!arr) continue;
      for (const c of arr) {
        if (!c || !c.name || !wanted.has(c.name)) continue;
        const v = typeof c.value === 'string' ? c.value : '';
        if (v.trim() === '') continue;
        all.push(c);
      }
    }
    const out = {};
    for (const name of WANTED) {
      const cands = all.filter((c) => c.name === name);
      if (!cands.length) continue;
      const prefs = HOST_PREF[name] || DEFAULT_PREF;
      let val = null;
      for (const h of prefs) {
        const hit = cands.find((c) => stripDot(c.domain) === h);
        if (hit) { val = hit.value; break; }
      }
      if (val === null) val = cands[0].value; // preserves earlier-array order
      out[name] = val;
    }
    return out;
  }

  /** Which required names are present / missing in a name->value map. */
  function requiredStatus(cookieMap) {
    const found = [];
    const missing = [];
    for (const name of REQUIRED) {
      if (Object.prototype.hasOwnProperty.call(cookieMap, name)) found.push(name);
      else missing.push(name);
    }
    return { found: found, missing: missing };
  }

  /** Names present, sorted, for display. NEVER returns values. */
  function presentNames(cookieMap) {
    return Object.keys(cookieMap).sort();
  }

  /** Encode a name->value map as the form body the app expects. */
  function buildFormBody(cookieMap) {
    const json = JSON.stringify(cookieMap);
    return 'cookies=' + encodeURIComponent(json);
  }

  /**
   * Interpret the app's response. Pass status, whether the fetch was
   * redirected, the final URL, and the response body text.
   * Returns { kind, message } where kind is one of:
   *   'saved'      -> success, message lists saved names
   *   'need_login' -> user must sign in to the app
   *   'error'      -> app reported a problem (e.g. missing cookies)
   *   'unknown'    -> couldn't classify
   */
  function interpretResponse(status, redirected, finalUrl, bodyText) {
    const body = String(bodyText == null ? '' : bodyText);
    const url = String(finalUrl == null ? '' : finalUrl);
    if (status === 401 || /\/login(\?|$)/.test(url) || (redirected && /\/login/.test(url))) {
      return { kind: 'need_login', message: 'The app needs you to sign in first.' };
    }
    let m = body.match(/Saved:\s*([^<\n]+)/i);
    if (m) {
      return { kind: 'saved', message: 'Saved: ' + m[1].trim() };
    }
    m = body.match(/Missing required cookies:[^<\n]*/i);
    if (m) {
      return { kind: 'error', message: m[0].trim() };
    }
    // Other server-rendered error paragraph, e.g. a parse error.
    m = body.match(/class="login-error"[^>]*>([^<]+)</i);
    if (m) {
      return { kind: 'error', message: m[1].trim() };
    }
    if (status === 403 && /cross-origin/i.test(body)) {
      return {
        kind: 'error',
        message: 'The app rejected the request as cross-origin (403). ' +
          'Make sure the extension has permission for this address, or see the README.',
      };
    }
    if (status >= 200 && status < 300) {
      return { kind: 'unknown', message: 'The app responded but the result could not be read.' };
    }
    return { kind: 'error', message: 'The app returned HTTP ' + status + '.' };
  }

  const api = {
    DEFAULT_APP,
    REQUIRED,
    OPTIONAL,
    WANTED,
    PRIMARY_URL,
    FALLBACK_URL,
    MESSAGES_WEB_URL,
    normalizeAppAddress,
    cookiesEndpoint,
    loginUrl,
    selectCookies,
    requiredStatus,
    presentNames,
    buildFormBody,
    interpretResponse,
  };

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  } else {
    root.TMLib = api;
  }
})(typeof self !== 'undefined' ? self : this);
