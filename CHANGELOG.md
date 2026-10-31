# Changelog

## v0.7.21

- Web and Car Mode link previews now match the Android card: the preview is the bubble, image flush and full-width (cropped, never letterboxed, tall images capped), play overlay on video/YouTube, and native title, description and site footer sizes, spacing and colors.
- Big emoji: emoji-only messages now show at about the phone app size (64dp animated emoji, smaller large-text emoji) and take real layout space with normal spacing, so they no longer overlap the message above or below. Web, Car Mode and Android.

## v0.7.20

- Group chats: each sender name now uses that person's own color, as Google Messages does. The color is the one the phone assigns to the participant (sent with the participant data), so a name matches its avatar circle. If no color is sent, a stable pick from an 8-color palette by number or name is used. Names are adjusted for readability on dark and light themes. Applies to web, Car Mode and the Android app.

## v0.7.19

- Reply quotes match the phone app: the quote sets the bubble width, so a short reply no longer squeezes a one-line quote; long quotes use the full bubble width and clamp at 4 lines (line breaks kept). Darker inset block, regular-weight name, no accent bar.
- Group sender names are gold, as on the phone.

## v0.7.17

- Link previews fill the bubble width with no grey side bars for tall/portrait images and video posts (X); height follows the image up to ~60% of the screen (400px), minimal crop past that.
- Video previews (YouTube, X videos) show a play button; server sends is_video and image size.
- YouTube previews (watch, youtu.be, shorts, live) now come from YouTube oEmbed + i.ytimg thumbnail, so they no longer vanish when the watch page is huge or behind a consent/bot page. Page read limit raised to 3 MB.
- Long-press on a link preview opens the message menu instead of the browser link menu.

## v0.7.16

- Reply quotes size to their own content (one line, or up to 3 lines when long) and the bubble widens to fit; short replies like "Oh nice" no longer squeeze the quote (v0.7.8 rule restored).
- Quote styling per the phone app: 8dp corners, 6dp inset, 12sp name and text, darker fill.
- Photos and link previews sit flush with the bubble edges, clipped to its corners, with no inner frame; the link preview is the bubble itself (text above, image, title, description, favicon + domain).
- Bubble corners 20dp / 4dp per the phone app.

## v0.7.15

- Google Messages look: photos run edge to edge at the top of the bubble with rounded corners and the caption below.
- Reply quotes are a full-width darker rounded block inside the bubble (name + text), tap still jumps to the original.
- Link previews fill the bubble: large uncropped image, bold title, description, and a favicon + domain footer (no uppercase site label).

## v0.7.14

- Contact (vCard) cards with call links and a contact.vcf download; calendar (.ics) cards with "Add to calendar" and invite.ics download.
- Voice notes play with a waveform, seek and 1x/1.5x/2x speed; other files show a download card.
- Stickers without a bubble; one-time code "Copy code" and payment amount chips.
- System notices (missed calls, encryption/security changes, verification, scam warnings) render as centered notices, including MESSAGE_STATUS_TOMBSTONE_* statuses.
- RCS rich cards, carousels and suggested replies/actions render when they arrive.
- No "[attachment]" label above link and location preview cards.
- Link preview images are never cropped: full image, proportional height, capped at about 60% of the screen (400px), including Car Mode.
- Better MIME detection for attachments, and older RCS location pushes become location messages.

## v0.7.13

- Shared locations from Google Messages are now received (they used to arrive empty) and render as a location card with the place text, opening Maps on tap. A sent location no longer stays stuck on the pending clock: status updates for already-stored contentless messages are applied.
- Read receipts in one-to-one chats show the reader's photo (or initials) instead of two plain blue circles; group readers are deduplicated.
- Per-conversation drafts in the web app and Car Mode: typed text is saved automatically for each chat in this browser only (localStorage, never synced). Switching chats shows that chat's own draft or an empty box; coming back restores it, mentions included. Drafts clear on send or when erased.
- The chat list shows "Draft: ..." for chats with an unsent draft.

## v0.7.12

- Holiday and keyword animations in the web app (subtle in Car Mode), plus reaction bursts. Setting "Holiday animations" (on by default); honours reduced-motion.
- Animated Noto emoji for emoji-only messages. Setting "Animated emojis" (on by default).
- Clean links: a "Clean links" chip appears in the composer when a link is present and removes tracking parameters. The rule is an editable regular expression in Settings, with validation and Reset to default.
- Block, Block & report spam, and Unblock in the conversation menu.
- Tap a failed message to resend it; "Tap to download" for MMS the phone has not downloaded; opening an image requests the full-resolution original.
- Bubble corners and the RCS/Text message composer label now match Google Messages.
- Archive, spam and trash folder sync fix.


## v0.7.11 - Deleted messages

- New setting "Show deleted message placeholders" (off by default). When it is on, a message its sender deleted for everyone stays in the thread as an italic "This message was deleted" bubble with the sender and time. The deleted text is wiped on the server and never shown.
- While your own delete-for-everyone is pending, the bubble says "Deleting for everyone…".

## Unreleased - Mention picker restyle

- The @mention picker is now a rounded dark card above the composer with a round photo (or initials) and "@Full Name" per member, with no header. It works in Car Mode too, and Esc or tapping outside closes it.

## Unreleased - Mention pushes, reply quotes

- Push notifications now carry `mentions_me`, so the Android app can show "<Sender> mentioned you".
- Reply quotes inside bubbles size to their own content: the sender name is never cut off, and the quoted text shows up to 3 lines (including Car Mode).
- Tapping a reply quote scrolls to the original and flashes it. If the original isn't loaded, older history is loaded (capped) until it's found, or "Message not available" is shown.

## Unreleased - Composer mention highlight

- Valid @mentions in the message box (including Car Mode) are highlighted as you type, in the same accent chip style as sent mentions.

## Unreleased - @mentions

- Mentions in received messages ('@Full Name' or '@First') show as rounded chips, like Google Messages. Google sends mentions as plain text wrapped in non-breaking spaces.
- Typing @ in a group chat opens a member picker that filters as you type (arrows, Enter or Tab, Esc). It inserts the full name. Backspace removes the last name first, then the whole mention. Car Mode gets larger picker rows.
- The server marks Google messages that mention you (full or first name), so the "Mentions only" notification setting now works for Google chats.
- App push data now carries the sender name, number, and group flag (left out when text is hidden), so the Android app can show sender photos and the group icon.

## Unreleased - Disconnected grace

- The "Google Messages is disconnected" banner and the Disconnected status dot now need 45 seconds of continuous disconnection (was 30 s for the banner, 25 s for the dot), so server restarts and VPN blips no longer flash them. Phone offline stays at 25 s.
- A connected pairing poll (every 15 s) clears a stale Google-down banner at once instead of waiting for the 60 s health poll.

## 2026-10-30

- **Link previews hide the raw URL:** when a message shows a preview card, the previewed link is removed from the bubble text (a link-only message shows just the card). No card, no change.

- **Fewer false connection errors:** background load failures now toast only after 3+ failures spanning 25 s while the tab is visible, at most once a minute, and never within 20 s of the tab regaining focus. The header dot waits 25 s before switching to Disconnected / Phone offline (recovery is instant). Server health banner keeps its 30 s rule.

- **Go-to-newest button:** now a 48px high-contrast round button (64px in Car Mode) that sits 16px above the composer and follows it as it grows, above other layers. It replaces the old "New messages" pill; its badge shows the new-message count.

- **Go to newest, sooner:** the button now shows whenever you are not at the bottom of a thread (about two messages up), whether the newest messages are read, unread, or your own. The badge still counts only new incoming messages.

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
