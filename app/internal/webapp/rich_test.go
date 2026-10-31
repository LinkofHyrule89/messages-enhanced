package webapp

import (
	"os/exec"
	"strings"
	"testing"
)

// The rich-content parsers load before app.js and cover each content type.
func TestRichContentWired(t *testing.T) {
	idx, _ := staticFS.ReadFile("static/index.html")
	s := string(idx)
	r, a := strings.Index(s, `<script src="/app/rich.js"></script>`), strings.Index(s, `<script src="/app/app.js"></script>`)
	if r < 0 || r > a {
		t.Fatalf("rich.js must load before app.js (%d, %d)", r, a)
	}
	js, _ := staticFS.ReadFile("static/app.js")
	for _, want := range []string{"contactNode(m)", "eventNode(m)", "voiceNode(m)", "richCardNode(m)", "pushLocationNode(m)", "noticeRow(m)", "textChips(m)", "isSticker(m)"} {
		if !strings.Contains(string(js), want) {
			t.Errorf("app.js missing %s", want)
		}
	}
	if strings.Contains(string(js), "/^TOMBSTONE/") {
		t.Error("tombstone checks must match MESSAGE_STATUS_TOMBSTONE_* too")
	}
}

func TestRichParsersNode(t *testing.T) {
	if _, err := exec.LookPath("node"); err != nil {
		t.Skip("node not installed")
	}
	out, err := exec.Command("node", "testdata/rich_test.js").CombinedOutput()
	if err != nil {
		t.Fatalf("%v: %s", err, out)
	}
}
