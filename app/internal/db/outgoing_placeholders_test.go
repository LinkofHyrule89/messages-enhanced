package db

import (
	"testing"
	"time"
)

const phBase = int64(1_790_738_307_000) // ~2026-09-29 21:18 MDT

// androidSendUUID is the shape of IdempotencyKeys.newKey() on the Android app.
// transportRequestID is the shape of messaging.CryptoIDSource.
const (
	androidSendUUID    = "6ba7b810-9dad-41d1-80b4-00c04fd430c8"
	transportRequestID = "0123456789abcdef0123456789abcdef"
)

func placeholderMsg(id, conv, body string, ts int64) *Message {
	return &Message{MessageID: id, ConversationID: conv, Body: body, TimestampMS: ts, Status: "OUTGOING_SENDING", IsFromMe: true}
}

func realOwnMsg(id, conv, body string, ts int64) *Message {
	return &Message{MessageID: id, ConversationID: conv, Body: body, TimestampMS: ts, Status: "OUTGOING_DELIVERED", IsFromMe: true}
}

func withMedia(m *Message, mediaID, mime string) *Message {
	m.MediaID, m.MimeType = mediaID, mime
	return m
}

func mustStore(t *testing.T, s *Store, msgs ...*Message) {
	t.Helper()
	for _, m := range msgs {
		if err := s.UpsertMessage(m); err != nil {
			t.Fatalf("upsert %s: %v", m.MessageID, err)
		}
	}
}

func assertPresent(t *testing.T, s *Store, id string, want bool) {
	t.Helper()
	got, err := s.MessageExists(id)
	if err != nil {
		t.Fatalf("exists %s: %v", id, err)
	}
	if got != want {
		t.Fatalf("message %s present = %v, want %v", id, got, want)
	}
}

func TestOutgoingPlaceholderMatches(t *testing.T) {
	cases := []struct {
		name string
		p, r *Message
		want bool
	}{
		{"text same trimmed body", placeholderMsg("tm-1", "5", "On my way ", phBase), realOwnMsg("g1", "5", "On my way", phBase+1500), true},
		{"text different body", placeholderMsg("tm-1", "5", "On my way", phBase), realOwnMsg("g1", "5", "Almost there", phBase+1500), false},
		{"text different conversation", placeholderMsg("tm-1", "5", "On my way", phBase), realOwnMsg("g1", "6", "On my way", phBase+1500), false},
		{"text outside window", placeholderMsg("tm-1", "5", "On my way", phBase), realOwnMsg("g1", "5", "On my way", phBase+OutgoingPlaceholderMatchWindowMS+1), false},
		{"text real earlier, inside window", placeholderMsg("tm-1", "5", "On my way", phBase), realOwnMsg("g1", "5", "On my way", phBase-2000), true},
		{"media png vs png", withMedia(placeholderMsg("tm-1", "5", "", phBase), "up1", "image/png"), withMedia(realOwnMsg("192058", "5", "", phBase+1000), "m1", "image/png"), true},
		{"media png vs jpeg (re-encoded)", withMedia(placeholderMsg("tm-1", "5", "", phBase), "up1", "image/png"), withMedia(realOwnMsg("g1", "5", "", phBase+1000), "m1", "image/jpeg"), true},
		{"media octet-stream", withMedia(placeholderMsg("tm-1", "5", "", phBase), "up1", "image/png"), withMedia(realOwnMsg("g1", "5", "", phBase+1000), "m1", "application/octet-stream"), true},
		{"media image vs video", withMedia(placeholderMsg("tm-1", "5", "", phBase), "up1", "image/png"), withMedia(realOwnMsg("g1", "5", "", phBase+1000), "m1", "video/mp4"), false},
		{"media vs text", withMedia(placeholderMsg("tm-1", "5", "", phBase), "up1", "image/png"), realOwnMsg("g1", "5", "hi", phBase+1000), false},
		{"empty text never matches", placeholderMsg("tm-1", "5", "  ", phBase), realOwnMsg("g1", "5", "", phBase+1000), false},
		{"failed placeholder", &Message{MessageID: "tm-1", ConversationID: "5", Body: "hi", TimestampMS: phBase, Status: "OUTGOING_FAILED:FAILURE_2", IsFromMe: true}, realOwnMsg("g1", "5", "hi", phBase+1000), false},
		{"tmp_ server/MCP placeholder", placeholderMsg("tmp_000000000001", "5", "hi", phBase), realOwnMsg("g1", "5", "hi", phBase+1000), true},
		{"android uuid idempotency key", placeholderMsg(androidSendUUID, "5", "hi", phBase), realOwnMsg("g1", "5", "hi", phBase+1000), true},
		{"v2 transport request id", placeholderMsg(transportRequestID, "5", "hi", phBase), realOwnMsg("g1", "5", "hi", phBase+1000), true},
		{"not a placeholder id", placeholderMsg("msg-local-1", "5", "hi", phBase), realOwnMsg("g1", "5", "hi", phBase+1000), false},
		{"real is another placeholder", placeholderMsg("tm-1", "5", "hi", phBase), placeholderMsg("tm-2", "5", "hi", phBase+1000), false},
		{"real is incoming", placeholderMsg("tm-1", "5", "hi", phBase), &Message{MessageID: "g1", ConversationID: "5", Body: "hi", TimestampMS: phBase + 1000, Status: "INCOMING_COMPLETE"}, false},
	}
	for _, tc := range cases {
		if got := OutgoingPlaceholderMatches(tc.p, tc.r); got != tc.want {
			t.Errorf("%s: got %v, want %v", tc.name, got, tc.want)
		}
	}
}

