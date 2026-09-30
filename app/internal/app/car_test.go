package app

import (
	"errors"
	"net/http"
	"testing"

	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
	"google.golang.org/protobuf/proto"

	"github.com/maxghenis/openmessage/internal/db"
)

func carStatus(t *testing.T, err error) int {
	t.Helper()
	var ce *CarError
	if !errors.As(err, &ce) {
		t.Fatalf("expected *CarError, got %T %v", err, err)
	}
	return ce.HTTPStatus()
}

func newDisconnectedApp(t *testing.T) *App {
	t.Helper()
	store, err := db.New(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { store.Close() })
	return &App{Store: store, Logger: zerolog.Nop()}
}

func putMsg(t *testing.T, s *db.Store, id, conv, platform string, fromMe bool) {
	t.Helper()
	if err := s.UpsertMessage(&db.Message{MessageID: id, ConversationID: conv, Body: "hi " + id, TimestampMS: 1000, Status: "INCOMING_COMPLETE", IsFromMe: fromMe, SourcePlatform: platform}); err != nil {
		t.Fatal(err)
	}
}

func TestCarDeleteMessageDeletesOnPhoneThenLocally(t *testing.T) {
	mock := &mockGMClient{}
	a := newTestApp(t, mock)
	putMsg(t, a.Store, "m1", "c1", "sms", false)
	res, err := a.CarDeleteMessage("m1")
	if err != nil {
		t.Fatal(err)
	}
	if res.Scope != "google" || res.ConversationID != "c1" {
		t.Fatalf("result = %+v", res)
	}
	if len(mock.deleteCalls) != 1 || mock.deleteCalls[0] != "m1" {
		t.Fatalf("DeleteMessage calls = %v", mock.deleteCalls)
	}
	if m, _ := a.Store.GetMessageByID("m1"); m != nil {
		t.Fatal("local row not deleted")
	}
}

func TestCarDeleteMessageKeepsRowWhenGoogleFailsOrIsDisconnected(t *testing.T) {
	mock := &mockGMClient{deleteFailure: true}
	a := newTestApp(t, mock)
	putMsg(t, a.Store, "m1", "c1", "sms", false)
	if _, err := a.CarDeleteMessage("m1"); carStatus(t, err) != http.StatusBadGateway {
		t.Fatalf("refused delete err = %v", err)
	}
	mock.deleteFailure = false
	mock.deleteErr = errors.New("boom")
	if _, err := a.CarDeleteMessage("m1"); carStatus(t, err) != http.StatusBadGateway {
		t.Fatalf("errored delete err = %v", err)
	}
	if m, _ := a.Store.GetMessageByID("m1"); m == nil {
		t.Fatal("row deleted although Google did not delete it")
	}

	d := newDisconnectedApp(t)
	putMsg(t, d.Store, "m2", "c1", "", false)
	if _, err := d.CarDeleteMessage("m2"); carStatus(t, err) != http.StatusServiceUnavailable {
		t.Fatalf("disconnected err = %v", err)
	}
	if m, _ := d.Store.GetMessageByID("m2"); m == nil {
		t.Fatal("row deleted while Google was disconnected")
	}
}

func TestCarDeleteMessageLocalOnlyForOtherPlatformsAndPlaceholders(t *testing.T) {
	mock := &mockGMClient{}
	a := newTestApp(t, mock)
	putMsg(t, a.Store, "wa1", "whatsapp:1@s.whatsapp.net", "whatsapp", false)
	putMsg(t, a.Store, "tmp_1", "c1", "sms", true)
	for _, id := range []string{"wa1", "tmp_1"} {
		res, err := a.CarDeleteMessage(id)
		if err != nil || res.Scope != "local" {
			t.Fatalf("%s: %+v %v", id, res, err)
		}
		if m, _ := a.Store.GetMessageByID(id); m != nil {
			t.Fatalf("%s not deleted", id)
		}
	}
	if len(mock.deleteCalls) != 0 {
		t.Fatalf("Google called for local-only deletes: %v", mock.deleteCalls)
	}
	if _, err := a.CarDeleteMessage("missing"); carStatus(t, err) != http.StatusNotFound {
		t.Fatalf("missing err = %v", err)
	}
	if _, err := a.CarDeleteMessage("  "); carStatus(t, err) != http.StatusBadRequest {
		t.Fatalf("empty err = %v", err)
	}
}

