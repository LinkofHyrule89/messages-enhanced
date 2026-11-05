package webapp

import (
	"os"
	"strings"
	"testing"
)

func TestReadReceiptSingleAvatar(t *testing.T) {
	b, err := os.ReadFile("static/app.js")
	if err != nil {
		t.Fatal(err)
	}
	s := string(b)
	for _, want := range []string{"function dedupeReaders(", "dedupeReaders(readByNames(m))", `"read-one"`} {
		if !strings.Contains(s, want) {
			t.Errorf("app.js missing %q", want)
		}
	}
}

func TestReadByAllIsNotAnAvatar(t *testing.T) {
	b, err := os.ReadFile("static/app.js")
	if err != nil {
		t.Fatal(err)
	}
	s := string(b)
	for _, want := range []string{
		`if (/^(all|everyone)$/i.test(r[1].trim())) return ["__all__"];`,
		`names[0] === "__all__"`,
		`if (everyoneRead) names = [];`,
		`statusIcon(everyoneRead || (names && names.length) ? "read" : kind)`,
	} {
		if !strings.Contains(s, want) {
			t.Errorf("app.js missing %q", want)
		}
	}
	// The sentinel must not be passed to senderAvatar as a display name.
	if strings.Contains(s, `SenderName: "__all__"`) || strings.Contains(s, `SenderName:"__all__"`) {
		t.Fatal(`"__all__" is drawn as an avatar`)
	}
}

func TestDeskMentionLayerDoesNotEatClicks(t *testing.T) {
	b, err := os.ReadFile("static/app.css")
	if err != nil {
		t.Fatal(err)
	}
	s := string(b)
	enable := "html.desk .thread-view > .compose > *, html.desk .thread-view > .quick-replies > * { pointer-events: auto; }"
	block := "html.desk .thread-view > .compose > .compose-mention-layer { pointer-events: none; }"
	i, j := strings.Index(s, enable), strings.Index(s, block)
	if i < 0 || j < 0 || j < i {
		t.Fatalf("mention layer must stay pointer-events:none after the desk compose rule (enable=%d block=%d)", i, j)
	}
}

func TestLocationCardWired(t *testing.T) {
	b, _ := os.ReadFile("static/app.js")
	for _, want := range []string{"function locationCard(", "locationInfo(text)", "Open in Maps"} {
		if !strings.Contains(string(b), want) {
			t.Errorf("missing %q", want)
		}
	}
}
