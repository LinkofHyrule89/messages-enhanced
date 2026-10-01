package app

import (
	"net/http"
	"testing"

	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"

	"github.com/maxghenis/openmessage/internal/client"
	"github.com/maxghenis/openmessage/internal/db"
)

func putConv(t *testing.T, s *db.Store, id string, group bool, participants string) {
	t.Helper()
	if err := s.UpsertConversation(&db.Conversation{ConversationID: id, Name: id, IsGroup: group, Participants: participants, LastMessageTS: 1000, SourcePlatform: "sms"}); err != nil {
		t.Fatal(err)
	}
}

func TestCarArchiveConversationSyncsGoogleThenLocal(t *testing.T) {
	mock := &mockGMClient{}
	a := newTestApp(t, mock)
	putConv(t, a.Store, "c1", false, `[{"name":"Ann","number":"+15551230000"}]`)
	res, err := a.CarArchiveConversation("c1", true)
	if err != nil || !res.Archived || res.Scope != "google" {
		t.Fatalf("archive: %+v %v", res, err)
	}
	if len(mock.updateConvCalls) != 1 {
		t.Fatalf("calls %d", len(mock.updateConvCalls))
	}
	req := mock.updateConvCalls[0]
	if req.GetConversationID() != "c1" || req.GetUpdateData().GetStatus() != gmproto.ConversationStatus_ARCHIVED || req.GetUpdateData().GetConversationID() != "c1" {
		t.Fatalf("request: %v", req)
	}
	if c, _ := a.Store.GetConversation("c1"); c.Tab != db.TabArchive {
		t.Fatalf("tab = %q", c.Tab)
	}
	if _, err := a.CarArchiveConversation("c1", false); err != nil {
		t.Fatal(err)
	}
	if mock.updateConvCalls[1].GetUpdateData().GetStatus() != gmproto.ConversationStatus_ACTIVE {
		t.Fatal("unarchive should set ACTIVE")
	}
	if c, _ := a.Store.GetConversation("c1"); c.Tab != db.TabInbox {
		t.Fatalf("tab after unarchive = %q", c.Tab)
	}
	// Google refuses: local unchanged.
	mock.updateConvFail = true
	if _, err := a.CarArchiveConversation("c1", true); carStatus(t, err) != http.StatusBadGateway {
		t.Fatalf("refused: %v", err)
	}
	if c, _ := a.Store.GetConversation("c1"); c.Tab != db.TabInbox {
		t.Fatal("tab changed although Google refused")
	}
	d := newDisconnectedApp(t)
	putConv(t, d.Store, "c1", false, "[]")
	if _, err := d.CarArchiveConversation("c1", true); carStatus(t, err) != http.StatusServiceUnavailable {
		t.Fatalf("disconnected: %v", err)
	}
}

func TestCarTrashConversationDeletesOnPhoneThenLocally(t *testing.T) {
	mock := &mockGMClient{}
	a := newTestApp(t, mock)
	putConv(t, a.Store, "c1", false, `[{"name":"Me","number":"+15550000000","is_me":true},{"name":"Ann","number":"+15551230000"}]`)
	putMsg(t, a.Store, "m1", "c1", "sms", false)
	res, err := a.CarTrashConversation("c1")
	if err != nil || res.Action != "trash" {
		t.Fatalf("%+v %v", res, err)
	}
	req := mock.updateConvCalls[0]
	if req.GetAction() != gmproto.ConversationActionStatus_DELETE || req.GetDeleteData().GetConversationID() != "c1" || req.GetDeleteData().GetPhone() != "+15551230000" {
		t.Fatalf("delete request: %v", req)
	}
	if c, _ := a.Store.GetConversation("c1"); c != nil {
		t.Fatal("local conversation not deleted")
	}
	if m, _ := a.Store.GetMessageByID("m1"); m != nil {
		t.Fatal("local messages not deleted")
	}
	// Groups: no phone number.
	putConv(t, a.Store, "g1", true, `[{"name":"A","number":"+1555"},{"name":"B","number":"+1556"}]`)
	if _, err := a.CarTrashConversation("g1"); err != nil {
		t.Fatal(err)
	}
	if p := mock.updateConvCalls[1].GetDeleteData().GetPhone(); p != "" {
		t.Fatalf("group phone = %q", p)
	}
	// Google error keeps the local copy.
	putConv(t, a.Store, "c2", false, "[]")
	mock.updateConvFail = true
	if _, err := a.CarTrashConversation("c2"); carStatus(t, err) != http.StatusBadGateway {
		t.Fatalf("refused: %v", err)
	}
	if c, _ := a.Store.GetConversation("c2"); c == nil {
		t.Fatal("deleted locally although Google refused")
	}
}