func seedCarPeople(t *testing.T, s *db.Store) {
	t.Helper()
	convs := []*db.Conversation{
		{ConversationID: "c-ann", Name: "Ann", LastMessageTS: 3000, Participants: `[{"name":"Ann","number":"+1 (555) 000-1111","id":"p-ann"},{"name":"Me","number":"+15559999999","is_me":true}]`},
		{ConversationID: "c-bob", Name: "Bob", LastMessageTS: 5000, Participants: `[{"name":"Bob","number":"+15550002222","id":"p-bob"}]`},
		{ConversationID: "c-zed", Name: "Zed", LastMessageTS: 1000, Participants: `[{"name":"","number":"+15550003333","id":"p-zed"}]`},
		{ConversationID: "g1", Name: "Team", IsGroup: true, LastMessageTS: 9000, Participants: `[{"name":"Ann","number":"+15550001111"},{"name":"Bob","number":"+15550002222"}]`},
		{ConversationID: "whatsapp:15550004444@s.whatsapp.net", Name: "Wendy", LastMessageTS: 8000, SourcePlatform: "whatsapp", Participants: `[{"name":"Wendy","number":"+15550004444"}]`},
	}
	for _, c := range convs {
		if err := s.UpsertConversation(c); err != nil {
			t.Fatal(err)
		}
	}
	for _, c := range []*db.Contact{
		{ContactID: "k1", Name: "ann smith", Number: "5550001111"},
		{ContactID: "k2", Name: "Carl", Number: "+15550005555"},
		{ContactID: "k3", Name: "Carl dup", Number: "(555) 000-5555"},
	} {
		if err := s.UpsertContact(c); err != nil {
			t.Fatal(err)
		}
	}
}

func TestCarContactsMergesAndSorts(t *testing.T) {
	a := newTestApp(t, &mockGMClient{})
	seedCarPeople(t, a.Store)
	got, err := a.CarContacts()
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, c := range got.All {
		names = append(names, c.Name)
	}
	want := []string{"ann smith", "Bob", "Carl", "Zed"}
	if len(names) != len(want) {
		t.Fatalf("all = %v", names)
	}
	for i := range want {
		if names[i] != want[i] {
			t.Fatalf("all = %v, want %v", names, want)
		}
	}
	if got.All[0].ConversationID != "c-ann" || got.All[0].ParticipantID != "p-ann" || got.All[0].ContactID != "k1" {
		t.Fatalf("ann not merged with her conversation: %+v", got.All[0])
	}
	if got.All[3].Name != "Zed" { // falls back to the conversation name
		t.Fatalf("zed = %+v", got.All[3])
	}
	var top []string
	for _, c := range got.Top {
		top = append(top, c.ConversationID)
	}
	if len(top) != 3 || top[0] != "c-bob" || top[1] != "c-ann" || top[2] != "c-zed" {
		t.Fatalf("top = %v (groups and WhatsApp must be excluded, newest first)", top)
	}
}

func TestCarStartConversationOpensExistingWithoutGoogle(t *testing.T) {
	mock := &mockGMClient{getOrCreateFn: func(*gmproto.GetOrCreateConversationRequest) (*gmproto.GetOrCreateConversationResponse, error) {
		t.Fatal("GetOrCreateConversation must not be called for an existing 1:1")
		return nil, nil
	}}
	a := newTestApp(t, mock)
	seedCarPeople(t, a.Store)
	res, err := a.CarStartConversation([]string{"555-000-1111"}, "")
	if err != nil {
		t.Fatal(err)
	}
	if !res.Existing || res.ConversationID != "c-ann" {
		t.Fatalf("res = %+v", res)
	}
	if _, err := a.CarStartConversation([]string{" ", ""}, ""); carStatus(t, err) != http.StatusBadRequest {
		t.Fatalf("empty err = %v", err)
	}
}

