package db

import (
	"errors"
	"testing"
)

func TestMessagePinsLifecycle(t *testing.T) {
	s := newTestStore(t)
	mustStore(t, s,
		&Message{MessageID: "a1", ConversationID: "c1", SenderName: "Sam", Body: "Gate code is 4412", TimestampMS: 1000},
		&Message{MessageID: "a2", ConversationID: "c1", SenderName: "Me", Body: "Meet at 7", TimestampMS: 2000, IsFromMe: true},
		&Message{MessageID: "a3", ConversationID: "c1", SenderName: "Sam", TimestampMS: 3000, MediaID: "m1", MimeType: "image/jpeg"},
		&Message{MessageID: "b1", ConversationID: "c2", Body: "other chat", TimestampMS: 1000},
		placeholderMsg("tm-123-abc", "c1", "sending", 4000),
	)
	if pins, err := s.ListPinnedMessages("c1"); err != nil || len(pins) != 0 {
		t.Fatalf("empty: %v %v", pins, err)
	}
	for i, id := range []string{"a1", "a2", "a3", "b1"} {
		if _, err := s.SetMessagePinned(id, true, int64(100+i)); err != nil {
			t.Fatalf("pin %s: %v", id, err)
		}
	}
	// idempotent re-pin moves it to the top
	if _, err := s.SetMessagePinned("a1", true, 500); err != nil {
		t.Fatal(err)
	}
	pins, err := s.ListPinnedMessages("c1")
	if err != nil || len(pins) != 3 {
		t.Fatalf("pins: %+v %v", pins, err)
	}
	if pins[0].MessageID != "a1" || pins[0].Body != "Gate code is 4412" || pins[0].SenderName != "Sam" || pins[0].PinnedAtMS != 500 {
		t.Fatalf("newest pin first: %+v", pins[0])
	}
	if pins[1].MessageID != "a3" || !pins[1].HasMedia || pins[2].MessageID != "a2" || !pins[2].IsFromMe {
		t.Fatalf("order/fields: %+v", pins)
	}
	if other, _ := s.ListPinnedMessages("c2"); len(other) != 1 {
		t.Fatalf("c2 pins: %+v", other)
	}
	// unpin, unknown, placeholder
	if _, err := s.SetMessagePinned("a2", false, 0); err != nil {
		t.Fatal(err)
	}
	if _, err := s.SetMessagePinned("a2", false, 0); err != nil {
		t.Fatal("unpin should be idempotent")
	}
	if _, err := s.SetMessagePinned("nope", true, 1); !errors.Is(err, ErrPinNoMessage) {
		t.Fatalf("unknown: %v", err)
	}
	if _, err := s.SetMessagePinned("tm-123-abc", true, 1); !errors.Is(err, ErrPinPlaceholder) {
		t.Fatalf("placeholder: %v", err)
	}
	// deleting a message drops its pin
	if err := s.DeleteMessageByID("a1"); err != nil {
		t.Fatal(err)
	}
	pins, _ = s.ListPinnedMessages("c1")
	if len(pins) != 1 || pins[0].MessageID != "a3" {
		t.Fatalf("after delete: %+v", pins)
	}
	var n int
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM message_pins WHERE message_id = 'a1'`).Scan(&n)
	if n != 0 {
		t.Fatal("dangling pin not pruned")
	}
}

func TestMessagePinsLimit(t *testing.T) {
	s := newTestStore(t)
	for i := 0; i <= MaxPinsPerConversation; i++ {
		id := "m" + string(rune('a'+i))
		mustStore(t, s, &Message{MessageID: id, ConversationID: "c", Body: id, TimestampMS: int64(i)})
		_, err := s.SetMessagePinned(id, true, int64(i))
		if i < MaxPinsPerConversation && err != nil {
			t.Fatalf("pin %d: %v", i, err)
		}
		if i == MaxPinsPerConversation && !errors.Is(err, ErrPinLimit) {
			t.Fatalf("limit: %v", err)
		}
	}
	// re-pinning an existing one is still fine at the limit
	if _, err := s.SetMessagePinned("ma", true, 999); err != nil {
		t.Fatal(err)
	}
}
