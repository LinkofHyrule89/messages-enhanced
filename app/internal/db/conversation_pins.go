package db

import (
	"database/sql"
	"errors"
	"strings"
)

// Pinned conversations.
//
// Google Messages exposes a read-only Conversation.pinned flag (set on the
// phone); it is stored in conversations.google_pinned on every conversation
// snapshot (live events and backfill). The web protocol has no pin/unpin
// action, so pins made from the car page are stored here only, in
// conversations.local_pinned_at, and shared by every browser using this
// server.

// MaxLocalConversationPins caps pins set from the car page.
const MaxLocalConversationPins = 20

var (
	ErrConvPinLimit    = errors.New("too many pinned conversations")
	ErrConvPinNotFound = errors.New("conversation not found")
)

// SetConversationLocalPinned pins (at atMS) or unpins a conversation locally.
// Re-pinning keeps the original pin time. Returns the updated conversation.
func (s *Store) SetConversationLocalPinned(id string, pinned bool, atMS int64) (*Conversation, error) {
	id = strings.TrimSpace(id)
	if id == "" {
		return nil, ErrConvPinNotFound
	}
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	var current int64
	if err := tx.QueryRow(`SELECT local_pinned_at FROM conversations WHERE conversation_id = ?`, id).Scan(&current); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrConvPinNotFound
		}
		return nil, err
	}
	switch {
	case !pinned && current != 0:
		if _, err := tx.Exec(`UPDATE conversations SET local_pinned_at = 0 WHERE conversation_id = ?`, id); err != nil {
			return nil, err
		}
	case pinned && current == 0:
		var n int
		if err := tx.QueryRow(`SELECT COUNT(*) FROM conversations WHERE local_pinned_at > 0`).Scan(&n); err != nil {
			return nil, err
		}
		if n >= MaxLocalConversationPins {
			return nil, ErrConvPinLimit
		}
		if atMS <= 0 {
			atMS = 1
		}
		if _, err := tx.Exec(`UPDATE conversations SET local_pinned_at = ? WHERE conversation_id = ?`, atMS, id); err != nil {
			return nil, err
		}
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return s.GetConversation(id)
}

// CountLocalConversationPins returns how many conversations are pinned locally.
func (s *Store) CountLocalConversationPins() (int, error) {
	var n int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM conversations WHERE local_pinned_at > 0`).Scan(&n)
	return n, err
}
