package db

import "testing"

func TestMessageStatusText(t *testing.T) {
	s := newTestStore(t)
	if err := s.SetMessageStatusText("m1", "c1", " Read by Alice, Bob "); err != nil {
		t.Fatal(err)
	}
	msgs := []*Message{{MessageID: "m1", IsFromMe: true}, {MessageID: "m2", IsFromMe: true}, {MessageID: "m1x"}}
	s.FillStatusText(msgs)
	if msgs[0].StatusText != "Read by Alice, Bob" || msgs[1].StatusText != "" {
		t.Fatalf("got %q %q", msgs[0].StatusText, msgs[1].StatusText)
	}
	_ = s.SetMessageStatusText("m1", "c1", "")
	msgs[0].StatusText = ""
	s.FillStatusText(msgs)
	if msgs[0].StatusText != "" {
		t.Fatalf("not cleared: %q", msgs[0].StatusText)
	}
}
