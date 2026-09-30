package app

import (
	"testing"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"

	"github.com/maxghenis/openmessage/internal/db"
)

// Own messages that come in through the fetch path (recent reconcile,
// backfill) carry no TmpID; they must still replace the "tm-" placeholder.
func TestStoreMessage_FetchedOwnPictureRemovesPlaceholder(t *testing.T) {
	a := newDisconnectedApp(t)
	const ts = int64(1_790_738_307_000)
	if err := a.Store.UpsertMessage(&db.Message{
		MessageID: "tm-1790738302359-1kwiyqpn", ConversationID: "5", TimestampMS: ts,
		Status: "OUTGOING_SENDING", IsFromMe: true, MediaID: "upload-1", MimeType: "image/png",
	}); err != nil {
		t.Fatal(err)
	}
	if err := a.Store.UpsertMessage(&db.Message{
		MessageID: "tm-failed-send", ConversationID: "5", Body: "never delivered", TimestampMS: ts,
		Status: "OUTGOING_SENDING", IsFromMe: true,
	}); err != nil {
		t.Fatal(err)
	}
	a.storeMessage(&gmproto.Message{
		MessageID:         "192058",
		ConversationID:    "5",
		Timestamp:         (ts + 1000) * 1000,
		MessageStatus:     &gmproto.MessageStatus{Status: gmproto.MessageStatusType_OUTGOING_DELIVERED},
		SenderParticipant: &gmproto.Participant{IsMe: true},
		MessageInfo: []*gmproto.MessageInfo{{
			Data: &gmproto.MessageInfo_MediaContent{MediaContent: &gmproto.MediaContent{MediaID: "media-1", MimeType: "image/png"}},
		}},
	})
	if m, _ := a.Store.GetMessageByID("192058"); m == nil || !m.IsFromMe {
		t.Fatalf("real picture not stored as from me: %+v", m)
	}
	if m, _ := a.Store.GetMessageByID("tm-1790738302359-1kwiyqpn"); m != nil {
		t.Fatal("picture placeholder should be removed")
	}
	if m, _ := a.Store.GetMessageByID("tm-failed-send"); m == nil {
		t.Fatal("unmatched placeholder must stay")
	}
}
