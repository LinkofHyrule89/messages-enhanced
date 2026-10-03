# Messages Enhanced (Android)

The Android app for a self-hosted [Messages Enhanced](../README.md) server. It
does two things:

1. **Opens your server's web app full screen** as a Trusted Web Activity (TWA):
   Chrome renders the page, with a splash in the theme color, web notifications
   shown as app notifications (notification delegation), and no URL bar.
2. **Sends Google cookies to the server** (a native cookie sender screen), which
   does the same job as the "Messages Enhanced Cookie Sender" Chrome extension.

- Application id `com.ubermicrostudios.messagesenhanced`, minSdk 26, targetSdk 36, compileSdk 37
- Kotlin, Jetpack Compose (Material 3), androidx.browser helper (TWA), OkHttp, security-crypto
- Adaptive icon (white chat glyph on `#3E6AE1`) with a monochrome layer for Android 13+ themed icons
- No analytics. The only traffic is your server and Google (inside the cookie sender's WebView).
- **No server is built in.** The APK is the same for everyone.

## Setup

1. Install the APK and open **Messages Enhanced**.
2. On first launch, type your server URL, for example `https://messages.example.com`.
   It must be `https://`. The app checks that it reaches a Messages Enhanced server
   (it reads `/app/manifest.webmanifest`, then `/app/`) before saving it.
3. The web app opens. Sign in with your server password as usual.

To change the server later, long-press the app icon and pick **Server**, or open the
app's settings from Android's app info page. Both open the **Server settings** screen.

## Cookie sender

Open it in any of these ways: long-press the app icon and pick **Google cookies**, use the
button on the Server settings screen, or tap **Send cookies from this phone** in the web app's
Settings > Account (shown only inside the Android app).

1. The server address is prefilled from setup. Enter the **Server password** and tap **Save settings**.
   The password is stored encrypted (Android Keystore) and never shown again.
2. Tap **Sign in to Google Messages** and sign in to Google. It's fine if Messages
   then shows the QR/pairing screen; wait for the page to finish loading.
3. Tap **Send to Messages Enhanced**. The app lists which required cookies it found
   (names only), logs in to the server, posts the cookies, and shows the result.

## No URL bar: Digital Asset Links

Chrome hides the URL bar only when the site vouches for the app at
`https://<your server>/.well-known/assetlinks.json`. The Messages Enhanced server
serves that file by default, with this app's package name and the SHA-256
fingerprint of the official release signing key built in. Nothing to configure
if you install the official APK.

If you build and sign the app yourself, set these on the server:

```
MESSAGES_ANDROID_CERT_SHA256=AA:BB:...   # your key's SHA-256; comma-separate several
MESSAGES_ANDROID_PACKAGE=com.example.yourfork   # only if you changed the application id
```

Get your fingerprint with `keytool -list -v -keystore your.keystore` or
`apksigner verify --print-certs app-release.apk`. If verification fails (for
example, the server sits behind something that rewrites `/.well-known/`), the
app still works but opens as a Chrome Custom Tab with a small URL bar.

## Build

```
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleRelease        # R8-minified, signed APK
```

Release signing reads `signing/local-signing.properties` (`storeFile`,
`storePassword`, `keyAlias`, `keyPassword`). The `signing/` folder is gitignored
and is not in this repo. Without it the release APK is unsigned. Create your own key with
`keytool -genkeypair -storetype PKCS12 -keyalg RSA -keysize 3072 -validity 10000 -alias yourkey -keystore signing/release.keystore`,
then set `MESSAGES_ANDROID_CERT_SHA256` on your server as above.

Optional integration test against a local server:
`TM_HARNESS_URL=http://127.0.0.1:7199 TM_HARNESS_SECRET=… ./gradlew testDebugUnitTest`
(skipped when unset).

## What the cookie sender sends

1. `POST <server>/login` form `secret=<password>` with `Origin: <server>` (same-origin form post).
   A 303 plus the `tm_session` cookie means it worked. A 401 means wrong password. A 429 means rate-limited.
2. `POST <server>/admin/cookies` form `cookies=<JSON {name: value}>`, same Origin.
   The HTML response is parsed like the extension's `interpretResponse`: `Saved: …`, or a `login-error` message, or 401 or a redirect to `/login`.

Cookies are read with `CookieManager.getCookie()` for `https://messages.google.com`,
`https://www.google.com` and `https://accounts.google.com`, from **this app's WebView only**.
`OSID` is taken only from messages.google.com. Required: `SID HSID SSID OSID APISID SAPISID`.
Optional (sent if present): `__Secure-1PSID __Secure-3PSID __Secure-1PSIDTS __Secure-3PSIDTS __Secure-1PAPISID __Secure-3PAPISID NID SIDCC`.

## Security notes

- Cookie values and the password are never logged, displayed, or written anywhere except the encrypted prefs (password only).
- App backup is disabled, so settings and WebView data are not backed up.
- `http://` is accepted only for LAN or loopback hosts (RFC1918, 127/8, 169.254/16, 100.64/10, localhost, `*.local`/`*.lan`, IPv6 ULA and link-local). Other hosts need `https://`. The network security config allows cleartext at the platform level because the user types an IP, which the XML can't express as a range. It forces google.com to https.
- The WebView only navigates to https URLs, no scripts are injected, and the user agent is the device's own Chrome UA without the `; wv` and `Version/4.0` WebView markers.
- "Sign out of Google in this app" clears only this app's WebView cookies and storage.

## Known limitation: Google may block sign-in in WebViews

Google sometimes refuses sign-in in embedded browsers ("This browser or app may
not be secure"). The app uses a normal Chrome UA to reduce this, and if Google
still redirects to its rejected page, the app shows a clear message. It does
not try to get around Google's protections. If that happens, use the Messages Enhanced
Cookie Sender Chrome extension on a computer.
