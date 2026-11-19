package client

import (
	"testing"
	"time"

	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"

	"github.com/maxghenis/openmessage/internal/db"
)

func ownEcho(id, conv, body string, tsMS int64, tmpID string, status gmproto.MessageStatusType) *libgm.WrappedMessage {
	return &libgm.WrappedMessage{Message: &gmproto.Message{
		MessageID:         id,
		ConversationID:    conv,
		Timestamp:         tsMS * 1000,
		TmpID:             tmpID,
		MessageStatus:     &gmproto.MessageStatus{Status: status},
		SenderParticipant: &gmproto.Participant{IsMe: true, FullName: "Me", ID: &gmproto.SmallInfo{Number: "+15551234567"}},
		MessageInfo: []*gmproto.MessageInfo{{
			Data: &gmproto.MessageInfo_MessageContent{MessageContent: &gmproto.MessageContent{Content: body}},
		}},
	}}
}

// An echo without a TmpID (or whose TmpID row doesn't exist) replaces the
// matching "tm-" placeholder; a later status-only re-delivery of the same
// real message must not consume another identical pending placeholder.
func TestHandleMessage_EchoWithoutTmpIDRemovesMatchingPlaceholder(t *testing.T) {
	store, err := db.New(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	const ts = int64(1_790_738_307_000)
	for _, m := range []*db.Message{
		{MessageID: "tm-1790738307000-aaaa", ConversationID: "5", Body: "Leaving now", TimestampMS: ts, Status: "OUTGOING_SENDING", IsFromMe: true},
		{MessageID: "tm-1790738400000-bbbb", ConversationID: "5", Body: "Leaving now", TimestampMS: ts + 93000, Status: "OUTGOING_SENDING", IsFromMe: true},
		{MessageID: "tm-1790738307000-cccc", ConversationID: "6", Body: "Leaving now", TimestampMS: ts, Status: "OUTGOING_SENDING", IsFromMe: true},
	} {
		if err := store.UpsertMessage(m); err != nil {
			t.Fatal(err)
		}
	}
	h := &EventHandler{Store: store, Logger: zerolog.Nop()}

	h.handleMessage(ownEcho("191900", "5", "Leaving now", ts+900, "", gmproto.MessageStatusType_OUTGOING_COMPLETE))
	if m, _ := store.GetMessageByID("tm-1790738307000-aaaa"); m != nil {
		t.Fatal("matching placeholder should be removed")
	}
	// Status update for 191900 (not new): the second placeholder stays.
	h.handleMessage(ownEcho("191900", "5", "Leaving now", ts+900, "", gmproto.MessageStatusType_OUTGOING_DELIVERED))
	if m, _ := store.GetMessageByID("tm-1790738400000-bbbb"); m == nil {
		t.Fatal("status re-delivery must not remove another placeholder")
	}
	// Its own echo (TmpID pointing at an unknown row) removes it.
	h.handleMessage(ownEcho("191901", "5", "Leaving now", ts+93500, "tmp_unrelated", gmproto.MessageStatusType_OUTGOING_COMPLETE))
	if m, _ := store.GetMessageByID("tm-1790738400000-bbbb"); m != nil {
		t.Fatal("second placeholder should be removed by its own echo")
	}
	if m, _ := store.GetMessageByID("tm-1790738307000-cccc"); m == nil {
		t.Fatal("placeholder in another conversation must stay")
	}
	if m, _ := store.GetMessageByID("191900"); m == nil || m.Status != "OUTGOING_DELIVERED" {
		t.Fatalf("real message should be stored with latest status, got %+v", m)
	}
}

func TestHandleMessage_DirectEchoWithDifferentTmpIDReplacesPlaceholder(t *testing.T) {
	store, err := db.New(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	const ts = int64(1_790_738_307_000)
	if err := store.UpsertMessage(&db.Message{
		MessageID: "tmp_111111111111", ConversationID: "dm-1", Body: "on my way",
		TimestampMS: ts, Status: "OUTGOING_SENDING", IsFromMe: true,
	}); err != nil {
		t.Fatal(err)
	}
	// An unrelated real id that a group echo might name in the tmp field.
	if err := store.UpsertMessage(&db.Message{
		MessageID: "199999", ConversationID: "dm-1", Body: "older",
		TimestampMS: ts - 60000, Status: "OUTGOING_DELIVERED", IsFromMe: true,
	}); err != nil {
		t.Fatal(err)
	}
	h := &EventHandler{Store: store, Logger: zerolog.Nop()}
	h.handleMessage(ownEcho("200100", "dm-1", "on my way", ts+400, "199999", gmproto.MessageStatusType_OUTGOING_COMPLETE))
	if m, _ := store.GetMessageByID("tmp_111111111111"); m != nil {
		t.Fatal("1:1 placeholder should be replaced by body match")
	}
	if m, _ := store.GetMessageByID("199999"); m == nil {
		t.Fatal("echo tmp id must not delete a real message")
	}
	if m, _ := store.GetMessageByID("200100"); m == nil {
		t.Fatal("real echo should be stored")
	}
}

func TestHandleMessage_GroupEchoTmpIDOnAnotherFieldMovesOntoSendConversation(t *testing.T) {
	store, err := db.New(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	now := time.Now().UnixMilli()
	if err := store.UpsertMessage(&db.Message{
		MessageID: "tmp_264794425382", ConversationID: "4", Body: "group hi",
		TimestampMS: now, Status: "OUTGOING_SENDING", IsFromMe: true,
	}); err != nil {
		t.Fatal(err)
	}
	echo := ownEcho("200342", "99", "group hi", now+307, "", gmproto.MessageStatusType_OUTGOING_DISPLAYED)
	echo.AnotherMessageID = &gmproto.AnotherMessageID{AnotherID: "tmp_264794425382"}
	h := &EventHandler{Store: store, Logger: zerolog.Nop()}
	h.handleMessage(echo)

	if m, _ := store.GetMessageByID("tmp_264794425382"); m != nil {
		t.Fatal("group placeholder should be removed")
	}
	got, err := store.GetMessageByID("200342")
	if err != nil || got == nil || got.ConversationID != "4" || got.Status != "OUTGOING_DISPLAYED" {
		t.Fatalf("echo = %+v, %v", got, err)
	}
	msgs, err := store.GetMessagesByConversation("4", 20)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 1 || msgs[0].MessageID != "200342" {
		t.Fatalf("conversation 4 = %+v, want only the Google row", msgs)
	}
}
