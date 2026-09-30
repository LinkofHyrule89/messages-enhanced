package client

import (
	"testing"

	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"

	"github.com/maxghenis/openmessage/internal/db"
)

func deletedUpdate(id, conv string) *libgm.WrappedMessage {
	return &libgm.WrappedMessage{Message: &gmproto.Message{
		MessageID:      id,
		ConversationID: conv,
		Timestamp:      2000 * 1000,
		MessageStatus:  &gmproto.MessageStatus{Status: gmproto.MessageStatusType_MESSAGE_DELETED},
		MessageInfo: []*gmproto.MessageInfo{{
			Data: &gmproto.MessageInfo_MessageContent{MessageContent: &gmproto.MessageContent{Content: "still has text"}},
		}},
	}}
}

func TestHandleMessage_DeletedUpdateRemovesLocalRow(t *testing.T) {
	store, err := db.New(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if err := store.UpsertMessage(&db.Message{MessageID: "m1", ConversationID: "c1", Body: "hello", TimestampMS: 1000, Status: "INCOMING_COMPLETE"}); err != nil {
		t.Fatal(err)
	}
	var changedFor string
	convChanges := 0
	h := &EventHandler{
		Store:                 store,
		Logger:                zerolog.Nop(),
		OnMessagesChange:      func(id string) { changedFor = id },
		OnConversationsChange: func() { convChanges++ },
	}
	h.handleMessage(deletedUpdate("m1", "c1"))
	if m, _ := store.GetMessageByID("m1"); m != nil {
		t.Fatal("deleted message still stored")
	}
	if changedFor != "c1" || convChanges != 1 {
		t.Fatalf("change callbacks: %q %d", changedFor, convChanges)
	}

	// A deleted-status update for a message we never had is not stored.
	h.handleMessage(deletedUpdate("m2", "c1"))
	if m, _ := store.GetMessageByID("m2"); m != nil {
		t.Fatal("deleted update was stored as a new message")
	}
}
