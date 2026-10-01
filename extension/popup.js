/*
 * Popup controller for the Messages Enhanced cookie sender.
 *
 * SECURITY: cookie values are read via chrome.cookies, put straight into the
 * POST body, and never logged, stored, or shown. Only cookie NAMES appear in
 * the UI. See lib.js for the pure helpers (unit-tested).
 */
'use strict';

const L = self.TMLib;
const STORAGE_KEY = 'appAddress';

const els = {
  app: document.getElementById('app'),
  appHint: document.getElementById('app-hint'),
  send: document.getElementById('send'),
  found: document.getElementById('found'),
  foundList: document.getElementById('found-list'),
  optional: document.getElementById('optional'),
  status: document.getElementById('status'),
  actions: document.getElementById('actions'),
};

let busy = false;

function setStatus(msg, kind) {
  els.status.textContent = msg || '';
  els.status.className = 'status' + (kind ? ' status-' + kind : '');
}

function clearActions() {
  els.actions.replaceChildren();
}

function addAction(label, onClick, primary) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'btn ' + (primary ? 'btn-primary' : 'btn-secondary');
  b.textContent = label;
  b.addEventListener('click', onClick);
  els.actions.appendChild(b);
  return b;
}

// Show the six required cookie NAMES with found/missing marks, plus a line
// listing any optional names found. Values are never displayed.
function showFound(cookieMap) {
  els.foundList.replaceChildren();
  els.optional.textContent = '';
  if (!cookieMap) {
    els.found.hidden = true;
    return;
  }
  const { found } = L.requiredStatus(cookieMap);
  for (const name of L.REQUIRED) {
    const li = document.createElement('li');
    const ok = found.includes(name);
    li.className = ok ? 'yes' : 'no';
    li.textContent = (ok ? '\u2713 ' : '\u2717 ') + name;
    li.title = ok ? 'found' : 'missing';
    els.foundList.appendChild(li);
  }
  const extras = L.presentNames(cookieMap).filter((n) => !L.REQUIRED.includes(n));
  if (extras.length) els.optional.textContent = 'Also sending: ' + extras.join(', ');
  els.found.hidden = false;
}

async function openTab(url) {
  try {
    await chrome.tabs.create({ url });
  } catch (e) {
    setStatus('Could not open a tab. Open ' + url + ' manually.', 'error');
  }
}

// --- storage ---------------------------------------------------------------

function loadAddress() {
  return new Promise((resolve) => {
    chrome.storage.local.get([STORAGE_KEY], (res) => {
      resolve((res && res[STORAGE_KEY]) || L.DEFAULT_APP);
    });
  });
}

function saveAddress(value) {
  return new Promise((resolve) => {
    chrome.storage.local.set({ [STORAGE_KEY]: value }, () => resolve());
  });
}

// --- permissions -----------------------------------------------------------

function requestPermission(originPattern) {
  return new Promise((resolve) => {
    try {
      chrome.permissions.request({ origins: [originPattern] }, (granted) => {
        if (chrome.runtime.lastError) resolve(false);
        else resolve(!!granted);
      });
    } catch (e) {
      resolve(false);
    }
  });
}

// --- cookies ---------------------------------------------------------------

function getAll(url, storeId) {
  return new Promise((resolve) => {
    const details = storeId ? { url, storeId } : { url };
    chrome.cookies.getAll(details, (cookies) => {
      if (chrome.runtime.lastError) {
        resolve([]);
        return;
      }
      resolve(cookies || []);
    });
  });
}

// Cookie store of the window the popup was opened from, so an incognito
// window (when the extension is allowed in incognito) reads incognito
// cookies. Returns undefined to use the default store.
function getAllBy(query, storeId) {
  return new Promise((resolve) => {
    const details = storeId ? Object.assign({}, query, { storeId }) : Object.assign({}, query);
    chrome.cookies.getAll(details, (cookies) => {
      if (chrome.runtime.lastError) {
        resolve([]);
        return;
      }
      resolve(cookies || []);
    });
  });
}

async function currentStoreId() {
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab || tab.id == null) return undefined;
    const stores = await chrome.cookies.getAllCookieStores();
    const store = stores.find((st) => (st.tabIds || []).includes(tab.id));
    return store ? store.id : undefined;
  } catch (e) {
    return undefined;
  }
}

