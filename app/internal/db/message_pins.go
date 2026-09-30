package db

import (
	"database/sql"
	"errors"
	"strings"
)

// Pinned messages, stored locally per conversation.
//
// libgm's Google Messages protocol (checked against upstream
// mautrix/gmessages main, Sept 2026) has no message-pin field or pin/unpin
// action, so pins can't be synced with the phone yet. They live here so every
// browser using this server (car, tablet, phone) sees the same pins. The
// source column leaves room for Google-synced pins later.

// MaxPinsPerConversation caps pins in one conversation.
const MaxPinsPerConversation = 20

var (
	ErrPinLimit       = errors.New("too many pinned messages in this conversation")
	ErrPinNoMessage   = errors.New("message not found")
	ErrPinPlaceholder = errors.New("wait until the message has been sent to pin it")
)

// PinnedMessage is a pin plus a snapshot of the message it points at.
type PinnedMessage struct {
	ConversationID string `json:"conversation_id"`
	MessageID      string `json:"message_id"`
	PinnedAtMS     int64  `json:"pinned_at_ms"`
	Source         string `json:"source"`
	SenderName     string `json:"sender_name"`
	Body           string `json:"body"`
	IsFromMe       bool   `json:"is_from_me"`
	TimestampMS    int64  `json:"timestamp_ms"`
	MimeType       string `json:"mime_type,omitempty"`
	HasMedia       bool   `json:"has_media,omitempty"`
}

func (s *Store) ensureMessagePins() error {
	_, err := s.db.Exec(`CREATE TABLE IF NOT EXISTS message_pins (
		conversation_id TEXT NOT NULL,
		message_id TEXT NOT NULL,
		pinned_at INTEGER NOT NULL DEFAULT 0,
		source TEXT NOT NULL DEFAULT 'local',
		PRIMARY KEY (conversation_id, message_id)
	)`)
	return err
}

// SetMessagePinned pins or unpins a message (idempotent). The message must
// exist; its conversation is taken from the message itself.
func (s *Store) SetMessagePinned(messageID string, pinned bool, atMS int64) (*Message, error) {
	messageID = strings.TrimSpace(messageID)
	m, err := s.GetMessageByID(messageID)
	if errors.Is(err, sql.ErrNoRows) || (err == nil && m == nil) {
		return nil, ErrPinNoMessage
	}
	if err != nil {
		return nil, err
	}
	if !pinned {
		_, err := s.db.Exec(`DELETE FROM message_pins WHERE conversation_id = ? AND message_id = ?`, m.ConversationID, m.MessageID)
		return m, err
	}
	if strings.HasPrefix(m.MessageID, "tm-") || strings.HasPrefix(m.MessageID, "tmp_") {
		return nil, ErrPinPlaceholder
	}
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	var exists int
	if err := tx.QueryRow(`SELECT COUNT(*) FROM message_pins WHERE conversation_id = ? AND message_id = ?`, m.ConversationID, m.MessageID).Scan(&exists); err != nil {
		return nil, err
	}
	if exists == 0 {
		var n int
		// Count only pins whose message still exists.
		if err := tx.QueryRow(`SELECT COUNT(*) FROM message_pins p JOIN messages m ON m.message_id = p.message_id
			WHERE p.conversation_id = ?`, m.ConversationID).Scan(&n); err != nil {
			return nil, err
		}
		if n >= MaxPinsPerConversation {
			return nil, ErrPinLimit
		}
	}
	if _, err := tx.Exec(`INSERT INTO message_pins (conversation_id, message_id, pinned_at, source) VALUES (?, ?, ?, 'local')
		ON CONFLICT(conversation_id, message_id) DO UPDATE SET pinned_at = excluded.pinned_at`, m.ConversationID, m.MessageID, atMS); err != nil {
		return nil, err
	}
	return m, tx.Commit()
}

// ListPinnedMessages returns a conversation's pins, newest pin first. Pins
// whose message has been deleted are dropped (and pruned).
func (s *Store) ListPinnedMessages(conversationID string) ([]PinnedMessage, error) {
	rows, err := s.db.Query(`SELECT p.conversation_id, p.message_id, p.pinned_at, p.source,
			m.message_id IS NOT NULL, COALESCE(m.sender_name, ''), COALESCE(m.body, ''), COALESCE(m.is_from_me, 0),
			COALESCE(m.timestamp_ms, 0), COALESCE(m.mime_type, ''), COALESCE(m.media_id, '')
		FROM message_pins p LEFT JOIN messages m ON m.message_id = p.message_id
		WHERE p.conversation_id = ?
		ORDER BY p.pinned_at DESC, p.rowid DESC`, conversationID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []PinnedMessage{}
	var gone []string
	for rows.Next() {
		var p PinnedMessage
		var ok bool
		var media string
		if err := rows.Scan(&p.ConversationID, &p.MessageID, &p.PinnedAtMS, &p.Source, &ok, &p.SenderName, &p.Body,
			&p.IsFromMe, &p.TimestampMS, &p.MimeType, &media); err != nil {
			return nil, err
		}
		if !ok {
			gone = append(gone, p.MessageID)
			continue
		}
		p.HasMedia = media != ""
		out = append(out, p)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	rows.Close()
	for _, id := range gone {
		_, _ = s.db.Exec(`DELETE FROM message_pins WHERE conversation_id = ? AND message_id = ?`, conversationID, id)
	}
	return out, nil
}
