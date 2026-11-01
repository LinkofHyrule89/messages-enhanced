# Changelog

## android-v0.8.1

- In-app updates now come from this repository's Android releases (`android-v*`). A web release is not offered as an app update.
- versionCode 43.

## v0.8.0

- New package name `com.ubermicrostudios.messagesenhanced`. This installs as a fresh app, so uninstall the old app first. Data from `com.ubermicrostudios.messagesenhanced.nativeapp` does not carry over.

## v0.7.25

- Switching chats keeps photo, GIF, sticker, and link-preview rows the same height. The view is sized from the attachment's dimensions, or from the size saved after the first decode, and a neutral placeholder fills that box until the bitmap arrives.
- Cached pictures show immediately. A crossfade runs only for a real network download; a chat background already in memory is applied synchronously, so the wallpaper does not flash when you open the chat.
- Avatars and group icons show the cached photo immediately, then check with If-None-Match. The cached photo is replaced only when the server sends a new image.

## v0.7.24

- Conversation list: pinned-row pin icons stay in one column; timestamps use a fixed minimum width so labels like "Fri" and "12:00 AM" no longer shift the pin sideways.

## v0.7.23

- YouTube previews look like the web card: a 16:9 centre-cropped thumbnail with no black bars, the real video title and channel name, then the youtube.com footer. If the title failed to load, the app asks again instead of keeping "YouTube". Other preview types are unchanged.

## v0.7.22

- Link previews: YouTube and other video previews show a 16:9 centre-cropped thumbnail, so no black bars above and below. Web and Car Mode: the preview now sets the bubble width, so the image fills it edge to edge with no grey strip, and a YouTube title that failed to load is retried instead of staying as "YouTube".

## v0.7.21

- Big emoji: emoji-only messages now show at about the phone app size (64dp animated emoji, smaller large-text emoji) and take real layout space with normal spacing, so they no longer overlap the message above or below. Web, Car Mode and Android.

## v0.7.20

- Group chats: each sender name now uses that person's own color, as Google Messages does. The color is the one the phone assigns to the participant (sent with the participant data), so a name matches its avatar circle. If no color is sent, a stable pick from an 8-color palette by number or name is used. Names are adjusted for readability on dark and light themes. Applies to web, Car Mode and the Android app.

## v0.7.19

- Reply quotes match the phone app: the quote sets the bubble width, so a short reply no longer squeezes a one-line quote; long quotes use the full bubble width and clamp at 4 lines (line breaks kept). Darker inset block, regular-weight name, no accent bar.
- Group sender names are gold, as on the phone.
- Squeeze cause: the quote's width was set only on the quote block, which the bubble's wrap measurement ignored; the bubble itself now takes the quote's width.

## v0.7.18

- Fix in-app update: v0.7.17 shipped with the v0.7.16 version number (versionCode 32), so the updater correctly refused it as "not newer". Now versionCode 34 / 0.7.18. Includes all v0.7.17 link preview fixes.

## v0.7.17

- Link previews fill the bubble edge to edge: tall/portrait images and video posts (e.g. X) no longer show grey side bars; height follows the image, capped at ~60% of the screen (400dp), cropping minimally past the cap.
- Video link previews (YouTube, X videos) show a play button over the thumbnail.
- YouTube previews always show (server now uses YouTube oEmbed + thumbnail instead of the watch page).
- Long-press on a link preview opens the message menu (reactions, reply, copy, etc.); tap still opens the link.

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

- Contact cards (vCard): name, numbers and emails, tap to add to contacts.
- Calendar invites (.ics): title, time and place, tap to add to your calendar.
- Voice notes: play/pause with a waveform and elapsed/total time.
- Video and other files: tap to open in a matching app (previously "[attachment]").
- Stickers show without a bubble behind them.
- One-time codes get a "Copy code" chip; payment messages get an amount chip.
- System notices (missed calls, encryption changes, security/verification changes, scam warnings, group changes) show as centered notices instead of message bubbles.
- RCS business rich cards, carousels and suggested replies/actions render when they arrive.
- Older RCS location messages render as a location card.
- Read-receipt faces are bigger (20dp, with a thin ring and slight overlap), for groups and 1:1.
- No "[attachment]" label above link and location preview cards.
- Link preview images are never cropped: full image at its own shape, capped at about 60% of the screen (400dp).

