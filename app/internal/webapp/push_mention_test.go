package webapp

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func TestBuildNotificationMentionsMe(t *testing.T) {
	conv := PushConversation{ID: "c1", Name: "Test Group", IsGroup: true}
	for _, hide := range []bool{false, true} {
		n := buildNotification(conv, PushMessage{ID: "m1", SenderName: "Alex Example", Body: "hi \u00a0@Sam\u00a0", MentionsMe: true}, 1, hide, time.Unix(0, 0))
		b, _ := json.Marshal(n)
		if !strings.Contains(string(b), `"mentions_me":true`) {
			t.Fatalf("hide=%v: payload lacks mentions_me: %s", hide, b)
		}
	}
	n := buildNotification(conv, PushMessage{ID: "m2", SenderName: "Alex Example", Body: "hi"}, 1, false, time.Unix(0, 0))
	b, _ := json.Marshal(n)
	if strings.Contains(string(b), "mentions_me") {
		t.Fatalf("unexpected mentions_me: %s", b)
	}
}
