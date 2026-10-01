package app

import (
	"net/http"
	"testing"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"

	"github.com/maxghenis/openmessage/internal/db"
)

func TestCarPinConversationIsLocalAndKeepsGoogleFlag(t *testing.T) {
	a := newDisconnectedApp(t)
	pinnedOnPhone := true
	if err := a.Store.ApplyConversationSnapshot(&db.Conversation{ConversationID: "c1", Name: "Crew", LastMessageTS: 10, GooglePinnedSnapshot: &pinnedOnPhone}); err != nil {
		t.Fatal(err)
	}
	if err := a.Store.UpsertConversation(&db.Conversation{ConversationID: "c2", Name: "Sam", LastMessageTS: 20}); err != nil {
		t.Fatal(err)
	}
	res, err := a.CarPinConversation("c2", true)
	if err != nil || !res.Pinned || res.GooglePinned || res.Scope != "local" || res.LocalPins != 1 || res.MaxLocalPins != db.MaxLocalConversationPins || res.LocalPinnedAtMS == 0 {
		t.Fatalf("pin c2: %+v %v", res, err)
	}
	// Unpinning locally never touches the phone's flag.
	res, err = a.CarPinConversation("c1", false)
	if err != nil || res.Pinned || !res.GooglePinned {
		t.Fatalf("unpin c1: %+v %v", res, err)
	}
	for _, c := range []struct {
		id   string
		want int
	}{{"", http.StatusBadRequest}, {"nope", http.StatusNotFound}} {
		if _, err := a.CarPinConversation(c.id, true); carStatus(t, err) != c.want {
			t.Errorf("CarPinConversation(%q) status %d want %d", c.id, carStatus(t, err), c.want)
		}
	}
}

func TestBackfillStoresPhonePin(t *testing.T) {
	a := newDisconnectedApp(t)
	if err := a.storeConversation(&gmproto.Conversation{ConversationID: "g1", Name: "Crew", LastMessageTimestamp: 1_000_000, Pinned: true}); err != nil {
		t.Fatal(err)
	}
	if c, err := a.Store.GetConversation("g1"); err != nil || !c.GooglePinned {
		t.Fatalf("after backfill: %+v %v", c, err)
	}
	if err := a.storeConversation(&gmproto.Conversation{ConversationID: "g1", Name: "Crew", LastMessageTimestamp: 1_000_000}); err != nil {
		t.Fatal(err)
	}
	if c, _ := a.Store.GetConversation("g1"); c.GooglePinned {
		t.Fatal("unpin from backfill not applied")
	}
}

func TestCarFolderConversationsCarryPins(t *testing.T) {
	mock := &mockGMClient{conversations: map[gmproto.ListConversationsRequest_Folder][][]*gmproto.Conversation{
		gmproto.ListConversationsRequest_ARCHIVE: {{
			{ConversationID: "a1", Name: "Old", LastMessageTimestamp: 2000000, Status: gmproto.ConversationStatus_ARCHIVED, Pinned: true},
			{ConversationID: "a2", Name: "Older", LastMessageTimestamp: 1000000, Status: gmproto.ConversationStatus_ARCHIVED},
		}},
	}}
	a := newTestApp(t, mock)
	if err := a.Store.UpsertConversation(&db.Conversation{ConversationID: "a2", Name: "Older"}); err != nil {
		t.Fatal(err)
	}
	if _, err := a.Store.SetConversationLocalPinned("a2", true, 42); err != nil {
		t.Fatal(err)
	}
	arch, err := a.CarFolderConversations("archived")
	if err != nil || len(arch) != 2 {
		t.Fatalf("archived: %+v %v", arch, err)
	}
	if !arch[0].GooglePinned || arch[0].LocalPinnedAtMS != 0 || arch[1].GooglePinned || arch[1].LocalPinnedAtMS != 42 {
		t.Fatalf("pins: %+v / %+v", arch[0].Conversation, arch[1].Conversation)
	}
}
