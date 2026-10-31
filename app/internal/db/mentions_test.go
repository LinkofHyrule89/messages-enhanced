package db

import "testing"

func TestMentionsName(t *testing.T) {
	n := "Alex Example"
	cases := map[string]bool{
		"hey \u00a0@Alex Example\u00a0 you in?": true,
		"\u00a0@Alex\u00a0 ready": true,
		"@alex!": true,
		"\u00a0@Alexandra\u00a0 hi": false,
		"mail alex@example.com": false,
		"no mention": false,
		"\u00a0@Sam Other\u00a0": false,
	}
	for body, want := range cases {
		if got := MentionsName(body, n); got != want {
			t.Errorf("%q: got %v want %v", body, got, want)
		}
	}
}