## v0.7.13

- Shared locations render as a location card (pin, place, "Open in Maps").
- Notification Reply is more reliable: the reply waits for a network connection, retries with backoff without double-sending, and on final failure shows "Failed to send, tap to retry". A sent reply is added to the notification.
- Read receipts in one-to-one chats show the reader's photo (or initials) instead of two plain blue circles; group readers are deduplicated.
- Per-conversation drafts: text you type is saved automatically for that chat. Switching to another chat or group shows its own draft or an empty box, and coming back restores yours, mentions included. Drafts are kept only on this device (they survive restarts and never go to the server) and clear when you send or erase the text. Unsent photos and files are not kept in drafts.
- The chat list shows "Draft: ..." for chats with an unsent draft, like Google Messages.
- Sharing text into a chat that already has a draft adds the shared text on a new line instead of replacing the draft.

## v0.7.12

- Holiday and keyword animations: messages like "Happy birthday", "Happy New Year", "love you", "lol", "good morning", seasonal greetings and more play a short full-screen effect, and reactions play a small burst. New setting "Holiday animations" (on by default). Respects the system "Remove animations" setting.
- Animated emoji: a message that is a single emoji shows as a large animated Noto emoji; two or three emoji show large. New setting "Animated emojis" (on by default).
- Clean links: long-press the message box and choose "Clean links" to remove tracking parameters (utm_, ref, si and similar) from links before you send. The rule is an editable regular expression in Settings, with validation and Reset to default.
- New setting "Auto-clean shared links" (on by default): links in text shared into the app, including direct share shortcuts, are cleaned with the same rule before the message box is filled.
- Block, and Block & report spam, in the conversation menu for one-to-one texts.
- Tap the red "Failed" label on a message to try sending it again.
- Incoming multimedia messages that the phone has not downloaded show "Tap to download".
- Opening a photo asks the phone for the full-resolution original.
- The message box says "Text message" for SMS chats, like Google Messages.

## v0.7.11

- New setting "Show deleted message placeholders" (off by default, saved on this device). When it is on, a message its sender deleted for everyone shows as an italic "This message was deleted" bubble with the sender and time. The deleted text is never kept or shown. When it is off, deleted messages disappear as before.

## v0.7.10

- A message its sender deleted for everyone now stays in the thread as "This message was deleted" instead of disappearing.
- While your own delete-for-everyone is pending, the bubble says "Deleting for everyone…" instead of "Sending…".
- When a sender edits a message and the phone passes the change on, the bubble shows the latest text with an "Edited" label.

## v0.7.9

- The @mention picker now looks like Google Messages: a rounded dark card floating just above the composer, with a round contact photo (or initials) and "@Full Name" for each member. It filters as you type and closes when you tap outside it.

## v0.7.8

- When someone @mentions you, the notification now says so: "<Sender> mentioned you", with the group name and the message below it. If message text is hidden, it shows only "<Sender> mentioned you". Mentions use their own high-importance "Mentions" notification channel and keep the sender photo and group icon.
- Reply quotes inside a bubble size to their own content (the bubble grows up to its normal maximum width). The quoted sender name is never cut off, and the quoted text shows up to 3 lines before ending in an ellipsis.
- Tapping a reply quote scrolls to the original message and briefly highlights it. If the original is older than what's loaded, the app loads earlier history (with a cap) until it finds it, or shows "Message not available". Use the jump-to-newest button to go back.
- Message notifications are conversation-style: they're tied to the conversation's shortcut and show the contact photo or group icon (or an initials circle) as the large icon, never the app logo. The app appears only as the small status-bar glyph.

## v0.7.7

- @mentions are highlighted inside the message box as you type, in the same accent chip style as sent mentions. Backspace still trims the last name, then removes the mention.

## v0.7.6

