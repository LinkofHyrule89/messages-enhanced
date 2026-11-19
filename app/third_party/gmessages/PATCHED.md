# Vendored, patched gmessages

Copy of `github.com/MaxGhenis/gmessages` at `v0.2602.1-0.20260703132304-0e43542dfa0e`
(the fork's auth-refresh network
retry fix included), `pkg/libgm` only (`cmd/` and `pkg/connector` removed;
the app doesn't use them). License: AGPL-3.0 (see LICENSE).

Messages Enhanced patches:
- `pkg/libgm/longpoll.go` (marked "Messages Enhanced patch"):
  - `closeLongPolling` always stops the listen loop, so `Disconnect()` works
    while the network is down (it used to leak a retry loop + pinger per
    reconnect, flooding "Error sending ping" several times a second).
  - Long-poll retry sleeps: exponential 10s..5m instead of linear, and they
    wake as soon as the loop is stopped.
  - Ditto pinger: after send failures, back off 30s..10m between pings.
- `pkg/libgm/session_handler.go`: RPC replies are waited on for at most 20s.
  The HTTP call has already been accepted by then. `SendMessage` reports
  success on that timeout so a stalled update stream is not a failed send
  and is not retried.
- `pkg/libgm/client.go`: Google RPCs dial IPv4 before IPv6.
- `pkg/libgm/event_handler.go`: a duplicate message or conversation id inside
  one update batch no longer drops the rest of the batch.
