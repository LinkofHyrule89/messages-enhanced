package app

import (
	"errors"
	"testing"
	"time"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"

	"github.com/maxghenis/openmessage/internal/db"
)

func waitRefresh(t *testing.T, a *App) RefreshStatus {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if st := a.GoogleRefreshStatus(); !st.Running {
			return st
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("refresh did not finish")
	return RefreshStatus{}
}

func TestGoogleRefreshUpdatesNamesStatesAndRateLimits(t *testing.T) {
	t.Setenv("OPENMESSAGE_GOOGLE_AVATAR_SYNC", "off")
	now := time.Now().UnixMicro()
	mock := &mockGMClient{
		conversations: map[gmproto.ListConversationsRequest_Folder][][]*gmproto.Conversation{
			gmproto.ListConversationsRequest_INBOX: {{
				{ConversationID: "g1", Name: "New Name", IsGroupChat: true, Status: gmproto.ConversationStatus_ACTIVE, LastMessageTimestamp: now},
			}},
			gmproto.ListConversationsRequest_ARCHIVE: {{
				{ConversationID: "a1", Name: "Old", Status: gmproto.ConversationStatus_ARCHIVED, LastMessageTimestamp: now - 1000},
			}},
			gmproto.ListConversationsRequest_SPAM_BLOCKED: {{
				{ConversationID: "s1", Name: "Spam", Status: gmproto.ConversationStatus_SPAM_FOLDER, LastMessageTimestamp: now - 2000},
				{ConversationID: "s2", Name: "Unknown spam", Status: gmproto.ConversationStatus_SPAM_FOLDER, LastMessageTimestamp: now - 3000},
			}},
		},
		phoneConvs: map[string]*gmproto.Conversation{
			"gone": {ConversationID: "gone", Status: gmproto.ConversationStatus_DELETED},
		},
	}
	a := newTestApp(t, mock)
	ms := now / 1000
	for _, c := range []*db.Conversation{
		{ConversationID: "g1", Name: "Old Name", IsGroup: true, LastMessageTS: ms},
		{ConversationID: "s1", Name: "Spam", LastMessageTS: ms},
		{ConversationID: "gone", Name: "Trashed elsewhere", LastMessageTS: ms + 5},
	} {
		if err := a.Store.UpsertConversation(c); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := a.StartGoogleRefresh("list"); err != nil {
		t.Fatal(err)
	}
	st := waitRefresh(t, a)
	if st.Error != "" || st.Stage != "Done" {
		t.Fatalf("status = %+v", st)
	}
	g, _ := a.Store.GetConversation("g1")
	if g == nil || g.Name != "New Name" {
		t.Fatalf("group name not refreshed: %+v", g)
	}
	if c, _ := a.Store.GetConversation("a1"); c == nil || c.Tab != db.TabArchive {
		t.Fatalf("archived conversation not mirrored: %+v", c)
	}
	if c, _ := a.Store.GetConversation("s1"); c == nil || c.Tab != db.TabSpam {
		t.Fatalf("spam conversation not hidden: %+v", c)
	}
	if a.Store.ConversationExists("s2") {
		t.Fatal("spam not already here must not be imported")
	}
	if a.Store.ConversationExists("gone") {
		t.Fatal("conversation deleted on the phone should be removed")
	}
	if _, err := a.StartGoogleRefresh("list"); !errors.Is(err, ErrRefreshRateLimited) {
		t.Fatalf("second list refresh within a minute: err = %v", err)
	}
	if _, err := a.StartGoogleRefresh("all"); err != nil {
		t.Fatalf("first full refresh should start: %v", err)
	}
	waitRefresh(t, a)
	if st, err := a.StartGoogleRefresh("all"); !errors.Is(err, ErrRefreshRateLimited) || st.RetryAfterSec <= 0 {
		t.Fatalf("second full refresh: err=%v st=%+v", err, st)
	}
}
