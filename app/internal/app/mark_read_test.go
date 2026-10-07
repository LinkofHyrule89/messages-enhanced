package app

import (
	"testing"

	"github.com/maxghenis/openmessage/internal/db"
)

func TestMarkReadOnGoogleLatestMessageAndDedupe(t *testing.T) {
	mock := &mockGMClient{}
	a := newTestApp(t, mock)
	if err := a.Store.UpsertConversation(&db.Conversation{ConversationID: "c1", SourcePlatform: "sms", UnreadCount: 1}); err != nil {
		t.Fatal(err)
	}
	for _, m := range []*db.Message{
		{MessageID: "m1", ConversationID: "c1", TimestampMS: 1000, Status: "INCOMING_COMPLETE", Body: "x", SourcePlatform: "sms"},
		{MessageID: "m2", ConversationID: "c1", TimestampMS: 2000, Status: "INCOMING_COMPLETE", Body: "y", SourcePlatform: "sms"},
		{MessageID: "t3", ConversationID: "c1", TimestampMS: 3000, Status: "MESSAGE_STATUS_TOMBSTONE_GROUP_ICON_CHANGED_GLOBAL", SourcePlatform: "sms"},
		{MessageID: "tmp_4", ConversationID: "c1", TimestampMS: 4000, Status: "OUTGOING_SENDING", Body: "z", IsFromMe: true, SourcePlatform: "sms"},
	} {
		if err := a.Store.UpsertMessage(m); err != nil {
			t.Fatal(err)
		}
	}
	if scope, err := a.MarkReadOnGoogle("c1"); err != nil || scope != "google" {
		t.Fatalf("scope=%q err=%v", scope, err)
	}
	if scope, _ := a.MarkReadOnGoogle("c1"); scope != "google" { // deduped
		t.Fatalf("second scope=%q", scope)
	}
	mock.mu.Lock()
	calls := append([][2]string{}, mock.markReadCalls...)
	mock.mu.Unlock()
	if len(calls) != 1 || calls[0] != [2]string{"c1", "m2"} {
		t.Fatalf("MarkRead calls = %v, want one for m2 (skip placeholder and tombstone)", calls)
	}
	// A newer message is sent again.
	if err := a.Store.UpsertMessage(&db.Message{MessageID: "m5", ConversationID: "c1", TimestampMS: 5000, Status: "INCOMING_COMPLETE", Body: "w", SourcePlatform: "sms"}); err != nil {
		t.Fatal(err)
	}
	_, _ = a.MarkReadOnGoogle("c1")
	mock.mu.Lock()
	n := len(mock.markReadCalls)
	mock.mu.Unlock()
	if n != 2 {
		t.Fatalf("MarkRead calls after new message = %d, want 2", n)
	}
	if scope, _ := a.MarkReadOnGoogle("missing"); scope != "local" {
		t.Fatalf("empty conversation scope = %q, want local", scope)
	}
}
