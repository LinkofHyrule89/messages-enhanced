# Tesla Messages

A Google Messages client made for a car screen. It runs in the Tesla in-car
browser and is self-hosted: the server runs on your own computer, and the car
(or any browser) connects to it.

It's a fork of [OpenMessage](https://github.com/MaxGhenis/openmessage) by Max
Ghenis, which talks to Google Messages through mautrix-gmessages' `libgm`.
Many thanks to that project, and to the mautrix-gmessages authors.

## What's here

- **`app/`**: the Go program (server and car page are one binary). The car
  page is at `/tesla/` and adds:
  - a password-protected page (shared secret, session cookie)
  - large, dark, touch-friendly layout for a car screen
  - voice-to-text (browser speech, with a server speech-to-text fallback)
  - contact photos and Google group icons
  - inline photos with a full-screen viewer, inline videos, link previews
  - replies, delete, read status, typing indicator, start a new chat
  - a pairing screen and a place to paste Google sign-in cookies
  See [`app/TESLA.md`](app/TESLA.md) for details.
- **`extension/`**: "Tesla Messages Cookie Sender", a small Chrome extension
  that sends your Google Messages sign-in cookies to your own Tesla Messages
  server in one click. See [`extension/README.md`](extension/README.md).

## Build and run

Requires Go 1.25 or newer.

```bash
cd app
go build -o tesla-messages .
./tesla-messages serve --web
```

Then open `http://<host>:<port>/tesla/` and sign in with your secret.
Keep the server on loopback and put a tunnel or reverse proxy in front of it
if the car needs to reach it over the internet.

Configuration is by environment variable (set your own values; none are
included here):

| Variable | What it's for |
|---|---|
| `OPENMESSAGES_DATA_DIR` | Where the session, database and cookie vault live. |
| `OPENMESSAGES_PORT` | Port to listen on. |
| `OPENMESSAGES_HOST` | Address to listen on. |
| `TESLA_SECRET` | Login secret for the car page (turns the car page on). |
| `TESLA_STT_MODE` | Voice-to-text engine choice (browser, server, or auto). |
| `TESLA_STT_PROVIDER` | Server speech-to-text provider. |

More options are documented in [`app/TESLA.md`](app/TESLA.md).

## License

OpenMessage is released under the Unlicense (public domain); see
[`app/LICENSE`](app/LICENSE).
