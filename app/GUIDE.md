# Messages Enhanced (OpenMessage fork)

A car-friendly web client for Google Messages that runs in a car's built-in
browser. It's a thin layer (`internal/app/`) on top of
[OpenMessage](https://github.com/MaxGhenis/openmessage), which talks to Google
Messages through mautrix-gmessages' `libgm`.

What this adds to OpenMessage:

* **A car UI at `/app/`**: dark theme, 72–124 px touch targets, conversation
  list, thread view and compose box. Nothing depends on hover. It's built for
  ~1920×1200 landscape and falls back to one pane at a time on narrow screens.
  It's plain HTML/JS/CSS with no build step, embedded in the binary.
* **A mic button next to the compose box** with two engines, picked by
  `MESSAGES_STT_MODE` (default `auto`):
  * **Built-in speech first**: the browser's Web Speech API
    (`SpeechRecognition` / `webkitSpeechRecognition`) in continuous mode with
    interim results, so words appear live in the compose box. The language
    comes from `navigator.language` (default `en-US`). It keeps listening
    (restarting if the engine stops after a pause) until you tap **Done** or
    the cap is reached.
  * **Server fallback**: `getUserMedia` + `MediaRecorder`
    (`audio/webm;codecs=opus`, with fallbacks to webm, ogg/opus and mp4)
    records the clip and sends it to `POST /api/transcribe`, where a
    server-side STT provider transcribes it. In `auto` mode this path is used
    only when the Web Speech API is missing, throws on `start()`, never starts
    (5 s watchdog), or errors with `not-allowed`, `service-not-allowed`,
    `network` or `language-not-supported` before any words were heard. If the
    provider is `none`, the UI says speech isn't available on this browser.
    Other built-in errors (e.g. `audio-capture`) are shown as a message and
    don't fall back.
  * Either way the text is **inserted into the box without sending** (Send is
    disabled while listening). While listening you see a red pulsing button, a
    timer (auto-stops at `MESSAGES_STT_MAX_SECONDS`), **Cancel** (restores your
    draft) and **Done**. A small chip in the recording bar and the thread header
    shows which engine ran, e.g. `Chrome speech · en-US` or
    `Groq · built-in: not available`. The mic is released after every clip.
    Expect the car browser to take the server path: it's Qt WebEngine, which
    doesn't ship working speech recognition (see `../RESEARCH.md`). The
    `../mic-test/` page checks this in the car.
* **Login with a shared secret**: `/login` sets an HttpOnly, SameSite=Lax
  session cookie (an HMAC-signed expiry, 30 days by default, and Secure behind
  HTTPS). Every page and API needs it. Writes are CSRF-checked with
  Sec-Fetch-Site/Origin, and failed logins are rate-limited (5 per 5 minutes
  per IP).
* **Same origin**: the Go server serves the UI, the SSE event stream
  (`/api/events`) and every API, so the car only ever talks to this server and
  CORS never comes up.
* **A minimal pairing screen** (`/app/#pair`): it runs OpenMessage's existing
  `pair --google` flow (libgm `DoGaiaPairing`, then save `session.json`) from
  the server and shows the emoji Google picks, very large, as the Noto SVG from
  `libgm.GetEmojiSVG` with a text-glyph fallback, under "Tap this on your
  phone". There's an optional ntfy push (off by default).
* **Somewhere to put Google cookies**: the web page `/admin/cookies` or the CLI
  `messages-enhanced google-cookies`. Cookies are encrypted at rest (AES-256-GCM with a
  key scrypt-derived from `MESSAGES_SECRET`) in a 0600 file inside the 0700 data
  dir, outside git. Values are never logged or displayed; only cookie names
  are.

## Build and run

Requires Go ≥ 1.25 (the `go` command downloads it automatically if
`GOTOOLCHAIN=auto`).

```bash
go build -o messages-enhanced .

# Local dev with seeded fake conversations, fake STT, fake pairing emoji.
# Nothing contacts Google.
OPENMESSAGES_DATA_DIR=/tmp/tm-demo \
OPENMESSAGES_PORT=7117 \
MESSAGES_SECRET='local-dev-secret-please-change' \
MESSAGES_STT_PROVIDER=fake \
MESSAGES_DEV_FAKE_PAIRING='🦊' \
./messages-enhanced serve --demo
# open http://127.0.0.1:7117/  -> /login -> /app/
```

Real use (after pasting cookies and pairing, see below):

```bash
export OPENMESSAGES_DATA_DIR=$HOME/.local/share/messages-enhanced   # holds session + cookie vault; keep out of git/backups you don't trust
export MESSAGES_SECRET="$(openssl rand -base64 32)"                 # save it: it's the login and the vault key
export MESSAGES_STT_PROVIDER=openai OPENAI_API_KEY=sk-...           # or: groq + GROQ_API_KEY
./messages-enhanced serve --web
```

The web app switches on whenever `MESSAGES_SECRET` is set. Without it you get stock
OpenMessage (loopback-only UI at `/`).

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `MESSAGES_SECRET` | (unset = The web app off) | Login secret, ≥ 16 chars. It also derives the cookie-vault key and signs session cookies, so changing it logs everyone out and makes stored cookies unreadable (paste them again). |
| `MESSAGES_SESSION_DAYS` | `30` | Login cookie lifetime. |
| `MESSAGES_COOKIE_SECURE` | `auto` | `auto` marks the cookie Secure when the request came over TLS or `X-Forwarded-Proto: https`. `1` forces it on, `0` forces it off. |
| `MESSAGES_STT_MODE` | `auto` | `auto`: browser speech (Web Speech API), then server STT as a fallback. If browser speech fails for good (e.g. `network` in the car browser, no Google speech service), auto mode skips it on that device for 24 h and goes straight to server STT (Settings → Microphone & speech-to-text → Check again resets it). `builtin`: browser speech only (`/api/transcribe` returns 503). `server`: always record and use server STT. |
| `MESSAGES_STT_PROVIDER` | `none` | Server STT: `whisper` (local whisper.cpp, no key), `openai`, `groq`, `fake` or `none`. With `none` (and no browser speech) the mic button is dimmed and explains that speech isn't available. |
| `MESSAGES_WHISPER_URL` | `http://127.0.0.1:8178/inference` | For `whisper`: a whisper.cpp `whisper-server` started with `--convert` (needs `ffmpeg` on PATH, since browsers record webm/opus). Keep it on loopback. |
| `MESSAGES_WHISPER_MODEL` | `local` | For `whisper`: label only (shown as `whisper:<label>`), e.g. `base.en-q8_0`. The model is chosen by whisper-server's `-m`. |
| `MESSAGES_WHISPER_LIVE_URL` | (= `MESSAGES_WHISPER_URL`) | For `whisper`: whisper-server used for **live typing** passes (`POST /api/transcribe/partial`). The page sends 16 kHz mono PCM that the server wraps as WAV, so this instance doesn't need `--convert`; run it with `-ac 768` (≈15 s encoder context) for ~2× faster passes. |
| `MESSAGES_STT_LIVE` | on | `0` turns live typing off server-side (the page then records and transcribes on Done only). Live typing exists only for `whisper` (and `fake`); cloud providers would bill every overlapping window. |
| `OPENAI_API_KEY` | | Needed for `openai`. |
| `MESSAGES_OPENAI_MODEL` | `gpt-4o-mini-transcribe` | Or `whisper-1` / `gpt-4o-transcribe`. |
| `MESSAGES_OPENAI_BASE_URL` | `https://api.openai.com/v1` | Any OpenAI-compatible `/audio/transcriptions` server (e.g. a self-hosted whisper server). |
| `GROQ_API_KEY` | | Needed for `groq`. |
| `MESSAGES_GROQ_MODEL` | `whisper-large-v3-turbo` | Or `whisper-large-v3`. |
| `MESSAGES_STT_LANGUAGE` | (auto) | Server STT only: ISO-639-1 hint, e.g. `en`. Improves accuracy and speed. Browser speech uses `navigator.language`. |
| `MESSAGES_STT_PROMPT` | | Optional vocabulary hint (names you text often). |
| `MESSAGES_STT_MAX_SECONDS` | `60` | Client-side listening/recording cap (both engines). |
| `MESSAGES_STT_MAX_BYTES` | `10485760` | Server-side upload cap (opus at 32 kbps is about 4 KB/s). |
| `MESSAGES_FAKE_TRANSCRIPT` | | Canned text for the `fake` provider. |
| `MESSAGES_DEV_FAKE_PAIRING` | | **Dev only.** An emoji to show on the pairing screen instead of contacting Google. |
| `MESSAGES_NTFY_URL` | (off) | e.g. `https://ntfy.sh/<long-random-topic>`. When set, the pairing emoji is pushed there too. |
| `OPENMESSAGES_HOST` / `OPENMESSAGES_PORT` | `127.0.0.1` / `7007` | Listen address. Keep loopback and put a tunnel/reverse proxy in front. |
| `OPENMESSAGES_DATA_DIR` | `~/.local/share/openmessage` | Session, SQLite DB, cookie vault. |

## Pasting Google cookies and pairing

1. On a **desktop**, open a **private/incognito window** (Firefox is easiest;
   in Chrome, DBSC-bound cookies won't work). Sign in at
   `https://accounts.google.com/AccountChooser?continue=https://messages.google.com/web/config`.
   Don't browse anywhere else in that window.
2. Open devtools → Network, reload, then right-click the `config` request →
   **Copy as cURL**. You can also build a JSON object with `SID, HSID, SSID,
   OSID, APISID, SAPISID` (and `__Secure-1PSIDTS` if present).
3. Store the cookies in one of two ways:
   * Web: sign in to the app → **Cookies** → paste → **Save cookies**, or
   * CLI on the server: `MESSAGES_SECRET=... ./messages-enhanced google-cookies`
     (paste, then Ctrl-D). `--file path`, `--status` and `--clear` also work.
     Use the same `OPENMESSAGES_DATA_DIR` as the server.
   Nothing is saved unless all six required cookies are present.
4. Close the private window without signing out (signing out kills the
   cookies).
5. In the car (or anywhere), open **Pair phone** (or `/app/#pair`) and tap
   **Start pairing**. A big emoji appears. On the phone, Google Messages shows
   three emoji; tap the matching one. On success `session.json` is saved and
   the server reconnects by itself.
6. When Google later expires the cookies (the status pill shows "Not paired",
   or sends fail), paste fresh cookies the same way. The original
   `openmessage pair --google` CLI still works too.

Note: after pairing, libgm keeps its own copy of the cookies inside
`session.json`, because it rotates and persists them. That file is plaintext
(0600) in the data dir, as in upstream OpenMessage. Encrypt the disk or volume
the data dir lives on.

## API added

| Route | Auth | Purpose |
|---|---|---|
| `GET/POST /login`, `POST /logout` | public | Shared-secret login |
| `GET /healthz`, `GET /app/app.css` | public | Health check, login page styling |
| `GET /app/` | ✓ | Car UI |
| `POST /api/transcribe` | ✓ | Raw `audio/*` body, or multipart field `file`. Returns `{text, provider, label, ms}`. Never sends anything. 503 when the provider is `none` or `MESSAGES_STT_MODE=builtin` |
| `GET /api/app/config` | ✓ | `stt_mode`, `stt_enabled` (server STT usable), `stt_provider`, `stt_label`, recording limits |
| `GET /api/app/pairing` | ✓ | `{pairing:{state, emoji, emoji_svg, message}, cookies_saved, google:{…}}` |
| `POST /api/app/pairing/start` / `cancel` | ✓ | Start/stop Google-account pairing |
| `GET/POST /admin/cookies`, `POST /admin/cookies/clear` | ✓ | Cookie vault |
| everything else (`/api/*`, `/mcp/*`) | ✓ | Passed to OpenMessage in-process (see below) |

**Difference from upstream's security model:** OpenMessage only accepts
requests whose Host/Origin is loopback, because it's a localhost app. The web app
layer puts the login check and a same-origin write check in front, then hands
authenticated requests to OpenMessage with a loopback Host and no
Origin/Referer. OpenMessage's own control-token cookie is still in
"accept-and-log" mode, so expect one harmless "Local control request is not
authenticated" warning per client IP.

## Tests

```bash
go vet ./...
go test ./internal/app/     # auth, STT handler (fake + OpenAI-compatible request shape), cookie vault, pairing state
go test ./...                 # whole repo
```

Headless-browser walkthrough (the screenshots in `../screenshots/`): run the
demo server above, then
`cd ../shots-tool && SECRET=local-dev-secret-please-change node shots.js`
(playwright-core + the system Chrome, launched with a fake microphone). It
covers login, a wrong secret, the list, the thread, mic record → fake STT →
text in the box (verified: not sent), mic cancel, the pairing screen with the
dev emoji, the cookies page, and the narrow layout. It hides the Web Speech
API so it exercises the server path.

Mic engine checks: start four demo servers (ports 7117 auto/fake,
7118 auto/none with a 4 s cap, 7119 server/fake, 7120 builtin/fake), then
`node ../shots-tool/mic.js`. It injects a fake `SpeechRecognition` to test the
built-in path: live interim text, restart after the engine ends, Done, Cancel,
the cap, nothing sent, and the indicator. It also tests fallback on
`not-allowed`, `network`, a throwing `start()`, a missing API and an engine
that never starts, the `none`-provider message, and both forced modes.
Headless Chrome's real `webkitSpeechRecognition` exists but fails with
`audio-capture`.

## What's left for deployment

1. **In-car checks** (can't be done here): confirm the car is AMD Ryzen on
   2026.26+, check the mic permission prompt and which MIME type
   `MediaRecorder` picks (the UI tries webm/opus first), and confirm the car
   keyboard's dictation mic as a fallback for Intel cars.
2. **Hosting:** the car browser won't open LAN IPs and `getUserMedia` needs
   HTTPS, so the server needs a public HTTPS hostname. Recommended: an
   always-on home machine + Cloudflare Tunnel
   (`cloudflared tunnel --url http://127.0.0.1:7007`), optionally with
   Cloudflare Access in front. Or Fly.io/VPS with a persistent volume for the
   data dir. Keep `OPENMESSAGES_HOST=127.0.0.1` and terminate TLS in the
   tunnel/proxy. The proxy must not buffer `/api/events` (SSE).
3. **Secrets:** generate `MESSAGES_SECRET` (≥ 32 random chars) and the STT API
   key, and store them in the service manager's environment (systemd
   `EnvironmentFile` with mode 0600), not in the repo.
4. **Cookie capture and first pairing** as described above. Plan for occasional
   cookie refreshes.
5. **Optional:** set `MESSAGES_NTFY_URL` to a private topic; run it as a
   systemd/launchd service with restart-on-failure; back up the data dir
   (encrypted).
6. **Not done in this prototype:** media/attachments (shown only as
   "📎 type"), starting new conversations, reactions, search, a
   parked-only/driving lock, a "panic" button that unpairs and wipes (the
   stock `/api/unpair` exists but has no button), and WebAuthn/passkey login.