- Share to Messages Enhanced: the app now appears in Android's share menu for text, photos, videos, and files (one or many). Sharing opens the Share screen (recent conversations, contacts, search) with the content ready. Picking one conversation opens its composer with everything attached. Nothing sends until you tap Send.
- Direct Share: recent conversations and groups appear at the top of the share menu with their photo or group icon (initials when there is no photo). Picking one opens that conversation's composer with the content attached. The list updates as conversations change.
- Shared files are copied off the main thread, so large videos and multiple images don't freeze the app.

## v0.7.5

- @mentions: mentions in received messages show as rounded chips, for both full names and first names, like Google Messages.
- Typing @ in a group chat opens a member picker that filters as you type. Picking a member inserts their full name. Backspace removes the last name first, then the whole mention.
- Notifications: group chats use the group icon, and each sender shows their contact photo (circle-cropped), falling back to initials. Group notification titles show just the group name.

## v0.7.4

- The "Disconnected from Google Messages" banner now waits for 45 seconds of continuous disconnection, so brief server restarts and network blips no longer flash it. It clears as soon as the connection returns. "Sign in needed" still shows right away.

## v0.7.3

- Link previews: when a preview card shows, the raw URL is hidden from the bubble (link-only messages show just the card). No preview, no change.
- Group chats: sender avatars now load the contact's photo (matched to the group member by number or name), and the fallback letter comes from the name instead of the phone number's leading "1".

## v0.7.2

- The go-to-newest button now shows whenever you are not at the bottom of a conversation (about two messages up), whether the newest messages are read, unread, or your own. The badge still counts only new incoming messages.

## v0.7.1

- Go to newest message: a round down-arrow button appears at the bottom-right of a conversation when you scroll up more than a screen. Tap it to smooth-scroll to the newest message (jumps instantly when far).
- The button shows an unread badge when new messages arrive while you are scrolled up, and incoming messages no longer pull you away from what you are reading.

## v0.7.0

- Reconnect Google Messages from Settings (or the red disconnected banner): sign in in the app, send cookies to your server, confirm the emoji on your phone.
- Cookie values are never stored or logged on the phone.
- Auto re-pair Google (default on): when the server has a saved session it reconnects on its own; confirm the emoji on your phone. Settings can turn this off or forget the saved session.
- Banner shows “Confirm on your phone” with the emoji, or asks you to sign in again when the saved session was rejected.
- Group chats: a reply still shows the sender's name and avatar above the bubble at the start of their run (like Google Messages).

## v0.6.9

- Fixed the red 'Can’t reach the server' message that could flash when returning to the app.
- Delivery and read checkmarks are larger and easier to see in the conversation list and in chats.

## v0.6.8

- Conversation photos are round again in the list, the chat header, group collages, read-by chips, photo-viewer replies, the share picker, group details, and notification icons.
- Coming back to the app, or after Android stops it, shows your chats right away from this phone. The list and the open conversation stay put while they refresh in the background.
- After an update, the first open shows what changed in that version.

## v0.6.7

- Changing the server address forgets the previous server's sign-in cookie, so this app does not stay signed in under the old account.
- Notification credentials are stored encrypted on this phone.
- Chat colors from the previous account are cleared when you sign out.
- An app update signed with a rotated key is accepted on Android 9 and newer when that update still includes this app's current signer.
- Each conversation keeps its own notification and reply action, even when many chats are notifying at once.

## v0.6.6

- Signing out, or saving a different server address, clears the chats and message notifications stored on this phone so the next sign-in does not show the previous account.
- An app update is installed only when the downloaded file is this app, signed with the same key, and a newer version. A download that stops early is discarded.
- Opening a photo no longer reads a file path that sits outside this app's cache.
- A notification reply stays tied to the conversation it was sent from.

## v0.6.5

- Replies in the photo viewer show the sender's photo, and fall back to colored initials when that photo can't be loaded.
- GIFs play in the photo viewer. Pinch, drag, and double-tap still zoom the animation.
- Forwarding a photo opens a Share screen like Google Messages: recent conversations, contacts from A to Z, a caption, and buttons to send to each selected chat or to one new group.
- The conversation list and chats you opened recently show up right away from this phone, then refresh in the background.
- Tablet: the conversation you had open comes back in the split view as soon as the app starts, from messages saved on this phone, then refreshes in the background.

## v0.6.4

