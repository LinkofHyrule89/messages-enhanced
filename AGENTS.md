# AGENTS.md

Messages Enhanced is a monorepo. Run commands from the directory that owns them.

## Layout

- `app/` — Go server and web client (module `github.com/maxghenis/openmessage`). Build, test, and e2e from `app/`. Web release tags are `vX.Y.Z`.
- `android/` — native Android client, application id `com.ubermicrostudios.messagesenhanced`. Gradle commands run from `android/`. Release tags are `android-vX.Y.Z` (versionName `0.8.1` is tag `android-v0.8.1`).
- `extension/` — Chrome extension that sends Google cookies to the user's own server.
- `.github/workflows/` — web workflows (`test.yml`, `release.yml`, `gmessages-fork-drift.yml`) and Android workflows (`android-ci.yml`, `android-release.yml`).

## Releases

Android in-app updates list `LinkofHyrule89/messages-enhanced` releases and keep only `android-v*` tags. Web tags are ignored even when the version number is higher. GitHub's `/releases/latest` must stay on the newest Android release: `android-release.yml` sets `make_latest: true`, and the web release workflow creates releases with `--latest=false`.

`messages-enhanced-native` is the previous home of the Android app. It is archived. Its final `v0.8.1` release exists so installs that still check that repository can update once.

## Secrets and private data

Do not print, log, or commit keystores, signing passwords, API keys, cookies, message exports, screenshots of real chats, or decompiled APKs. Android release signing uses the repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`. `keystore.properties` and `*.jks` stay gitignored.