func TestReconcileOwnEcho_TextMatchWithoutTmpID(t *testing.T) {
	s := newTestStore(t)
	mustStore(t, s, placeholderMsg("tm-1790738302359-1kwiyqpn", "5", "Running late", phBase))
	real := realOwnMsg("192001", "5", "Running late", phBase+1200)
	mustStore(t, s, real)
	removed, err := s.ReconcileOwnEcho(real, "", true)
	if err != nil {
		t.Fatal(err)
	}
	if removed != "tm-1790738302359-1kwiyqpn" {
		t.Fatalf("removed = %q", removed)
	}
	assertPresent(t, s, "tm-1790738302359-1kwiyqpn", false)
	assertPresent(t, s, "192001", true)
}

func TestReconcileOwnEcho_MediaMatch(t *testing.T) {
	s := newTestStore(t)
	mustStore(t, s, withMedia(placeholderMsg("tm-a", "5", "", phBase), "upload-1", "image/png"))
	real := withMedia(realOwnMsg("192058", "5", "", phBase+1000), "media-1", "image/png")
	mustStore(t, s, real)
	if removed, err := s.ReconcileOwnEcho(real, "", true); err != nil || removed != "tm-a" {
		t.Fatalf("removed = %q, err = %v", removed, err)
	}
	assertPresent(t, s, "tm-a", false)
}

func TestReconcileOwnEcho_NoMatchKeepsPlaceholders(t *testing.T) {
	s := newTestStore(t)
	mustStore(t, s,
		placeholderMsg("tm-other-conv", "6", "Running late", phBase),
		placeholderMsg("tm-other-body", "5", "See you soon", phBase),
		placeholderMsg("tm-too-old", "5", "Running late", phBase-OutgoingPlaceholderMatchWindowMS-5000),
		&Message{MessageID: "tm-failed", ConversationID: "5", Body: "Running late", TimestampMS: phBase, Status: "OUTGOING_FAILED:FAILURE_2", IsFromMe: true},
	)
	real := realOwnMsg("192001", "5", "Running late", phBase+1000)
	mustStore(t, s, real)
	if removed, err := s.ReconcileOwnEcho(real, "", true); err != nil || removed != "" {
		t.Fatalf("removed = %q, err = %v; want nothing", removed, err)
	}
	for _, id := range []string{"tm-other-conv", "tm-other-body", "tm-too-old", "tm-failed"} {
		assertPresent(t, s, id, true)
	}
}

func TestReconcileOwnEcho_ExactTmpIDFirstAndAtMostOne(t *testing.T) {
	s := newTestStore(t)
	mustStore(t, s,
		placeholderMsg("tm-far", "5", "ok", phBase-60000),
		placeholderMsg("tm-near", "5", "ok", phBase-1000),
	)
	real := realOwnMsg("g1", "5", "ok", phBase)
	mustStore(t, s, real)
	// Exact TmpID wins even though tm-near is closer.
	if removed, _ := s.ReconcileOwnEcho(real, "tm-far", true); removed != "tm-far" {
		t.Fatalf("exact TmpID: removed %q", removed)
	}
	assertPresent(t, s, "tm-near", true)

	// A TmpID that points at nothing falls back to the closest match only.
	mustStore(t, s, placeholderMsg("tm-far2", "5", "ok", phBase-90000))
	real2 := realOwnMsg("g2", "5", "ok", phBase+100)
	mustStore(t, s, real2)
	if removed, _ := s.ReconcileOwnEcho(real2, "tm-gone", true); removed != "tm-near" {
		t.Fatalf("fallback: removed %q, want tm-near", removed)
	}
	assertPresent(t, s, "tm-far2", true)
}