func TestCarStartConversationCreatesThroughGoogle(t *testing.T) {
	var reqs []*gmproto.GetOrCreateConversationRequest
	mock := &mockGMClient{getOrCreateFn: func(req *gmproto.GetOrCreateConversationRequest) (*gmproto.GetOrCreateConversationResponse, error) {
		reqs = append(reqs, req)
		return &gmproto.GetOrCreateConversationResponse{Conversation: &gmproto.Conversation{
			ConversationID: "new1",
			Participants: []*gmproto.Participant{
				{FullName: "Dana", ID: &gmproto.SmallInfo{Number: "+15550006666", ParticipantID: "p-dana"}},
				{IsMe: true, ID: &gmproto.SmallInfo{Number: "+15559999999"}},
			},
		}}, nil
	}}
	a := newTestApp(t, mock)
	res, err := a.CarStartConversation([]string{"+15550006666"}, "")
	if err != nil {
		t.Fatal(err)
	}
	if res.Existing || res.ConversationID != "new1" || res.Name != "Dana" || res.IsGroup {
		t.Fatalf("res = %+v", res)
	}
	if len(reqs) != 1 || len(reqs[0].Numbers) != 1 || reqs[0].Numbers[0].Number != "+15550006666" || reqs[0].CreateRCSGroup != nil {
		t.Fatalf("requests = %+v", reqs)
	}
	if c, _ := a.Store.GetConversation("new1"); c == nil {
		t.Fatal("new conversation not stored locally")
	}
	if _, err := newDisconnectedApp(t).CarStartConversation([]string{"+15550006666"}, ""); carStatus(t, err) != http.StatusServiceUnavailable {
		t.Fatalf("disconnected err = %v", err)
	}
}

func TestCarStartGroupRetriesAsRCSGroup(t *testing.T) {
	var reqs []*gmproto.GetOrCreateConversationRequest
	mock := &mockGMClient{getOrCreateFn: func(req *gmproto.GetOrCreateConversationRequest) (*gmproto.GetOrCreateConversationResponse, error) {
		reqs = append(reqs, proto.Clone(req).(*gmproto.GetOrCreateConversationRequest))
		if req.CreateRCSGroup == nil {
			return &gmproto.GetOrCreateConversationResponse{Status: gmproto.GetOrCreateConversationResponse_CREATE_RCS}, nil
		}
		return &gmproto.GetOrCreateConversationResponse{Conversation: &gmproto.Conversation{ConversationID: "grp", IsGroupChat: true, Name: "Crew"}}, nil
	}}
	a := newTestApp(t, mock)
	res, err := a.CarStartConversation([]string{"+15550001111", "+1 555 000 1111", "+15550002222"}, "")
	if err != nil {
		t.Fatal(err)
	}
	if res.ConversationID != "grp" || !res.IsGroup {
		t.Fatalf("res = %+v", res)
	}
	if len(reqs) != 2 || len(reqs[0].Numbers) != 2 {
		t.Fatalf("requests = %d (dedupe numbers, then retry)", len(reqs))
	}
	if reqs[1].CreateRCSGroup == nil || !*reqs[1].CreateRCSGroup || reqs[1].RCSGroupName == nil {
		t.Fatalf("retry = %+v", reqs[1])
	}
}

