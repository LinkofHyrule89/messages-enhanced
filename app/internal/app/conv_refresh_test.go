package app

import (
	"errors"
	"fmt"
	"testing"
	"time"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

func refreshTestConv(id, name, iconURL string) *gmproto.Conversation {
	return &gmproto.Conversation{
		ConversationID: id, Name: name, IsGroupChat: true, GroupAvatarURL: iconURL,
		Participants: []*gmproto.Participant{
			{ID: &gmproto.SmallInfo{ParticipantID: "p1", Number: "+15555550101"}, FullName: "A"},
			{ID: &gmproto.SmallInfo{ParticipantID: "p2", Number: "+15555550102"}, FullName: "B"},
			{ID: &gmproto.SmallInfo{ParticipantID: "me", Number: "+15555550100"}, IsMe: true},
		},
	}
}

func httpStatusOf(err error) int {
	var s interface{ HTTPStatus() int }
	if errors.As(err, &s) {
		return s.HTTPStatus()
	}
	return 0
}

func TestRefreshConversationRefetchesOnlyThatConversation(t *testing.T) {
	const iconURL = "https://lh3.googleusercontent.com/refresh-icon"
	mock := &mockGMClient{
		avatarDownloads:       map[string][]byte{iconURL: testAvatarPNG},
		participantThumbnails: map[string][]byte{},
		phoneConvs:            map[string]*gmproto.Conversation{},
		messages:              map[string][][]*gmproto.Message{},
	}
	a := newTestApp(t, mock)
	for _, id := range []string{"g-a", "g-b"} {
		if _, err := a.storeConversationSnapshot(refreshTestConv(id, "Old "+id, iconURL), false); err != nil {
			t.Fatal(err)
		}
	}
	// Icon already cached for g-a: a normal sync would skip the same URL.
	a.fetchGoogleGroupAvatar(groupCandidate("g-a", iconURL))

	mock.mu.Lock()
	mock.phoneConvs["g-a"] = refreshTestConv("g-a", "Renamed", iconURL)
	mock.phoneConvs["g-b"] = refreshTestConv("g-b", "Also renamed", iconURL)
	mock.messages["g-a"] = [][]*gmproto.Message{{makeMsg("m1", "g-a", "hi", 1000), makeMsg("m2", "g-a", "there", 2000)}}
	mock.messages["g-b"] = [][]*gmproto.Message{{makeMsg("m3", "g-b", "other", 3000)}}
	mock.mu.Unlock()

	res, retry, err := a.RefreshConversation("g-a")
	if err != nil || retry != 0 || res == nil {
		t.Fatalf("refresh: %+v %d %v", res, retry, err)
	}
	if res.Members != 3 || res.Messages != 2 || res.Photos < 1 || res.Deleted || res.AvatarVersion == 0 {
		t.Fatalf("result = %+v", res)
	}
	if c, _ := a.Store.GetConversation("g-a"); c == nil || c.Name != "Renamed" {
		t.Fatalf("g-a not refreshed: %+v", c)
	}
	if c, _ := a.Store.GetConversation("g-b"); c == nil || c.Name != "Old g-b" {
		t.Fatalf("g-b must not be touched: %+v", c)
	}
	if msgs, _ := a.Store.GetMessagesByConversation("g-a", 10); len(msgs) != 2 {
		t.Fatalf("g-a messages = %d, want 2", len(msgs))
	}
	if msgs, _ := a.Store.GetMessagesByConversation("g-b", 10); len(msgs) != 0 {
		t.Fatalf("g-b messages fetched: %d", len(msgs))
	}
	mock.mu.Lock()
	dl := mock.avatarDownloadCalls[iconURL]
	mock.mu.Unlock()
	if dl != 2 { // initial + forced re-download for g-a only
		t.Fatalf("icon downloads = %d, want 2", dl)
	}
	if av, _ := a.Store.GetContactAvatar("sms", "conv:g-a", "", ""); av == nil || len(av.ImageData) == 0 {
		t.Fatal("g-a icon missing after refresh")
	}

	// Same conversation again right away: rate limited; another one is fine.
	if _, retry, err := a.RefreshConversation("g-a"); retry <= 0 || retry > 31 || !errors.Is(err, ErrRefreshRateLimited) {
		t.Fatalf("second refresh: retry=%d err=%v", retry, err)
	}
	if _, retry, err := a.RefreshConversation("g-b"); err != nil || retry != 0 {
		t.Fatalf("other conversation: retry=%d err=%v", retry, err)
	}
}

func TestRefreshConversationErrors(t *testing.T) {
	mock := &mockGMClient{phoneConvs: map[string]*gmproto.Conversation{}, participantThumbnails: map[string][]byte{}}
	a := newTestApp(t, mock)
	if _, err := a.storeConversationSnapshot(refreshTestConv("g-c", "C", ""), false); err != nil {
		t.Fatal(err)
	}
	for id, want := range map[string]int{"": 400, "  ": 400, "nope": 404, "g-c": 502} {
		if _, _, err := a.RefreshConversation(id); httpStatusOf(err) != want {
			t.Fatalf("%q: err=%v status=%d want %d", id, err, httpStatusOf(err), want)
		}
	}
	a.gmClient = nil
	if _, _, err := a.RefreshConversation("g-c"); httpStatusOf(err) != 503 {
		t.Fatalf("disconnected: %v", err)
	}
}

func TestConvRefreshLimiter(t *testing.T) {
	var l convRefreshLimiter
	t0 := time.Unix(1_000_000, 0)
	if w := l.reserve("a", t0); w != 0 {
		t.Fatalf("first: %d", w)
	}
	if w := l.reserve("b", t0); w == 0 {
		t.Fatal("concurrent refresh must wait")
	}
	l.done()
	if w := l.reserve("a", t0.Add(10*time.Second)); w < 20 || w > 21 {
		t.Fatalf("per-conversation wait = %d", w)
	}
	l.done() // harmless when not running
	if w := l.reserve("a", t0.Add(31*time.Second)); w != 0 {
		t.Fatalf("after 31s: %d", w)
	}
	l.done()
	// Global: 6 per minute (a twice already counted).
	for i := 0; i < 4; i++ {
		if w := l.reserve(fmt.Sprintf("c%d", i), t0.Add(32*time.Second)); w != 0 {
			t.Fatalf("c%d: %d", i, w)
		}
		l.done()
	}
	if w := l.reserve("z", t0.Add(33*time.Second)); w == 0 {
		t.Fatal("7th refresh within a minute must wait")
	}
	if w := l.reserve("z", t0.Add(61*time.Second)); w != 0 {
		t.Fatalf("after the window: %d", w)
	}
}
