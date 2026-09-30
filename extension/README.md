# Tesla Messages Cookie Sender (Chrome extension, MV3)

Send your Google Messages sign-in cookies to **your own** Tesla Messages app in
one click, instead of copying them out of DevTools by hand.

## Install (Load unpacked)

1. Unzip `tesla-messages-extension.zip` (or use this folder as-is).
2. Open `chrome://extensions`, turn on **Developer mode** (top right).
3. Click **Load unpacked** and select the folder that contains `manifest.json`.
4. Optional: pin the extension (puzzle icon → pin) so the red chat icon is always visible.

Chrome 116 or newer is required. Edge, Brave and other Chromium browsers work the same way.

## Use it

1. In Chrome, open <https://messages.google.com/web> and sign in to Google
   normally. It's fine if Messages then shows the QR / pairing screen.
   (The `OSID` cookie only exists after you visit messages.google.com.)
2. In another tab, open your Tesla Messages app (default
   `http://localhost:7117`) and **sign in to the app** with your
   `TESLA_SECRET`. The extension uses that login session.
3. Click the extension icon. Check the **App address**, then click
   **Send cookies to Tesla Messages**.
4. The popup shows which required cookies were found (✓/✗, **names only**) and
   the app's answer:
   - `Saved: APISID, HSID, …`: done. Go to the app and pair.
   - `Missing: OSID …`: open messages.google.com/web (button provided), sign in, retry.
   - `Sign in to the app first`: a button opens `<app>/login`. Sign in, then send again.
   - `Missing required cookies: …` / other text: the app's own error message.

The address is saved in `chrome.storage.local`. For an address that isn't
localhost/127.0.0.1, Chrome asks once for permission to reach that host.

### What gets sent

`POST <app>/admin/cookies`, `Content-Type: application/x-www-form-urlencoded`,
body `cookies=<JSON {"SID":"…",…}>`, with the app's session cookie included.

- Required: `SID HSID SSID OSID APISID SAPISID`
- Included if present: `__Secure-1PSID __Secure-3PSID __Secure-1PSIDTS __Secure-3PSIDTS __Secure-1PAPISID __Secure-3PAPISID NID SIDCC`

Cookies are read with `chrome.cookies.getAll({url: "https://messages.google.com/"})`
(that includes the host-only `OSID`). `https://www.google.com/` is queried only
as a fallback for anything still missing. The cookie store of the current window
is used, so an incognito window (if you allow the extension there) sends its
incognito session.

## Privacy

- Cookie **values** are never displayed, logged, or stored by the extension.
  They go into one HTTPS/HTTP request to the app address you entered, and nowhere else.
- The only thing stored is the app address.
- No analytics, no remote code, no third-party servers.
- These cookies are equivalent to your Google login. Only point this at an app
  you run yourself, preferably over HTTPS or on localhost. Signing out of Google
  in that browser ("Sign out of all accounts") invalidates them.
- Tip: use a separate Chrome profile or an incognito window for the Google
  account you pair, so signing out of your main browser doesn't break pairing.

## Server compatibility (CSRF check)

The app's `sameOriginWrite` check (internal/tesla/auth.go) accepts
`Sec-Fetch-Site: none`, which Chrome sends on extension fetches **to hosts the
extension has permission for** (and extension fetches with host permission carry
SameSite=Lax cookies such as `tm_session`). That works with no server change when the app is at:

- `http://localhost:…` / `http://127.0.0.1:…` (built-in permission), or
- any `https://…` address (permission granted from the popup).

**Plain-http non-local addresses** (e.g. `http://192.168.1.20:7117`, a
Tailscale IP over http) are not "potentially trustworthy", so Chrome sends no
`Sec-Fetch-Site`, only `Origin: chrome-extension://<id>`. The app then answers
`403 cross-origin request rejected`, and the popup reports exactly that. Fix
this by using HTTPS (recommended, because the cookies are secrets), or with this
small server change in `sameOriginWrite`, placed before the host comparison:

```go
// Allow this extension's popup (Chrome sends no Sec-Fetch-Site over plain http).
// TESLA_EXTENSION_ORIGINS: comma-separated, e.g. "chrome-extension://abcdefghijklmnopabcdefghijklmnop"
for _, o := range strings.Split(os.Getenv("TESLA_EXTENSION_ORIGINS"), ",") {
    if o = strings.TrimSpace(o); o != "" && origin == o {
        return true
    }
}
```

The extension ID is shown on `chrome://extensions`. An unpacked extension's ID
depends on its folder path, so add a `"key"` to the manifest if you need it to
stay the same. This keeps the CSRF protection intact: web pages can't forge a
`chrome-extension://` Origin.

## Development

```
python3 scripts/make_icons.py   # regenerate icons/icon{16,32,48,128}.png (stdlib only)
node --test tests/              # unit tests for lib.js + manifest/icon checks
```

`lib.js` holds the pure logic (cookie selection, form body, response parsing)
and `popup.js` does the chrome.* and DOM wiring.

## Chrome Web Store notes

This is built to be loaded unpacked. To publish:

- **Permission justification.** `cookies` plus host access to `*.google.com`
  are sensitive, so expect an in-depth review. Single purpose: "transfer the
  user's own Google Messages session to their self-hosted server at the user's
  request". `http://localhost/*` / `127.0.0.1` are for the default local app.
  `optional_host_permissions` (`http(s)://*/*`) are requested at runtime only
  for the address the user types.
- **Privacy policy.** Required because the extension handles authentication
  information. State that values are sent only to the user-entered address,
  never to the developer, and are not stored.
- **Data-use disclosures.** Tick "Authentication information", and certify no sale or transfer to third parties.
- **Policy risk.** Extensions that export Google session cookies may be
  rejected or flagged, even for self-hosting. Unlisted or private distribution,
  or keeping it as Load unpacked, is the low-risk path.
- Upload a zip with `manifest.json` at its root. Bump `version` for every upload.
  Store assets: 128×128 icon (included), at least one 1280×800 screenshot, and a 440×280 promo tile.
