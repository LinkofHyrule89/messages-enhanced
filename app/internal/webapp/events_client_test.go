package webapp

import (
	"regexp"
	"strings"
	"testing"

	"github.com/maxghenis/openmessage/internal/web"
)

// Regression: the car page showed "Sending…" until you left and re-opened
// the conversation. /api/events sends *named* SSE events ("event: messages"),
// which EventSource never hands to onmessage, so the page has to subscribe to
// each name; and since quick-tunnel proxies can buffer the stream, it must
// poll when no event arrives and follow up on its own sends.
func TestCarPageHandlesNamedServerEvents(t *testing.T) {
	src, err := staticFS.ReadFile("static/app.js")
	if err != nil {
		t.Fatal(err)
	}
	js := string(src)
	m := regexp.MustCompile(`\[([^\]]*)\]\.forEach\(function \(t\) \{ es\.addEventListener\(t, onStreamEvent\); \}\)`).FindStringSubmatch(js)
	if m == nil {
		t.Fatal("app.js must subscribe to the named /api/events events with es.addEventListener")
	}
	for _, name := range []string{web.EventTypeMessages, web.EventTypeConversations, web.EventTypeTyping, web.EventTypeStatus, web.EventTypeHeartbeat} {
		if !strings.Contains(m[1], `"`+name+`"`) {
			t.Errorf("app.js doesn't listen for SSE event %q (got %s)", name, m[1])
		}
	}
	for _, want := range []string{"function pollIfStreamDead", "setInterval(pollIfStreamDead, POLL_MS)", "function followSend(", "followSend(convID);"} {
		if !strings.Contains(js, want) {
			t.Errorf("app.js is missing %q", want)
		}
	}
	// The conversation list follows new messages live: rows are patched from
	// sends / the open thread, re-fetched on SSE reconnect and on visible.
	for _, want := range []string{"function patchConv(", "applyConvPatches();", "if (reconnect) { loadConversations();", "rest.sort(byRecency)"} {
		if !strings.Contains(js, want) {
			t.Errorf("app.js is missing %q (live conversation list)", want)
		}
	}
	// Text and media sends share the optimistic path (postLocal), which
	// posts to both endpoints and follows up after either.
	for _, want := range []string{"function postLocal(", `"/api/send-media"`, `postJSON("/api/send", l.req)`, "renderCurrent(true);   // the bubble shows before any network call"} {
		if !strings.Contains(js, want) {
			t.Errorf("app.js is missing %q (optimistic sends)", want)
		}
	}
}
