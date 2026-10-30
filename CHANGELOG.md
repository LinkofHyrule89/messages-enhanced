# Changelog

## 2026-10-30

- **Go to newest message:** conversation threads show a round down-arrow button at the bottom-right when you scroll up more than a screen, with an unread badge for messages that arrive meanwhile. Tap to smooth-scroll to the newest message (jumps when far). Incoming messages never yank your scroll position. Car Mode keeps its large "New messages" pill.

- **Pairing serialization:** manual cookie pairing, QR pairing, and automatic Gaia re-pair share one gate. Auto re-pair skips while any pairing is in progress or just completed, and re-checks live Google status before starting. A new pair fully stops the old Google client/listeners first, persists new keys atomically, and reconnects once — so only one long-poll exists and HMAC mismatches from overlapping sessions stop.

- **Reconnect Google from the native app:** `POST /api/app/google/cookies` (authenticated, same CSRF rules as other `/api/app` writes) accepts a JSON object of Google cookies, stores them in the encrypted cookie vault (never logs values), and starts Google-account pairing. Status (including the confirmation emoji) is available from the existing `GET /api/app/pairing`. Body size capped at 64 KiB.

- **Group reply sender headers:** In group chats, a reply that starts a sender run still shows that person's name (and avatar) above the bubble, like Google Messages. Consecutive messages from the same person still hide the repeated header. Reply status does not change run grouping.

- **Automatic Google re-pair:** when Google auth expires and encrypted vault cookies exist, the server starts Gaia pairing on its own (15‑minute pacing), pushes “Confirm on your phone” with the emoji, and surfaces `sign_in_needed` when the saved session was rejected. Settings key `auto_google_repair` (default on). `POST /api/app/google/cookies/forget` clears the vault.

## 2026-10-23

- **Faster photos:** `/api/media` responses are cached for a year with an ETag, so repeat views return 304 instead of downloading again.
- **Faster lists:** new database indexes for the conversation list, drafts and scheduled messages.
- **Security hardening:** JSON request bodies capped at 1 MiB (uploads keep their own limit), login and OAuth forms capped before parsing, and constant-time secret checks that don't leak length.
- No API changes; the native app works unchanged.

## 2026-10-22

- **Group icons:** stale group pictures are fixed, a member's photo is never used as the group icon, end-to-end encrypted (MLS) RCS group icons now show like Google Messages for Web, and groups without an icon get a default avatar.
- **Read on open:** opening a chat marks it read on the phone and clears its notifications.
- **Refresh one conversation:** ⋮ → Refresh re-syncs a single chat from Google.
- **Native app support:** Firebase Cloud Messaging notifications for the native Android app (cleared when read), an app-ready signal, shared theme/appearance settings (`GET/PUT /api/app/settings`), and detection of the built-in Android viewer.
- **Phone-number formatting:** numbers are shown formatted for display, e.g. `(801) 555-1234`.
- **Car Mode image pop-up:** tap a photo to open it full screen with pinch zoom (up to 5×), double-tap zoom, pan, a big close button, and tap-outside / Esc to close.
- **README:** link to the native Android app repo and releases.
- Tests use synthetic IDs only.
