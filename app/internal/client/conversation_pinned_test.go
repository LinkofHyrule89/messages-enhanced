package client

import (
	"testing"

	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"

	"github.com/maxghenis/openmessage/internal/db"
)

// Pinning or unpinning a conversation on the phone arrives as a conversation
// update; the read-only flag must follow it.
func TestHandleConversationTracksPhonePin(t *testing.T) {
	store, err := db.New(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	changes := 0
	h := &EventHandler{Store: store, Logger: zerolog.Nop(), OnConversationsChange: func() { changes++ }}
	for i, pinned := range []bool{true, false, true} {
		h.handleConversation(&gmproto.Conversation{ConversationID: "c1", Name: "Crew", LastMessageTimestamp: 1_000_000, Pinned: pinned})
		c, err := store.GetConversation("c1")
		if err != nil {
			t.Fatal(err)
		}
		if c.GooglePinned != pinned {
			t.Fatalf("step %d: google_pinned = %v, want %v", i, c.GooglePinned, pinned)
		}
	}
	if changes != 3 {
		t.Fatalf("conversation change events = %d", changes)
	}
}