func TestReconcileOwnEcho_StatusRedeliveryDoesNotConsumePlaceholder(t *testing.T) {
	s := newTestStore(t)
	mustStore(t, s, placeholderMsg("tm-second-ok", "5", "ok", phBase+30000))
	real := realOwnMsg("g1", "5", "ok", phBase)
	mustStore(t, s, real)
	// isNew=false: a DELIVERED/READ update for an already-stored message.
	if removed, _ := s.ReconcileOwnEcho(real, "", false); removed != "" {
		t.Fatalf("status re-delivery removed %q", removed)
	}
	assertPresent(t, s, "tm-second-ok", true)
}

func TestDeleteOutgoingPlaceholderIfEchoed(t *testing.T) {
	s := newTestStore(t)
	// Echo stored first (it won the race), then the placeholder.
	mustStore(t, s, realOwnMsg("g-early", "5", "ok", phBase-60000)) // an earlier identical send
	mustStore(t, s, placeholderMsg("tm-x", "5", "ok", phBase))
	sendStart := phBase - 800
	if realID, err := s.DeleteOutgoingPlaceholderIfEchoed("tm-x", sendStart-5000); err != nil || realID != "" {
		t.Fatalf("earlier identical message must not count: %q %v", realID, err)
	}
	assertPresent(t, s, "tm-x", true)

	mustStore(t, s, realOwnMsg("g-now", "5", "ok", phBase-200))
	if realID, err := s.DeleteOutgoingPlaceholderIfEchoed("tm-x", sendStart-5000); err != nil || realID != "g-now" {
		t.Fatalf("realID = %q, err = %v", realID, err)
	}
	assertPresent(t, s, "tm-x", false)

	// Server/MCP tmp_ placeholders get the same send-side race check.
	mustStore(t, s, placeholderMsg("tmp_000000000001", "5", "mcp hi", phBase+100000))
	mustStore(t, s, realOwnMsg("g-mcp", "5", "mcp hi", phBase+100200))
	if realID, err := s.DeleteOutgoingPlaceholderIfEchoed("tmp_000000000001", phBase+100000-5000); err != nil || realID != "g-mcp" {
		t.Fatalf("tmp_ realID = %q, err = %v", realID, err)
	}
	assertPresent(t, s, "tmp_000000000001", false)
	if realID, _ := s.DeleteOutgoingPlaceholderIfEchoed("msg-not-a-placeholder", 0); realID != "" {
		t.Fatalf("non-placeholder id handled: %q", realID)
	}

	// The Android app's idempotency key is a UUID, not tm-/tmp_. The echo can
	// win the race for that row too.
	mustStore(t, s, placeholderMsg(androidSendUUID, "5", "from the app", phBase+200000))
	mustStore(t, s, realOwnMsg("g-android", "5", "from the app", phBase+200400))
	if realID, err := s.DeleteOutgoingPlaceholderIfEchoed(androidSendUUID, phBase+200000-5000); err != nil || realID != "g-android" {
		t.Fatalf("android uuid realID = %q, err = %v", realID, err)
	}
	assertPresent(t, s, androidSendUUID, false)
}

func TestDeleteKnownOutgoingPlaceholderIfEchoed(t *testing.T) {
	s := newTestStore(t)
	mustStore(t, s, placeholderMsg("request-hex", "5", "on my way", phBase))
	mustStore(t, s, realOwnMsg("g-real", "5", "on my way", phBase+500))
	if realID, err := s.DeleteKnownOutgoingPlaceholderIfEchoed("request-hex", phBase-5000); err != nil || realID != "g-real" {
		t.Fatalf("realID = %q, err = %v", realID, err)
	}
	assertPresent(t, s, "request-hex", false)

	// The prefix-only helper must not treat an arbitrary id as a placeholder.
	mustStore(t, s, placeholderMsg("request-hex-2", "5", "later", phBase+10000))
	mustStore(t, s, realOwnMsg("g-real-2", "5", "later", phBase+10500))
	if realID, err := s.DeleteOutgoingPlaceholderIfEchoed("request-hex-2", phBase); err != nil || realID != "" {
		t.Fatalf("prefix helper realID = %q, err = %v", realID, err)
	}
	assertPresent(t, s, "request-hex-2", true)
}

