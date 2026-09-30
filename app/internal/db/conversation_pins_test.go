package db

import (
	"errors"
	"fmt"
	"testing"
)

func boolPtr(v bool) *bool { return &v }

func TestGooglePinnedFollowsSnapshots(t *testing.T) {
	s := newTestStore(t)
	snap := func(pinned *bool, ts int64) {
		t.Helper()
		if err := s.ApplyConversationSnapshot(&Conversation{ConversationID: "g1", Name: "Crew", LastMessageTS: ts, GooglePinnedSnapshot: pinned}); err != nil {
			t.Fatal(err)
		}
	}
	get := func() *Conversation {
		t.Helper()
		c, err := s.GetConversation("g1")
		if err != nil {
			t.Fatal(err)
		}
		return c
	}
	snap(boolPtr(true), 1000) // new conversation, pinned on the phone
	if !get().GooglePinned {
		t.Fatal("pinned flag not stored on insert")
	}
	snap(nil, 2000) // a snapshot without pin info keeps the flag
	if !get().GooglePinned {
		t.Fatal("nil snapshot cleared the flag")
	}
	// Other writers (WhatsApp/Signal-style upserts) don't touch it either.
	if err := s.UpsertConversation(&Conversation{ConversationID: "g1", Name: "Crew", LastMessageTS: 2100}); err != nil {
		t.Fatal(err)
	}
	if !get().GooglePinned {
		t.Fatal("plain upsert cleared the flag")
	}
	snap(boolPtr(false), 500) // unpinned on the phone; an older snapshot still carries it
	if c := get(); c.GooglePinned || c.LastMessageTS != 2100 {
		t.Fatalf("unpin not applied (or recency moved back): %+v", c)
	}
	snap(boolPtr(true), 2200)
	if !get().GooglePinned {
		t.Fatal("re-pin not applied")
	}
}

func TestLocalConversationPins(t *testing.T) {
	s := newTestStore(t)
	for i := 0; i < MaxLocalConversationPins+2; i++ {
		if err := s.UpsertConversation(&Conversation{ConversationID: fmt.Sprintf("c%02d", i), LastMessageTS: int64(1000 + i)}); err != nil {
			t.Fatal(err)
		}
	}
	c, err := s.SetConversationLocalPinned("c00", true, 111)
	if err != nil || c.LocalPinnedAtMS != 111 {
		t.Fatalf("pin: %+v %v", c, err)
	}
	// re-pin keeps the original time; a full upsert keeps the pin
	if c, _ = s.SetConversationLocalPinned("c00", true, 999); c.LocalPinnedAtMS != 111 {
		t.Fatalf("re-pin changed time: %d", c.LocalPinnedAtMS)
	}
	if err := s.ApplyConversationSnapshot(&Conversation{ConversationID: "c00", Name: "Renamed", LastMessageTS: 5000, GooglePinnedSnapshot: boolPtr(false)}); err != nil {
		t.Fatal(err)
	}
	if c, _ = s.GetConversation("c00"); c.LocalPinnedAtMS != 111 || c.Name != "Renamed" {
		t.Fatalf("snapshot dropped local pin: %+v", c)
	}
	if _, err := s.SetConversationLocalPinned("nope", true, 1); !errors.Is(err, ErrConvPinNotFound) {
		t.Fatalf("missing conversation: %v", err)
	}
	if _, err := s.SetConversationLocalPinned(" ", true, 1); !errors.Is(err, ErrConvPinNotFound) {
		t.Fatalf("blank id: %v", err)
	}
	for i := 1; i < MaxLocalConversationPins; i++ {
		if _, err := s.SetConversationLocalPinned(fmt.Sprintf("c%02d", i), true, int64(200+i)); err != nil {
			t.Fatalf("pin %d: %v", i, err)
		}
	}
	if n, _ := s.CountLocalConversationPins(); n != MaxLocalConversationPins {
		t.Fatalf("count = %d", n)
	}
	if _, err := s.SetConversationLocalPinned("c20", true, 1); !errors.Is(err, ErrConvPinLimit) {
		t.Fatalf("21st pin: %v", err)
	}
	// unpinning an already-pinned one at the limit still works, then there's room
	if c, err = s.SetConversationLocalPinned("c05", false, 0); err != nil || c.LocalPinnedAtMS != 0 {
		t.Fatalf("unpin: %+v %v", c, err)
	}
	if _, err := s.SetConversationLocalPinned("c20", true, 1); err != nil {
		t.Fatalf("pin after unpin: %v", err)
	}
	if _, err := s.SetConversationLocalPinned("c21", false, 0); err != nil {
		t.Fatalf("unpin of an unpinned conversation: %v", err)
	}
}

func TestListConversationsIncludesOlderPinned(t *testing.T) {
	s := newTestStore(t)
	for i := 0; i < 10; i++ {
		if err := s.UpsertConversation(&Conversation{ConversationID: fmt.Sprintf("c%d", i), LastMessageTS: int64(1000 + i)}); err != nil {
			t.Fatal(err)
		}
	}
	// c0 and c1 are the oldest: pinned on the phone / locally
	if err := s.ApplyConversationSnapshot(&Conversation{ConversationID: "c0", LastMessageTS: 1000, GooglePinnedSnapshot: boolPtr(true)}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.SetConversationLocalPinned("c1", true, 5); err != nil {
		t.Fatal(err)
	}
	list, err := s.ListConversations(3)
	if err != nil {
		t.Fatal(err)
	}
	got := map[string]*Conversation{}
	for _, c := range list {
		got[c.ConversationID] = c
	}
	if len(list) != 5 || got["c0"] == nil || !got["c0"].GooglePinned || got["c1"] == nil || got["c1"].LocalPinnedAtMS != 5 || got["c9"] == nil {
		t.Fatalf("list = %d %+v", len(list), got)
	}
}

func TestMergeConversationKeepsPins(t *testing.T) {
	s := newTestStore(t)
	if err := s.ApplyConversationSnapshot(&Conversation{ConversationID: "old", LastMessageTS: 1, GooglePinnedSnapshot: boolPtr(true)}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.SetConversationLocalPinned("old", true, 77); err != nil {
		t.Fatal(err)
	}
	if err := s.UpsertConversation(&Conversation{ConversationID: "new", LastMessageTS: 2}); err != nil {
		t.Fatal(err)
	}
	if err := s.MergeConversationIDs("old", "new"); err != nil {
		t.Fatal(err)
	}
	c, err := s.GetConversation("new")
	if err != nil || !c.GooglePinned || c.LocalPinnedAtMS != 77 {
		t.Fatalf("merged: %+v %v", c, err)
	}
}
