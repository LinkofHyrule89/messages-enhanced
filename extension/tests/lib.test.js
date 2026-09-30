// Run: node --test tests/   (Node 18+; no dependencies)
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const L = require('../lib.js');

const c = (name, value, domain) => ({ name, value, domain: domain || '.google.com' });

test('cookie name lists match the app (internal/tesla/cookies.go)', () => {
  assert.deepEqual(L.REQUIRED, ['SID', 'HSID', 'SSID', 'OSID', 'APISID', 'SAPISID']);
  assert.deepEqual(L.OPTIONAL, ['__Secure-1PSID', '__Secure-3PSID', '__Secure-1PSIDTS',
    '__Secure-3PSIDTS', '__Secure-1PAPISID', '__Secure-3PAPISID', 'NID', 'SIDCC']);
  const goPath = path.join(__dirname, '..', '..', 'app', 'internal', 'tesla', 'cookies.go');
  if (fs.existsSync(goPath)) {
    const go = fs.readFileSync(goPath, 'utf8');
    const grab = (v) => JSON.parse('[' + go.match(new RegExp(v + ' = \\[\\]string\\{([^}]*)\\}'))[1] + ']');
    assert.deepEqual(L.REQUIRED, grab('RequiredGoogleCookies'));
    assert.deepEqual(L.OPTIONAL, grab('OptionalGoogleCookies'));
  }
});

test('normalizeAppAddress', () => {
  let r = L.normalizeAppAddress('localhost:7117');
  assert.equal(r.ok, true);
  assert.equal(r.origin, 'http://localhost:7117');
  assert.equal(r.isLoopback, true);
  assert.equal(r.hostPattern, 'http://localhost/*');
  r = L.normalizeAppAddress('  https://tm.example.com/some/path?x=1 ');
  assert.equal(r.origin, 'https://tm.example.com');
  assert.equal(r.isLoopback, false);
  assert.equal(r.isHttps, true);
  assert.equal(r.hostPattern, 'https://tm.example.com/*');
  assert.equal(L.normalizeAppAddress('http://127.0.0.1:7199').isLoopback, true);
  assert.equal(L.normalizeAppAddress('').ok, false);
  assert.equal(L.normalizeAppAddress('ftp://x').ok, false);
  assert.equal(L.normalizeAppAddress('http://').ok, false);
});

test('endpoint helpers', () => {
  assert.equal(L.cookiesEndpoint('http://localhost:7117'), 'http://localhost:7117/admin/cookies');
  assert.equal(L.cookiesEndpoint('http://localhost:7117/'), 'http://localhost:7117/admin/cookies');
  assert.equal(L.loginUrl('https://a.b'), 'https://a.b/login');
  assert.equal(L.DEFAULT_APP, 'http://localhost:7117');
});

test('selectCookies keeps only wanted names, primary wins, skips empties', () => {
  const primary = [c('SID', 'p-sid'), c('OSID', 'osid', 'messages.google.com'),
    c('HSID', ''), c('RANDOM', 'x'), c('NID', 'nid')];
  const fallback = [c('SID', 'fallback-sid'), c('HSID', 'h'), c('SSID', 's'),
    c('APISID', 'a'), c('SAPISID', 'sa'), c('__Secure-3PSID', '3p')];
  const m = L.selectCookies([primary, fallback, null]);
  assert.deepEqual(m, { SID: 'p-sid', OSID: 'osid', NID: 'nid', HSID: 'h', SSID: 's',
    APISID: 'a', SAPISID: 'sa', '__Secure-3PSID': '3p' });
  assert.deepEqual(L.requiredStatus(m), { found: L.REQUIRED.slice(), missing: [] });
  assert.deepEqual(L.requiredStatus({ SID: 'x' }).missing, ['HSID', 'SSID', 'OSID', 'APISID', 'SAPISID']);
  assert.deepEqual(L.presentNames({ b: 1, a: 2 }), ['a', 'b']);
});

test('buildFormBody round-trips through URLSearchParams', () => {
  const m = { SID: 'a+b/c=d&e', '__Secure-1PSID': 'g.h%i', OSID: 'ü' };
  const body = L.buildFormBody(m);
  assert.match(body, /^cookies=/);
  assert.equal(body.includes('&'), false);
  assert.deepEqual(JSON.parse(new URLSearchParams(body).get('cookies')), m);
});