func TestGetMessagesByConversationPrefersRealRowOverPlaceholder(t *testing.T) {
	s := newTestStore(t)
	now := time.Now().UnixMilli()
	mustStore(t, s,
		placeholderMsg("tmp_264794425382", "4", "group hi", now),
		realOwnMsg("200342", "4", "group hi", now+307),
	)
	msgs, err := s.GetMessagesByConversation("4", 20)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 1 || msgs[0].MessageID != "200342" {
		t.Fatalf("messages = %+v, want only 200342", msgs)
	}
	if left, _ := s.GetMessageByID("tmp_264794425382"); left == nil {
		t.Fatal("read dedupe must not delete the placeholder row")
	}
}

func TestExpireUnmatchedOutgoingPlaceholder(t *testing.T) {
	s := newTestStore(t)
	now := time.Now().UnixMilli()
	mustStore(t, s,
		placeholderMsg("tmp_stale", "4", "never echoed", now-10*60*1000),
		placeholderMsg("tmp_fresh", "4", "still sending", now),
		realOwnMsg("kept-failed", "4", "nope", now-10*60*1000),
	)
	// realOwnMsg is OUTGOING_DELIVERED, not a placeholder. A failed send stays.
	failed := placeholderMsg("tmp_failed", "4", "carrier rejected", now-10*60*1000)
	failed.Status = "OUTGOING_FAILED"
	mustStore(t, s, failed)

	if n, err := s.ExpireUnmatchedOutgoingPlaceholders([]string{"4"}, now); err != nil || n != 1 {
		t.Fatalf("expired %d, %v", n, err)
	}
	assertPresent(t, s, "tmp_stale", false)
	assertPresent(t, s, "tmp_fresh", true)
	assertPresent(t, s, "tmp_failed", true)
	assertPresent(t, s, "kept-failed", true)
}

func TestDedupePersonMessagesPrefersRealEcho(t *testing.T) {
	now := time.Now().UnixMilli()
	got := dedupePersonMessages([]*Message{
		{MessageID: "tmp_264794425382", Body: "group hi", TimestampMS: now, IsFromMe: true},
		{MessageID: "200342", Body: "group hi", TimestampMS: now + 307, IsFromMe: true},
	})
	if len(got) != 1 || got[0].MessageID != "200342" {
		t.Fatalf("deduped = %+v", got)
	}
	got = dedupePersonMessages([]*Message{
		{MessageID: androidSendUUID, Body: "from the app", TimestampMS: now, IsFromMe: true, Status: "OUTGOING_SENDING"},
		{MessageID: "200500", Body: "from the app", TimestampMS: now + 400, IsFromMe: true, Status: "OUTGOING_COMPLETE", SenderNumber: "+15551230000"},
	})
	if len(got) != 1 || got[0].MessageID != "200500" {
		t.Fatalf("android uuid deduped = %+v", got)
	}
}