- Tap a photo in a conversation to open it full screen and swipe through every photo in the chat, starting from the one you tapped, with the next photo peeking in at the edge. Each photo sits on a rounded card over a blurred copy of itself. Pinch, drag, or double-tap to zoom; while zoomed, dragging moves the photo instead of changing pages. Swipe down or go back to close. The top shows who sent it and how long ago, plus download, delete, and a menu with Forward, Share, and Details. React along the bottom, or open replies to read comments and add one. Download saves to Pictures/Messages Enhanced.
- Message details, from that photo menu or from a message's long-press menu, shows the message and when it was sent or received, plus the message type when the chat's protocol is known.
- Phone numbers are shown in your country's format wherever a name or number is displayed, such as (801) 555-1234 in the US. Emails, names, and short codes stay as written. Numbers that are sent, saved, or used to find a chat are unchanged.
- Group details shows your profile photo on your own row.
- Connection banner on the conversation list, on phones and tablets. When the VPN or Google Messages has been down for at least 30 seconds, it says so and shows the time it went down. If those checks are healthy, it shows pairing status: not paired, the phone not responding, or connecting / disconnected once that state has lasted 30 seconds. The banner hides while the app can't reach the server. A failed health or pairing check does not show an error or a toast.
- Long-press a conversation for its menu: group or contact details, refresh from your phone, pin or unpin, archive, and move to trash.
- Tablet: the open conversation's top bar rounds its top-right corner to mirror the list.
- Each chat has one notification listing the recent unread messages, with Reply and Mark as read. Reply sends without opening the app and adds your message to that notification. A failed reply posts “Couldn't send reply” and opens the chat when tapped. Mark as read clears that chat's notification. Unread chats are grouped, and the summary goes away when the last one is cleared. Opening a chat clears its notification too.

## v0.6.3

- Background download and install. Tapping Update downloads the APK in the background with a progress notification (Downloading %), then installs it, so you can leave the app. On Android 12+ the install is silent when the system allows it; otherwise the usual install prompt is shown, with a notification that has an Install action.
- The app checks for updates in the background every 12 hours. Auto-update downloads and installs. Otherwise it posts an Update available notification with an Update action, and tapping the notification opens the app.
- A failed download or install posts a notification with a Retry action.
- Fix: opening a conversation (including SMS) reliably marks it read; a background refresh could skip the read call. New incoming messages in an open, visible chat are marked read too.
- Fix: the conversation list no longer flashes the pull-to-refresh spinner on every background poll; it only shows for a manual pull, and it uses Material You colors.

## v0.6.2

- Tablet: the conversation list card rounds only its top-left corner.
- Silent updates (Android 12+): the updater installs new versions without a prompt, like Neko, and reopens the app. New Auto-update switch in Settings.
- Note: silent updates only work once this app itself installed the current version. The first update from a manually installed APK (for example 0.6.1) still shows the normal Android install prompt.

## v0.6.1

- Top app bar is taller (68dp) below the status bar instead of squeezing into it, with the title vertically centered.
- More breathing room in the conversation header.
- Tablet: the app bar is a lighter surface over both panes, and the list sits on a darker card with rounded top corners.
- Conversation list "You:" receipts use the web app's circled-check icons (sent, delivered, read).
- Bell button in the conversation header mutes or unmutes notifications (replaces the lock icon; stays in sync with the ⋮ menu).
- App name is now just "Messages Enhanced" (same package, updates install in place).

## v0.6.0

- In-app updater. The app checks GitHub Releases on launch (at most every six hours) and from Settings, then downloads the APK with progress, verifies the size and the sha256 digest when one is published, and installs it with the system package installer.
- The server address is entered on the login screen. No server URL is built into the app.
- Release builds are minified with R8. A tag workflow builds the APK and publishes a GitHub release.
- Conversation list with search, pins, unread state, archive, trash, and a way to start a chat.
- Threads with chat themes, wallpapers, reactions, replies, stars, forward, delete, link previews, media, emoji, and pasted images.
- Group details, read receipts, typing status, and a shared events stream that reconnects.
- Push notifications configured from the server at runtime.
- Tablet layout with a full-width app bar above the list and the thread.
- Settings for the connection, notifications, refresh, and @Grok / @Groq.
