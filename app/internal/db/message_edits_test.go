package db

import (
	"path/filepath"
	"testing"
)

func TestDeletedPlaceholderAndEdits(t *testing.T) {
	s, err := New(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	m := &Message{MessageID: "m1", ConversationID: "c1", SenderName: "Test Person", Body: "synthetic hello", TimestampMS: 1, Status: "INCOMING_COMPLETE"}
	if err := s.UpsertMessage(m); err != nil {
		t.Fatal(err)
	}
	if err := s.MarkMessageEdited("m1", "c1"); err != nil {
		t.Fatal(err)
	}
	got, _ := s.GetMessageByID("m1")
	s.FillEdited([]*Message{got})
	if !got.Edited {
		t.Fatal("edited not filled")
	}
	if err := s.MarkMessageDeletedForEveryone("m1"); err != nil {
		t.Fatal(err)
	}
	got, _ = s.GetMessageByID("m1")
	if got == nil || got.Body != "" || got.Status != StatusMessageDeleted {
		t.Fatalf("placeholder wrong: %+v", got)
	}
	if IsEmptyStubMessage(got) {
		t.Fatal("deleted placeholder must not be a stub")
	}
}
