# Messages Enhanced Native

A native Android client for a self-hosted Messages Enhanced / OpenMessage server. It uses the server’s HTTP API for sign-in, conversations, threads, media, live events, and push. The UI is Kotlin with Views, RecyclerView, Hilt, Retrofit, OkHttp, and Material 3.

Application id `com.ubermicrostudios.messagesenhanced`. Minimum SDK 26. Debug builds add the application id suffix `.debug`.

## Features

- Sign in with the server URL and secret. The URL is entered on the login screen and stored on the device. There is no built-in server address.
- Conversation list with search, pins, unread badges, archive, trash, and pull to refresh.
- Start a chat from the server’s contacts.
- Threads with chat themes, wallpapers, reactions, swipe-to-reply, stars, forward, delete, link previews, and media.
- Composer with emoji, camera, gallery, files, and images pasted from the clipboard.
- Group details and read receipts.
- Live updates on one server-sent events stream, with reconnect.
- Notifications through Firebase Cloud Messaging. The server sends the Firebase options at runtime, so this project does not use a `google-services.json` file.
- Tablet layout with the list and the open thread side by side.
- Settings for the server URL, notifications, refresh, and @Grok / @Groq.
- In-app updates from GitHub Releases.

## Companion server

This app is a client only. It needs a running [Messages Enhanced](https://github.com/LinkofHyrule89/messages-enhanced) server, the self-hosted Go server and web app that pairs with Google Messages. Set up that server first, then sign in here with its URL and secret. The native app's features track the server's API, so keep the server reasonably up to date.

## Requirements

- Android 8.0 or newer
- JDK 21
- Android SDK, compile SDK 37
- A Messages Enhanced / OpenMessage server you can reach from the device

## Build

Debug build and unit tests:

```
./gradlew assembleDebug testDebugUnitTest
```

Release builds are minified and resource-shrunk with R8. Signing is applied only when `keystore.properties` exists in the project root (the file is gitignored). Format:

```
storeFile=keys/release.jks
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

`storeFile` is a path relative to the project root. Then:

```
./gradlew assembleRelease
```

The APK is `app/build/outputs/apk/release/app-release.apk`.

Push is configured from `GET /api/app/push/config` after sign-in. The Android Gradle build does not apply the Google services plugin and does not need `google-services.json`.

## Updates

The app reads the latest GitHub release for the repository in the `GITHUB_OWNER` and `GITHUB_REPO` BuildConfig fields. On launch it checks at most every six hours. Settings has **Check for updates**, which reports “You’re up to date” when nothing newer is published.

A newer `vX.Y.Z` tag with an `.apk` asset opens a dialog with the version and the release notes. Update downloads the APK into the app cache, checks the asset size, and checks the `sha256:` digest when GitHub includes one. A mismatch deletes the file and shows an error. Install uses a `PackageInstaller` session. Android will ask you to allow installs from this app the first time.

Tag pushes (`v*`) are built and published by `.github/workflows/release.yml`. The workflow expects these repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`.

## Attribution

Animated emoji are [Noto Animated Emoji](https://googlefonts.github.io/noto-emoji-animation/) by Google, licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/legal-code), loaded at runtime from fonts.gstatic.com and not modified or bundled. Holiday animations are original code.
