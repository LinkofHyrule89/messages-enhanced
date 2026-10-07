package db

import "testing"

const phBase = int64(1_790_738_307_000) // ~2026-09-29 21:18 MDT

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