func TestCleanupMatchedOutgoingPlaceholders(t *testing.T) {
	s := newTestStore(t)
	mustStore(t, s,
		// The 9:18 PM picture: placeholder + Google's delivered copy.
		withMedia(placeholderMsg("tm-1790738302359-1kwiyqpn", "5", "", phBase), "up", "image/png"),
		withMedia(realOwnMsg("192058", "5", "", phBase+1000), "m", "image/png"),
		// Three text sends from 8:20 PM with real copies.
		placeholderMsg("tm-t1", "5", "Leaving now", phBase-3600000),
		realOwnMsg("191900", "5", "Leaving now", phBase-3600000+900),
		placeholderMsg("tm-t2", "7", "ok", phBase-3590000),
		realOwnMsg("191901", "7", "ok", phBase-3590000+700),
		placeholderMsg("tm-t3", "7", "ok", phBase-3580000),
		realOwnMsg("191902", "7", "ok", phBase-3580000+600),
		// Genuinely failed / never delivered sends: no real copy. Must stay.
		placeholderMsg("tm-lost", "5", "Did this send?", phBase-100000),
		&Message{MessageID: "tm-failed", ConversationID: "5", Body: "Leaving now", TimestampMS: phBase - 3600000, Status: "OUTGOING_FAILED:FAILURE_2", IsFromMe: true},
		// Two identical placeholders but only one real copy: only one goes.
		placeholderMsg("tm-dup-a", "8", "yes", phBase),
		placeholderMsg("tm-dup-b", "8", "yes", phBase+20000),
		realOwnMsg("191950", "8", "yes", phBase+19000),
	)
	n, err := s.CleanupMatchedOutgoingPlaceholders()
	if err != nil {
		t.Fatal(err)
	}
	if n != 5 {
		t.Fatalf("removed %d, want 5", n)
	}
	for _, id := range []string{"tm-1790738302359-1kwiyqpn", "tm-t1", "tm-t2", "tm-t3", "tm-dup-b"} {
		assertPresent(t, s, id, false)
	}
	for _, id := range []string{"tm-lost", "tm-failed", "tm-dup-a", "192058", "191900", "191901", "191902", "191950"} {
		assertPresent(t, s, id, true)
	}
}

func TestCleanupMatchedOutgoingPlaceholdersOnce(t *testing.T) {
	s := newTestStore(t)
	mustStore(t, s,
		placeholderMsg("tm-dup-a", "8", "yes", phBase),
		placeholderMsg("tm-dup-b", "8", "yes", phBase+20000),
		realOwnMsg("191950", "8", "yes", phBase+19000),
	)
	ran, n, err := s.CleanupMatchedOutgoingPlaceholdersOnce()
	if err != nil || !ran || n != 1 {
		t.Fatalf("first run: ran=%v removed=%d err=%v", ran, n, err)
	}
	// Second start: does not run again, so tm-dup-a (whose would-be match was
	// already used by tm-dup-b) is not paired with the same real message.
	ran, n, err = s.CleanupMatchedOutgoingPlaceholdersOnce()
	if err != nil || ran || n != 0 {
		t.Fatalf("second run: ran=%v removed=%d err=%v", ran, n, err)
	}
	assertPresent(t, s, "tm-dup-a", true)
	assertPresent(t, s, "tm-dup-b", false)
}

func TestReconcileOwnEcho_TmpServerPlaceholder(t *testing.T) {
	s := newTestStore(t)
	mustStore(t, s, placeholderMsg("tmp_012345678901", "5", "From Grok", phBase))
	real := realOwnMsg("192500", "5", "From Grok", phBase+800)
	mustStore(t, s, real)
	// No TmpID on the echo (backfill / race); content match must still clear tmp_.
	removed, err := s.ReconcileOwnEcho(real, "", true)
	if err != nil {
		t.Fatal(err)
	}
	if removed != "tmp_012345678901" {
		t.Fatalf("removed = %q, want tmp_ placeholder", removed)
	}
	assertPresent(t, s, "tmp_012345678901", false)
	assertPresent(t, s, "192500", true)
}

func TestAndroidUUIDPlaceholderDoesNotLeakIntoThreadReads(t *testing.T) {
	s := newTestStore(t)
	now := time.Now().UnixMilli()
	withSender := placeholderMsg(transportRequestID, "5", "real still sending", now-10*60*1000)
	withSender.SenderNumber = "+15551230000"
	mustStore(t, s,
		placeholderMsg(androidSendUUID, "5", "from the app", now-10*60*1000),
		realOwnMsg("200500", "5", "from the app", now-10*60*1000+400),
		placeholderMsg("11111111-2222-4333-8444-555555555555", "5", "still sending", now),
		realOwnMsg("200501", "5", "still sending", now+300),
		withSender,
	)
	// A Google id that happens to look like a transport request id, and still
	// says Sending, is not an optimistic row when it has a sender.
	if OutgoingPlaceholderMatches(withSender, realOwnMsg("200500", "5", "real still sending", now-10*60*1000+100)) {
		t.Fatal("a sent Google row must not match as a placeholder")
	}

	msgs, err := s.GetMessagesByConversation("5", 20)
	if err != nil {
		t.Fatal(err)
	}
	got := map[string]bool{}
	for _, m := range msgs {
		got[m.MessageID] = true
	}
	if got[androidSendUUID] || got["11111111-2222-4333-8444-555555555555"] {
		t.Fatalf("thread read still contains placeholders: %+v", msgs)
	}
	if !got["200500"] || !got["200501"] || !got[transportRequestID] {
		t.Fatalf("thread read = %+v", msgs)
	}
	// Older than the match window: the read deletes it. The fresh one is only
	// hidden, so a send that is still in flight can still be reconciled.
	assertPresent(t, s, androidSendUUID, false)
	assertPresent(t, s, "11111111-2222-4333-8444-555555555555", true)
	assertPresent(t, s, transportRequestID, true)
}

