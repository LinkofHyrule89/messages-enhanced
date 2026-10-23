# Changelog

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