func TestGoogleStatusMirrorsArchiveAndDelete(t *testing.T) {
	a := newTestApp(t, &mockGMClient{})
	putConv(t, a.Store, "c1", false, "[]")
	conv := &gmproto.Conversation{ConversationID: "c1", Status: gmproto.ConversationStatus_ARCHIVED}
	client.MirrorGoogleConversationStatus(a.Store, zerolog.Nop(), conv)
	if c, _ := a.Store.GetConversation("c1"); c.Tab != db.TabArchive {
		t.Fatalf("archived on phone -> tab %q", c.Tab)
	}
	conv.Status = gmproto.ConversationStatus_UNKNOWN_CONVERSATION_STATUS
	client.MirrorGoogleConversationStatus(a.Store, zerolog.Nop(), conv)
	if c, _ := a.Store.GetConversation("c1"); c.Tab != db.TabArchive {
		t.Fatal("unknown status must not change the tab")
	}
	conv.Status = gmproto.ConversationStatus_ACTIVE
	client.MirrorGoogleConversationStatus(a.Store, zerolog.Nop(), conv)
	if c, _ := a.Store.GetConversation("c1"); c.Tab != db.TabInbox {
		t.Fatalf("active on phone -> tab %q", c.Tab)
	}
	// Deleted on the phone: the backfill path drops it instead of storing.
	if err := a.storeConversation(&gmproto.Conversation{ConversationID: "c1", Status: gmproto.ConversationStatus_DELETED}); err != nil {
		t.Fatal(err)
	}
	if c, _ := a.Store.GetConversation("c1"); c != nil {
		t.Fatal("deleted conversation still stored")
	}
}

func TestCarMuteReadAndMeta(t *testing.T) {
	mock := &mockGMClient{}
	a := newTestApp(t, mock)
	putConv(t, a.Store, "c1", false, "[]")
	putMsg(t, a.Store, "m1", "c1", "sms", false)
	res, err := a.CarMuteConversation("c1", true)
	if err != nil || !res.Muted || res.Scope != "google" {
		t.Fatalf("mute: %+v %v", res, err)
	}
	if mock.updateConvCalls[0].GetUpdateData().GetMute() != gmproto.ConversationMuteStatus_MUTE {
		t.Fatal("google mute not sent")
	}
	if c, _ := a.Store.GetConversation("c1"); c.NotificationMode != "muted" {
		t.Fatalf("mode %q", c.NotificationMode)
	}
	if m, _ := a.CarConversationMeta("c1"); !m.Muted {
		t.Fatal("meta not muted")
	}
	// Google refuses -> still muted here (server-side mute).
	mock.updateConvFail = true
	if res, _ := a.CarMuteConversation("c1", false); res.Scope != "local" || res.Muted {
		t.Fatalf("unmute local: %+v", res)
	}
	if _, err := a.CarMarkConversationRead("c1"); err != nil || len(mock.markReadCalls) != 1 || mock.markReadCalls[0][1] != "m1" {
		t.Fatalf("mark read: %v %v", err, mock.markReadCalls)
	}
	// Protocol from Google's conversation type; E2EE from tombstones.
	client.MirrorGoogleConversationStatus(a.Store, zerolog.Nop(), &gmproto.Conversation{ConversationID: "c1", Type: gmproto.ConversationType_RCS})
	if err := a.Store.UpsertMessage(&db.Message{MessageID: "t1", ConversationID: "c1", TimestampMS: 2000, Status: "TOMBSTONE_ENCRYPTED_ONE_ON_ONE_RCS_CREATED", SourcePlatform: "sms"}); err != nil {
		t.Fatal(err)
	}
	m, _ := a.CarConversationMeta("c1")
	if m.Protocol != "RCS" || !m.E2EE {
		t.Fatalf("meta: %+v", m)
	}
	client.MirrorGoogleConversationStatus(a.Store, zerolog.Nop(), &gmproto.Conversation{ConversationID: "c1", Type: gmproto.ConversationType_SMS})
	if m, _ := a.CarConversationMeta("c1"); m.Protocol != "SMS" || m.E2EE {
		t.Fatalf("sms meta: %+v", m)
	}
}