async function collectCookies() {
  // Query by DOMAIN, not by url. A url query over https returned only the
  // Secure cookies in some Chrome builds, dropping SID/HSID/APISID (which are
  // not marked Secure). A domain query returns them all. `google.com` also
  // covers subdomains, so it includes the messages.google.com OSID; selection
  // in lib.js prefers that host for OSID.
  const storeId = await currentStoreId();
  const g = await getAllBy({ domain: 'google.com' }, storeId);
  const m = await getAllBy({ domain: 'messages.google.com' }, storeId);
  let map = L.selectCookies([m, g]);
  let status = L.requiredStatus(map);
  if (status.missing.length) {
    // Fall back to url-based queries, then to the default cookie store.
    const primary = await getAll(L.PRIMARY_URL, storeId);
    const fallback = await getAll(L.FALLBACK_URL, storeId);
    map = L.selectCookies([m, g, primary, fallback]);
    status = L.requiredStatus(map);
    if (status.missing.length && storeId) {
      const g2 = await getAllBy({ domain: 'google.com' });
      const m2 = await getAllBy({ domain: 'messages.google.com' });
      map = L.selectCookies([m, g, primary, fallback, m2, g2]);
      status = L.requiredStatus(map);
    }
  }
  return { map, status };
}

// --- main action -----------------------------------------------------------

async function onSend() {
  if (busy) return;
  clearActions();
  showFound(null);

  const norm = L.normalizeAppAddress(els.app.value);
  if (!norm.ok) {
    setStatus(norm.error, 'error');
    return;
  }
  // Ask for host access first, while we still have the click's user
  // gesture. Resolves true with no prompt if already granted (e.g. the
  // built-in http://localhost / 127.0.0.1 permissions).
  const granted = await requestPermission(norm.hostPattern);
  if (!granted) {
    setStatus('Permission to reach ' + norm.origin + ' was not granted.', 'error');
    return;
  }
  await saveAddress(norm.origin);
  els.app.value = norm.origin;

  busy = true;
  els.send.disabled = true;
  setStatus('Reading cookies…');

  try {
    const { map, status } = await collectCookies();
    showFound(map);

    if (status.missing.length) {
      const hint = status.missing.includes('OSID')
        ? 'OSID only exists after you open Google Messages for web and sign in.'
        : 'Open Google Messages for web and sign in to Google, then try again.';
      setStatus('Missing: ' + status.missing.join(', ') + '. ' + hint, 'error');
      addAction('Open messages.google.com/web', () => openTab(L.MESSAGES_WEB_URL), true);
      return;
    }

    setStatus('Sending to ' + norm.origin + '…');
    let resp;
    try {
      resp = await fetch(L.cookiesEndpoint(norm.origin), {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: L.buildFormBody(map),
        redirect: 'follow',
      });
    } catch (e) {
      setStatus(
        'Could not reach ' + norm.origin + '. Is the app running and the address correct?',
        'error'
      );
      return;
    }

    let bodyText = '';
    try {
      bodyText = await resp.text();
    } catch (e) {
      bodyText = '';
    }

    const result = L.interpretResponse(resp.status, resp.redirected, resp.url, bodyText);
    if (result.kind === 'saved') {
      setStatus(result.message, 'ok');
    } else if (result.kind === 'need_login') {
      setStatus('Sign in to the app first, then send again.', 'error');
      addAction('Open app sign-in', () => openTab(L.loginUrl(norm.origin)), true);
    } else {
      setStatus(result.message, 'error');
    }
  } finally {
    busy = false;
    els.send.disabled = false;
  }
}

// --- address field hint ----------------------------------------------------

function refreshHint() {
  const norm = L.normalizeAppAddress(els.app.value);
  if (!norm.ok) {
    els.appHint.textContent = '';
    return;
  }
  if (!norm.isLoopback) {
    els.appHint.textContent =
      'Non-local address: Chrome will ask permission to reach it the first time.';
  } else {
    els.appHint.textContent = '';
  }
}

// --- init ------------------------------------------------------------------

async function init() {
  const addr = await loadAddress();
  els.app.value = addr;
  refreshHint();
  els.app.addEventListener('input', refreshHint);
  els.send.addEventListener('click', onSend);
}

init();