func TestCarFolderConversationsIsReadOnlyAndSplitsSpamBlocked(t *testing.T) {
	mock := &mockGMClient{conversations: map[gmproto.ListConversationsRequest_Folder][][]*gmproto.Conversation{
		gmproto.ListConversationsRequest_ARCHIVE: {{
			{ConversationID: "a1", Name: "Old", LastMessageTimestamp: 2000000, Status: gmproto.ConversationStatus_ARCHIVED,
				LatestMessage: &gmproto.LatestMessage{DisplayContent: "see ya"}},
		}, {
			{ConversationID: "a2", Name: "Older", LastMessageTimestamp: 1000000, Status: gmproto.ConversationStatus_KEEP_ARCHIVED},
		}},
		gmproto.ListConversationsRequest_SPAM_BLOCKED: {{
			{ConversationID: "s1", Name: "Spammer", Status: gmproto.ConversationStatus_SPAM_FOLDER},
			{ConversationID: "b1", Name: "Blocked guy", Status: gmproto.ConversationStatus_BLOCKED_FOLDER},
		}},
	}}
	a := newTestApp(t, mock)
	if err := a.Store.UpsertConversation(&db.Conversation{ConversationID: "a1", Name: "Old"}); err != nil {
		t.Fatal(err)
	}
	before, _ := a.Store.ListConversations(100)

	arch, err := a.CarFolderConversations("Archived")
	if err != nil {
		t.Fatal(err)
	}
	if len(arch) != 2 || arch[0].ConversationID != "a1" || arch[1].ConversationID != "a2" {
		t.Fatalf("archived = %+v", arch)
	}
	if !arch[0].Local || arch[1].Local || arch[0].LastMessagePreview != "see ya" || arch[0].LastMessageTS != 2000 || arch[0].Folder != "archived" {
		t.Fatalf("archived[0] = %+v archived[1] = %+v", arch[0], arch[1])
	}
	spam, err := a.CarFolderConversations("spam")
	if err != nil || len(spam) != 1 || spam[0].ConversationID != "s1" {
		t.Fatalf("spam = %+v %v", spam, err)
	}
	blocked, err := a.CarFolderConversations("blocked")
	if err != nil || len(blocked) != 1 || blocked[0].ConversationID != "b1" || blocked[0].Status != "BLOCKED_FOLDER" {
		t.Fatalf("blocked = %+v %v", blocked, err)
	}
	after, _ := a.Store.ListConversations(100)
	if len(after) != len(before) {
		t.Fatalf("folder listing wrote to the local DB: %d -> %d conversations", len(before), len(after))
	}
	if _, err := a.CarFolderConversations("inbox"); carStatus(t, err) != http.StatusBadRequest {
		t.Fatalf("unknown folder err = %v", err)
	}
	if _, err := newDisconnectedApp(t).CarFolderConversations("archived"); carStatus(t, err) != http.StatusServiceUnavailable {
		t.Fatalf("disconnected err = %v", err)
	}
}

func TestCarFolderMessagesLiveNewestFirst(t *testing.T) {
	del := makeMsg("gone", "a2", "deleted", 3000)
	del.MessageStatus = &gmproto.MessageStatus{Status: gmproto.MessageStatusType_MESSAGE_DELETED}
	out := makeMsg("m2", "a2", "mine", 2000)
	out.MessageStatus = &gmproto.MessageStatus{Status: gmproto.MessageStatusType_OUTGOING_DISPLAYED}
	mock := &mockGMClient{messages: map[string][][]*gmproto.Message{
		"a2": {{makeMsg("m1", "a2", "hello", 1000), out, del}},
	}}
	a := newTestApp(t, mock)
	msgs, err := a.CarFolderMessages("a2")
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 2 || msgs[0].MessageID != "m2" || !msgs[0].IsFromMe || msgs[0].Status != "OUTGOING_DISPLAYED" || msgs[1].Body != "hello" {
		t.Fatalf("msgs = %+v", msgs)
	}
	if n, _ := a.Store.GetMessagesByConversation("a2", 10); len(n) != 0 {
		t.Fatal("folder messages must not be stored")
	}
}

func TestStoreMessageSkipsDeletedMessages(t *testing.T) {
	a := newTestApp(t, &mockGMClient{})
	m := makeMsg("gone", "c1", "deleted text", 1000)
	m.MessageStatus = &gmproto.MessageStatus{Status: gmproto.MessageStatusType_MESSAGE_DELETED}
	a.storeMessage(m)
	if got, _ := a.Store.GetMessageByID("gone"); got != nil {
		t.Fatal("MESSAGE_DELETED message was stored")
	}
}

func TestCarPhoneKey(t *testing.T) {
	cases := map[string]string{
		"+1 (555) 000-1111": "5550001111",
		"5550001111":        "5550001111",
		"12345":             "12345",
		"Ann@Example.com":   "ann@example.com",
	}
	for in, want := range cases {
		if got := carPhoneKey(in); got != want {
			t.Fatalf("carPhoneKey(%q) = %q, want %q", in, got, want)
		}
	}
}
