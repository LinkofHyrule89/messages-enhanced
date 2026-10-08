package app

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
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

func noURLGroupCandidate(convID string, force bool) db.ContactAvatarCandidate {
	return db.ContactAvatarCandidate{SourcePlatform: "sms", ParticipantID: db.GroupAvatarParticipantID(convID), Source: "live", GroupIcon: true, Force: force}
}

func groupIconHash(t *testing.T, a *App, convID string) string {
	t.Helper()
	st, err := a.Store.GetGroupAvatarState(noURLGroupCandidate(convID, false))
	if err != nil {
		t.Fatal(err)
	}
	if st == nil {
		return ""
	}
	return st.ImageHash
}

// Without groupAvatarURL Google has no fetchable icon: the cached one is
// cleared (clients show the default group avatar) and no thumbnail lookup by
// conversation ID is made (it returns the photo of the member whose
// participant ID equals the conversation ID).
func TestFetchGoogleGroupAvatarWithoutURLClearsAndNeverUsesThumbnail(t *testing.T) {
	const iconURL = "https://lh3.googleusercontent.com/old-icon"
	memberPhoto := append(append([]byte{}, testAvatarPNG...), 0x01)
	mock := &mockGMClient{avatarDownloads: map[string][]byte{iconURL: testAvatarPNG}, participantThumbnails: map[string][]byte{"g9": memberPhoto}}
	a := newTestApp(t, mock)

	a.fetchGoogleGroupAvatar(groupCandidate("g9", iconURL))
	if groupIconHash(t, a, "g9") == "" {
		t.Fatal("icon from URL not cached")
	}
	v1 := a.Store.AvatarVersion()
	time.Sleep(2 * time.Millisecond)
	a.fetchGoogleGroupAvatar(noURLGroupCandidate("g9", false))
	if h := groupIconHash(t, a, "g9"); h != "" {
		t.Fatalf("icon without URL should be cleared, got %s", h)
	}
	if av, _ := a.Store.GetContactAvatar("sms", "conv:g9", "", ""); av != nil && len(av.ImageData) > 0 {
		t.Fatal("cleared icon still served")
	}
	if a.Store.AvatarVersion() <= v1 {
		t.Fatal("clearing must bump AvatarVersion so clients refetch")
	}
	a.fetchGoogleGroupAvatar(noURLGroupCandidate("g9", true))
	mock.mu.Lock()
	thumbs := len(mock.participantThumbCalls)
	mock.mu.Unlock()
	if thumbs != 0 {
		t.Fatalf("group icon sync must not call GetParticipantThumbnail (%d calls)", thumbs)
	}
}

func TestFetchGoogleGroupAvatarRefusesPersonPhotoAndForceRedownloads(t *testing.T) {
	const iconURL = "https://lh3.googleusercontent.com/same-icon"
	mock := &mockGMClient{avatarDownloads: map[string][]byte{iconURL: testAvatarPNG}}
	a := newTestApp(t, mock)
	// A member's photo with the same bytes is cached.
	person := db.ContactAvatarCandidate{SourcePlatform: "sms", ParticipantID: "p1"}
	sum := sha256.Sum256(testAvatarPNG)
	if err := a.Store.UpsertContactAvatar(person, testAvatarPNG, "image/png", hex.EncodeToString(sum[:]), 1); err != nil {
		t.Fatal(err)
	}
	a.fetchGoogleGroupAvatar(groupCandidate("g8", iconURL))
	if h := groupIconHash(t, a, "g8"); h != "" {
		t.Fatal("a person's photo must never be cached as a group icon")
	}

	// Force (Refresh everything) re-downloads an unchanged URL.
	other := append(append([]byte{}, testAvatarPNG...), 0x07)
	mock.mu.Lock()
	mock.avatarDownloads = map[string][]byte{iconURL: other}
	mock.mu.Unlock()
	a.fetchGoogleGroupAvatar(groupCandidate("g7", iconURL))
	a.fetchGoogleGroupAvatar(groupCandidate("g7", iconURL))
	c := groupCandidate("g7", iconURL)
	c.Force = true
	a.fetchGoogleGroupAvatar(c)
	mock.mu.Lock()
	dl := mock.avatarDownloadCalls[iconURL]
	mock.mu.Unlock()
	if dl != 3 { // g8 once, g7 once + forced once
		t.Fatalf("downloads = %d, want 3", dl)
	}
}

func TestRepairGroupAvatarsClearsWrongIcons(t *testing.T) {
	a := newTestApp(t, &mockGMClient{})
	put := func(c db.ContactAvatarCandidate, img []byte, urlHash string) {
		t.Helper()
		sum := sha256.Sum256(img)
		if err := a.Store.UpsertGroupAvatar(c, urlHash, img, "image/png", hex.EncodeToString(sum[:]), 1); err != nil {
			t.Fatal(err)
		}
	}
	member := append(append([]byte{}, testAvatarPNG...), 0x01)
	good := append(append([]byte{}, testAvatarPNG...), 0x02)
	noURL := append(append([]byte{}, testAvatarPNG...), 0x03)
	sum := sha256.Sum256(member)
	if err := a.Store.UpsertContactAvatar(db.ContactAvatarCandidate{SourcePlatform: "sms", ParticipantID: "p4"}, member, "image/png", hex.EncodeToString(sum[:]), 1); err != nil {
		t.Fatal(err)
	}
	put(noURLGroupCandidate("g1", false), member, "u1") // same bytes as a member photo
	put(noURLGroupCandidate("g2", false), noURL, "")    // no source URL (thumbnail lookup)
	put(noURLGroupCandidate("g3", false), good, "u3")   // real icon
	v1 := a.Store.AvatarVersion()
	a.RepairGroupAvatars()
	if groupIconHash(t, a, "g1") != "" || groupIconHash(t, a, "g2") != "" {
		t.Fatal("wrong group icons not cleared")
	}
	if groupIconHash(t, a, "g3") == "" {
		t.Fatal("real group icon must be kept")
	}
	if av, _ := a.Store.GetContactAvatar("sms", "p4", "", ""); av == nil || len(av.ImageData) == 0 {
		t.Fatal("member photo must be kept")
	}
	if a.Store.AvatarVersion() <= v1 {
		t.Fatal("repair must bump AvatarVersion")
	}
}