test('interpretResponse', () => {
  const page = (inner) => '<main>' + inner + '<form></form></main>';
  let r = L.interpretResponse(200, false, 'http://localhost:7117/admin/cookies',
    page('<p class="admin-ok" role="status">Saved: APISID, HSID, OSID</p>'));
  assert.deepEqual(r, { kind: 'saved', message: 'Saved: APISID, HSID, OSID' });

  r = L.interpretResponse(400, false, '',
    page('<p class="login-error" role="alert">Missing required cookies: OSID. Nothing was saved.</p>'));
  assert.equal(r.kind, 'error');
  assert.equal(r.message, 'Missing required cookies: OSID. Nothing was saved.');

  r = L.interpretResponse(400, false, '', page('<p class="login-error" role="alert">nothing pasted</p>'));
  assert.deepEqual(r, { kind: 'error', message: 'nothing pasted' });

  assert.equal(L.interpretResponse(401, false, '', '{"error":"login required"}').kind, 'need_login');
  assert.equal(L.interpretResponse(200, true, 'http://x/login?next=%2Fadmin%2Fcookies', '<form>').kind, 'need_login');
  assert.equal(L.interpretResponse(200, false, 'http://x/login', '').kind, 'need_login');

  r = L.interpretResponse(403, false, '', 'cross-origin request rejected\n');
  assert.equal(r.kind, 'error');
  assert.match(r.message, /cross-origin/);
  assert.equal(L.interpretResponse(500, false, '', 'boom').message, 'The app returned HTTP 500.');
  assert.equal(L.interpretResponse(200, false, '', '<html></html>').kind, 'unknown');
});

test('manifest is valid MV3 and references existing files', () => {
  const root = path.join(__dirname, '..');
  const mf = JSON.parse(fs.readFileSync(path.join(root, 'manifest.json'), 'utf8'));
  assert.equal(mf.manifest_version, 3);
  for (const p of ['cookies', 'storage']) assert.ok(mf.permissions.includes(p));
  assert.ok(mf.host_permissions.includes('https://messages.google.com/*'));
  // Non-Secure cookies (SID/HSID/APISID) are permission-checked against an
  // http:// URL, so an https-only google pattern silently drops them.
  assert.ok(mf.host_permissions.includes('https://*.google.com/*'));
  assert.ok(mf.host_permissions.includes('http://*.google.com/*'));
  assert.ok(mf.host_permissions.includes('http://localhost/*'));
  const files = [mf.action.default_popup, ...Object.values(mf.icons), ...Object.values(mf.action.default_icon)];
  for (const f of files) assert.ok(fs.existsSync(path.join(root, f)), 'missing ' + f);
  for (const [size, f] of Object.entries(mf.icons)) {
    const buf = fs.readFileSync(path.join(root, f));
    assert.equal(buf.toString('latin1', 1, 4), 'PNG');
    assert.equal(buf.readUInt32BE(16), Number(size));
    assert.equal(buf.readUInt32BE(20), Number(size));
  }
});

test('popup never logs or renders cookie values', () => {
  const src = fs.readFileSync(path.join(__dirname, '..', 'popup.js'), 'utf8');
  assert.equal(/console\.(log|info|warn|error|debug)/.test(src), false);
  assert.equal(/innerHTML/.test(src), false);
  assert.equal(/\.value\b/.test(src.replace(/els\.app\.value/g, '')), false);
});

test('selectCookies: domain query result incl. non-secure + OSID on several hosts', () => {
  // Mirrors what chrome.cookies.getAll({domain:'google.com'}) returns: the
  // non-Secure SID/HSID/APISID alongside Secure ones, and OSID on many hosts.
  const g = [
    { name: 'OSID', value: 'mail-osid', domain: 'mail.google.com', secure: true },
    { name: 'SID', value: 'sid', domain: '.google.com', secure: false },
    { name: 'HSID', value: 'hsid', domain: '.google.com', secure: false },
    { name: 'APISID', value: 'apisid', domain: '.google.com', secure: false },
    { name: 'SSID', value: 'ssid', domain: '.google.com', secure: true },
    { name: 'SAPISID', value: 'sapisid', domain: '.google.com', secure: true },
    { name: 'OSID', value: 'msg-osid', domain: 'messages.google.com', secure: true },
    { name: 'OSID', value: 'drive-osid', domain: 'drive.google.com', secure: true },
  ];
  const m = L.selectCookies([g]);
  assert.deepEqual(L.requiredStatus(m).missing, []);
  assert.equal(m.OSID, 'msg-osid'); // must be the messages.google.com one
  assert.equal(m.SID, 'sid');
  assert.equal(m.HSID, 'hsid');
  assert.equal(m.APISID, 'apisid');
});
