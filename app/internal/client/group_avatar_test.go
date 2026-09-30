package client

import (
	"bytes"
	"strings"
	"testing"

	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"

	"github.com/maxghenis/openmessage/internal/db"
)

func TestGroupAvatarCandidate(t *testing.T) {
	const iconURL = "https://lh3.googleusercontent.com/group-icon"
	c, ok := GroupAvatarCandidate(&gmproto.Conversation{ConversationID: "5", Name: "Fam", IsGroupChat: true, GroupAvatarURL: " " + iconURL + " "}, "live")
	if !ok || c.ParticipantID != "conv:5" || c.GroupAvatarURL != iconURL || c.SourcePlatform != "sms" || c.Source != "live" || c.DisplayName != "Fam" {
		t.Fatalf("group with icon: %+v, %v", c, ok)
	}
	if _, ok := GroupAvatarCandidate(&gmproto.Conversation{ConversationID: "5", IsGroupChat: true}, "live"); ok {
		t.Fatal("group without icon URL must not produce a candidate")
	}
	if _, ok := GroupAvatarCandidate(&gmproto.Conversation{ConversationID: "6", GroupAvatarURL: iconURL}, "live"); ok {
		t.Fatal("1:1 conversation must not produce a group candidate")
	}
	if _, ok := GroupAvatarCandidate(nil, "live"); ok {
		t.Fatal("nil conversation must not produce a candidate")
	}
}

func TestLogGroupAvatarPresenceOncePerConversation(t *testing.T) {
	var buf bytes.Buffer
	logger := zerolog.New(&buf)
	const secretURL = "https://lh3.googleusercontent.com/very-secret-token"
	with := &gmproto.Conversation{ConversationID: "test-grp-1", IsGroupChat: true, GroupAvatarURL: secretURL}
	without := &gmproto.Conversation{ConversationID: "test-grp-2", IsGroupChat: true}
	oneToOne := &gmproto.Conversation{ConversationID: "test-dm-1", GroupAvatarURL: secretURL}

	for i := 0; i < 3; i++ {
		LogGroupAvatarPresence(logger, with)
		LogGroupAvatarPresence(logger, without)
		LogGroupAvatarPresence(logger, oneToOne)
	}
	out := buf.String()
	lines := strings.Split(strings.TrimSpace(out), "\n")
	if len(lines) != 2 {
		t.Fatalf("want 2 log lines (one per group), got %d:\n%s", len(lines), out)
	}
	if !strings.Contains(out, `"conv_id":"test-grp-1","group_avatar_url_present":true`) ||
		!strings.Contains(out, `"conv_id":"test-grp-2","group_avatar_url_present":false`) {
		t.Fatalf("unexpected log output:\n%s", out)
	}
	if strings.Contains(out, "googleusercontent") || strings.Contains(out, "secret") {
		t.Fatalf("log must never contain the icon URL:\n%s", out)
	}
	// The field appearing later is logged once more.
	LogGroupAvatarPresence(logger, &gmproto.Conversation{ConversationID: "test-grp-2", IsGroupChat: true, GroupAvatarURL: secretURL})
	LogGroupAvatarPresence(logger, &gmproto.Conversation{ConversationID: "test-grp-2", IsGroupChat: true, GroupAvatarURL: secretURL})
	if n := strings.Count(buf.String(), "\n"); n != 3 {
		t.Fatalf("want 3 lines after presence change, got %d", n)
	}
}

func TestStoreConversationEmitsGroupIconCandidateFirst(t *testing.T) {
	store, err := db.New(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	var got []db.ContactAvatarCandidate
	handler := &EventHandler{
		Store:                    store,
		Logger:                   zerolog.Nop(),
		OnGoogleAvatarCandidates: func(c []db.ContactAvatarCandidate) { got = append(got, c...) },
	}
	handler.storeConversation(&gmproto.Conversation{
		ConversationID: "test-live-grp", Name: "Crew", IsGroupChat: true,
		GroupAvatarURL: "https://lh3.googleusercontent.com/live-icon",
		Participants: []*gmproto.Participant{
			{ID: &gmproto.SmallInfo{ParticipantID: "p1", Number: "+15555550101"}, FullName: "A"},
			{ID: &gmproto.SmallInfo{ParticipantID: "me", Number: "+15555550100"}, IsMe: true},
		},
	})
	if len(got) != 2 || got[0].ParticipantID != "conv:test-live-grp" || got[0].GroupAvatarURL == "" || got[1].ParticipantID != "p1" {
		t.Fatalf("candidates = %+v", got)
	}
}