func TestReconcileOwnEcho_AndroidUUIDAndDoesNotDeleteRealTmpID(t *testing.T) {
	s := newTestStore(t)
	realNamed := placeholderMsg(transportRequestID, "5", "older", phBase)
	realNamed.Status = "OUTGOING_SENDING"
	realNamed.SenderNumber = "+15551230000"
	mustStore(t, s,
		placeholderMsg(androidSendUUID, "5", "on my way", phBase+1000),
		realNamed,
	)
	echo := realOwnMsg("200100", "5", "on my way", phBase+1500)
	mustStore(t, s, echo)
	// The echo names the real message, the way a group/MMS update sometimes
	// does. That row stays. The Android placeholder is removed by content.
	removed, err := s.ReconcileOwnEchoIDs(echo, []string{transportRequestID}, true)
	if err != nil {
		t.Fatal(err)
	}
	if removed != androidSendUUID {
		t.Fatalf("removed = %q, want the android placeholder", removed)
	}
	assertPresent(t, s, transportRequestID, true)
	assertPresent(t, s, "200100", true)

	// The usual path: the echo's TmpID is the UUID itself.
	mustStore(t, s, placeholderMsg(androidSendUUID, "5", "second", phBase+5000))
	second := realOwnMsg("200101", "5", "second", phBase+5400)
	removed, err = s.ReconcileOwnEchoIDs(second, []string{androidSendUUID}, true)
	if err != nil || removed != androidSendUUID {
		t.Fatalf("exact uuid removed = %q, err = %v", removed, err)
	}
	assertPresent(t, s, androidSendUUID, false)
}

func TestCleanupClientIdempotencyPlaceholdersOnce(t *testing.T) {
	s := newTestStore(t)
	now := time.Now().UnixMilli()
	stuck := placeholderMsg(androidSendUUID, "5", "stuck sending", now-10*60*1000)
	fresh := placeholderMsg("11111111-2222-4333-8444-555555555555", "5", "in flight", now-1000)
	matched := placeholderMsg("cccccccc-dddd-4eee-8fff-aaaaaaaaaaaa", "6", "hello", now-2000)
	kept := placeholderMsg(transportRequestID, "5", "google id", now-10*60*1000)
	kept.SenderNumber = "+15551230000"
	mustStore(t, s, stuck, fresh, matched, realOwnMsg("g-hello", "6", "hello", now-1500), kept)

	ran, n, err := s.CleanupClientIdempotencyPlaceholdersOnce(now)
	if err != nil || !ran || n != 2 {
		t.Fatalf("first run: ran=%v removed=%d err=%v", ran, n, err)
	}
	assertPresent(t, s, androidSendUUID, false)
	assertPresent(t, s, matched.MessageID, false)
	assertPresent(t, s, fresh.MessageID, true)
	assertPresent(t, s, "g-hello", true)
	assertPresent(t, s, transportRequestID, true)

	ran, n, err = s.CleanupClientIdempotencyPlaceholdersOnce(now)
	if err != nil || ran || n != 0 {
		t.Fatalf("second run: ran=%v removed=%d err=%v", ran, n, err)
	}
	assertPresent(t, s, fresh.MessageID, true)
}

func TestReconcileOwnEcho_TmpExactID(t *testing.T) {
	s := newTestStore(t)
	mustStore(t, s, placeholderMsg("tmp_012345678901", "5", "From Grok", phBase))
	real := realOwnMsg("192501", "5", "From Grok", phBase+500)
	mustStore(t, s, real)
	removed, err := s.ReconcileOwnEcho(real, "tmp_012345678901", true)
	if err != nil || removed != "tmp_012345678901" {
		t.Fatalf("removed = %q, err = %v", removed, err)
	}
	assertPresent(t, s, "tmp_012345678901", false)
}
