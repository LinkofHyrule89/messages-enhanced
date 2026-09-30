package app

import (
	"bytes"
	"strings"
	"testing"
	"time"

	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"

	"github.com/maxghenis/openmessage/internal/db"
)

func groupCandidate(convID, iconURL string) db.ContactAvatarCandidate {
	return db.ContactAvatarCandidate{SourcePlatform: "sms", ParticipantID: db.GroupAvatarParticipantID(convID), Source: "live", GroupAvatarURL: iconURL}
}

func TestFetchGoogleGroupAvatarCachesAndDedupesByURL(t *testing.T) {
	const url1 = "https://lh3.googleusercontent.com/icon-1"
	const url2 = "https://lh3.googleusercontent.com/icon-2"
	mock := &mockGMClient{avatarDownloads: map[string][]byte{url1: testAvatarPNG, url2: testAvatarPNG}}
	a := newTestApp(t, mock)
	var logs bytes.Buffer
	a.Logger = zerolog.New(&logs)

	a.fetchGoogleGroupAvatar(groupCandidate("7", url1))
	av, err := a.Store.GetContactAvatar("sms", "conv:7", "", "")
	if err != nil || av == nil || len(av.ImageData) == 0 || av.MimeType != "image/png" {
		t.Fatalf("group icon not cached: %+v, %v", av, err)
	}
	// Same URL again: no second download.
	a.fetchGoogleGroupAvatar(groupCandidate("7", url1))
	// New URL: downloaded.
	a.fetchGoogleGroupAvatar(groupCandidate("7", url2))
	mock.mu.Lock()
	c1, c2 := mock.avatarDownloadCalls[url1], mock.avatarDownloadCalls[url2]
	mock.mu.Unlock()
	if c1 != 1 || c2 != 1 {
		t.Fatalf("downloads url1=%d url2=%d, want 1 and 1", c1, c2)
	}
	if !strings.Contains(logs.String(), "Google group icon cached") || strings.Contains(logs.String(), "googleusercontent") {
		t.Fatalf("logs should say cached without the URL:\n%s", logs.String())
	}
}

func TestFetchGoogleGroupAvatarFailureAndRejectedHost(t *testing.T) {
	const missing = "https://lh3.googleusercontent.com/missing"
	const evil = "https://evil.example.com/lh3.googleusercontent.com/x"
	mock := &mockGMClient{avatarDownloads: map[string][]byte{}}
	a := newTestApp(t, mock)

	a.fetchGoogleGroupAvatar(groupCandidate("4", missing))
	a.fetchGoogleGroupAvatar(groupCandidate("4", missing)) // within missing TTL: not retried
	a.fetchGoogleGroupAvatar(groupCandidate("5", evil))
	a.fetchGoogleGroupAvatar(groupCandidate("5", "http://lh3.googleusercontent.com/plain-http"))
	mock.mu.Lock()
	calls := mock.avatarDownloadCalls
	mock.mu.Unlock()
	if calls[missing] != 1 || calls[evil] != 0 || calls["http://lh3.googleusercontent.com/plain-http"] != 0 {
		t.Fatalf("download calls = %v", calls)
	}
	if av, _ := a.Store.GetContactAvatar("sms", "conv:4", "", ""); av != nil && len(av.ImageData) > 0 {
		t.Fatal("failed download must not cache an image")
	}
	st, err := a.Store.GetGroupAvatarState(groupCandidate("4", missing))
	if err != nil || st == nil || st.SourceURLHash != db.GroupAvatarURLHash(missing) || st.ImageHash != "" {
		t.Fatalf("failure state = %+v, %v", st, err)
	}
}

func TestAllowedGroupAvatarURL(t *testing.T) {
	for raw, want := range map[string]bool{
		"https://lh3.googleusercontent.com/a/b":  true,
		"https://GGPHT.com/x":                    true,
		"https://www.gstatic.com/x.png":          true,
		"http://lh3.googleusercontent.com/a":     false,
		"https://googleusercontent.com.evil.io/": false,
		"https://user@lh3.googleusercontent.com": false,
		"https://example.com/x":                  false,
		"not a url":                              false,
	} {
		if got := allowedGroupAvatarURL(raw); got != want {
			t.Errorf("allowedGroupAvatarURL(%q) = %v, want %v", raw, got, want)
		}
	}
}

func TestStoreConversationQueuesGroupIconFirst(t *testing.T) {
	const iconURL = "https://lh3.googleusercontent.com/queued-icon"
	mock := &mockGMClient{
		avatarDownloads:       map[string][]byte{iconURL: testAvatarPNG},
		participantThumbnails: map[string][]byte{},
	}
	a := newTestApp(t, mock)
	conv := &gmproto.Conversation{
		ConversationID: "g-queued", Name: "Crew", IsGroupChat: true, GroupAvatarURL: iconURL,
		Participants: []*gmproto.Participant{
			{ID: &gmproto.SmallInfo{ParticipantID: "p1", Number: "+15555550101"}, FullName: "A"},
			{ID: &gmproto.SmallInfo{ParticipantID: "me", Number: "+15555550100"}, IsMe: true},
		},
	}
	if err := a.storeConversation(conv); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if av, _ := a.Store.GetContactAvatar("sms", "conv:g-queued", "", ""); av != nil && len(av.ImageData) > 0 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("group icon from storeConversation was not cached")
}
