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

func thumbGroupCandidate(convID string, force bool) db.ContactAvatarCandidate {
	return db.ContactAvatarCandidate{SourcePlatform: "sms", ParticipantID: db.GroupAvatarParticipantID(convID), Source: "live", GroupIcon: true, Force: force}
}

func groupIconHash(t *testing.T, a *App, convID string) string {
	t.Helper()
	st, err := a.Store.GetGroupAvatarState(thumbGroupCandidate(convID, false))
	if err != nil {
		t.Fatal(err)
	}
	if st == nil {
		return ""
	}
	return st.ImageHash
}

// Google omits groupAvatarURL after the icon is changed on the phone and
// serves the new icon only via GetParticipantThumbnail(conversationID): the
// old cached icon must be replaced, AvatarVersion bumped, and the icon
// removed when Google has none.
func TestFetchGoogleGroupAvatarByThumbnailReplacesAndClears(t *testing.T) {
	const oldURL = "https://lh3.googleusercontent.com/old-icon"
	newIcon := append(append([]byte{}, testAvatarPNG...), 0x01, 0x02)
	mock := &mockGMClient{avatarDownloads: map[string][]byte{oldURL: testAvatarPNG}, participantThumbnails: map[string][]byte{}}
	a := newTestApp(t, mock)

	a.fetchGoogleGroupAvatar(groupCandidate("g-thumb", oldURL))
	oldHash := groupIconHash(t, a, "g-thumb")
	if oldHash == "" {
		t.Fatal("old icon not cached")
	}
	v1 := a.Store.AvatarVersion()

	// Icon changed on the phone: no URL any more, new thumbnail.
	mock.mu.Lock()
	mock.participantThumbnails["g-thumb"] = newIcon
	mock.mu.Unlock()
	// Routine check within the TTL: not re-checked.
	a.fetchGoogleGroupAvatar(thumbGroupCandidate("g-thumb", false))
	if groupIconHash(t, a, "g-thumb") != oldHash {
		t.Fatal("routine check within TTL should not refetch")
	}
	// Forced (Refresh everything / icon-changed event): picks up the new icon.
	time.Sleep(2 * time.Millisecond)
	a.fetchGoogleGroupAvatar(thumbGroupCandidate("g-thumb", true))
	newHash := groupIconHash(t, a, "g-thumb")
	if newHash == "" || newHash == oldHash {
		t.Fatalf("icon not replaced: old=%s new=%s", oldHash, newHash)
	}
	v2 := a.Store.AvatarVersion()
	if v2 <= v1 {
		t.Fatalf("AvatarVersion not bumped: %d -> %d", v1, v2)
	}
	// Same icon again: no version bump.
	time.Sleep(2 * time.Millisecond)
	a.fetchGoogleGroupAvatar(thumbGroupCandidate("g-thumb", true))
	if a.Store.AvatarVersion() != v2 {
		t.Fatal("unchanged icon must not bump AvatarVersion")
	}
	// Removed on the phone: no URL, empty thumbnail -> cleared, version bumped.
	mock.mu.Lock()
	delete(mock.participantThumbnails, "g-thumb")
	mock.mu.Unlock()
	time.Sleep(2 * time.Millisecond)
	a.fetchGoogleGroupAvatar(thumbGroupCandidate("g-thumb", true))
	if h := groupIconHash(t, a, "g-thumb"); h != "" {
		t.Fatalf("removed icon still cached: %s", h)
	}
	if av, _ := a.Store.GetContactAvatar("sms", "conv:g-thumb", "", ""); av != nil && len(av.ImageData) > 0 {
		t.Fatal("removed icon still served")
	}
	if a.Store.AvatarVersion() <= v2 {
		t.Fatal("clearing must bump AvatarVersion")
	}
}

func TestFetchGoogleGroupAvatarDueAfterTTLAndStaleURLCheck(t *testing.T) {
	icon := append(append([]byte{}, testAvatarPNG...), 0x07)
	mock := &mockGMClient{participantThumbnails: map[string][]byte{"8": icon}}
	a := newTestApp(t, mock)
	// Never checked: fetched without force.
	a.fetchGoogleGroupAvatar(thumbGroupCandidate("8", false))
	if groupIconHash(t, a, "8") == "" {
		t.Fatal("first thumbnail check should cache the icon")
	}
	mock.mu.Lock()
	calls := mock.participantThumbCalls["8"]
	mock.mu.Unlock()
	if calls != 1 {
		t.Fatalf("thumbnail calls = %d, want 1", calls)
	}
	// Force re-downloads even when the URL hash matches.
	const u = "https://lh3.googleusercontent.com/same"
	mock.mu.Lock()
	mock.avatarDownloads = map[string][]byte{u: testAvatarPNG}
	mock.mu.Unlock()
	a.fetchGoogleGroupAvatar(groupCandidate("8", u))
	c := groupCandidate("8", u)
	c.Force = true
	a.fetchGoogleGroupAvatar(c)
	mock.mu.Lock()
	dl := mock.avatarDownloadCalls[u]
	mock.mu.Unlock()
	if dl != 2 {
		t.Fatalf("downloads = %d, want 2 (forced refresh re-downloads)", dl)
	}
}
