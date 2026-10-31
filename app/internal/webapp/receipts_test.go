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
