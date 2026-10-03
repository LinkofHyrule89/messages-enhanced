package db

// Google's per-message status text (MessageStatus.statusText). In RCS
// group chats it carries who has read an outgoing message, e.g.
// "Read by Alice, Bob" (first names as on the phone). Kept in its own
// table so the messages schema and its readers stay untouched; the API
// fills Message.StatusText from here.

import (
	"strings"
	"sync"
	"time"
)

var statusTextTableOnce sync.Map // *Store -> *sync.Once

func (s *Store) ensureStatusTextTable() {
	o, _ := statusTextTableOnce.LoadOrStore(s, &sync.Once{})
	o.(*sync.Once).Do(func() {
		_, _ = s.db.Exec(`CREATE TABLE IF NOT EXISTS message_status_text (
			message_id TEXT PRIMARY KEY,
			conversation_id TEXT NOT NULL DEFAULT '',
			text TEXT NOT NULL DEFAULT '',
			updated_ms INTEGER NOT NULL DEFAULT 0)`)
	})
}

// SetMessageStatusText stores (or, when text is empty, clears) a message's
// status text.
func (s *Store) SetMessageStatusText(messageID, conversationID, text string) error {
	if messageID == "" {
		return nil
	}
	s.ensureStatusTextTable()
	text = strings.TrimSpace(text)
	if text == "" {
		_, err := s.db.Exec(`DELETE FROM message_status_text WHERE message_id = ?`, messageID)
		return err
	}
	_, err := s.db.Exec(`INSERT INTO message_status_text (message_id, conversation_id, text, updated_ms) VALUES (?, ?, ?, ?)
		ON CONFLICT(message_id) DO UPDATE SET conversation_id=excluded.conversation_id, text=excluded.text, updated_ms=excluded.updated_ms
		WHERE message_status_text.text != excluded.text`,
		messageID, conversationID, text, time.Now().UnixMilli())
	return err
}

// FillStatusText sets StatusText on the outgoing messages in msgs.
func (s *Store) FillStatusText(msgs []*Message) {
	if s == nil || s.db == nil {
		return
	}
	var ids []any
	byID := map[string]*Message{}
	for _, m := range msgs {
		if m != nil && m.IsFromMe && m.MessageID != "" {
			ids = append(ids, m.MessageID)
			byID[m.MessageID] = m
		}
	}
	if len(ids) == 0 {
		return
	}
	s.ensureStatusTextTable()
	for start := 0; start < len(ids); start += 500 {
		end := min(start+500, len(ids))
		chunk := ids[start:end]
		q := `SELECT message_id, text FROM message_status_text WHERE message_id IN (?` + strings.Repeat(",?", len(chunk)-1) + `)`
		rows, err := s.db.Query(q, chunk...)
		if err != nil {
			return
		}
		for rows.Next() {
			var id, text string
			if rows.Scan(&id, &text) == nil {
				if m := byID[id]; m != nil {
					m.StatusText = text
				}
			}
		}
		rows.Close()
	}
}

// LastStatus describes a conversation's latest message for the list's
// "You: …" status icon.
type LastStatus struct {
	FromMe bool
	Status string
	Text   string
}

// LatestConversationStatuses returns, per conversation, whether its latest
// message is yours plus its status and status text.
func (s *Store) LatestConversationStatuses(conversationIDs []string) map[string]LastStatus {
	out := map[string]LastStatus{}
	if s == nil || s.db == nil || len(conversationIDs) == 0 {
		return out
	}
	s.ensureStatusTextTable()
	stmt, err := s.db.Prepare(`SELECT m.is_from_me, m.status, COALESCE(t.text, '') FROM messages m
		LEFT JOIN message_status_text t ON t.message_id = m.message_id
		WHERE m.conversation_id = ? ORDER BY m.timestamp_ms DESC, m.message_id DESC LIMIT 1`)
	if err != nil {
		return out
	}
	defer stmt.Close()
	for _, id := range conversationIDs {
		var ls LastStatus
		if stmt.QueryRow(id).Scan(&ls.FromMe, &ls.Status, &ls.Text) == nil {
			out[id] = ls
		}
	}
	return out
}
