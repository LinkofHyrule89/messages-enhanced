package db

// Deleted-for-everyone placeholders and incoming edits. Edits live in their
// own table (like message_status_text) so the messages schema stays as is;
// the API fills Message.Edited from here.

import (
	"strings"
	"sync"
	"time"
)

const StatusMessageDeleted = "MESSAGE_DELETED"

var editsTableOnce sync.Map // *Store -> *sync.Once

func (s *Store) ensureEditsTable() {
	o, _ := editsTableOnce.LoadOrStore(s, &sync.Once{})
	o.(*sync.Once).Do(func() {
		_, _ = s.db.Exec(`CREATE TABLE IF NOT EXISTS message_edits (
			message_id TEXT PRIMARY KEY,
			conversation_id TEXT NOT NULL DEFAULT '',
			edited_ms INTEGER NOT NULL DEFAULT 0)`)
	})
}

// MarkMessageEdited records that a message's text changed after delivery.
func (s *Store) MarkMessageEdited(messageID, conversationID string) error {
	if messageID == "" {
		return nil
	}
	s.ensureEditsTable()
	_, err := s.db.Exec(`INSERT INTO message_edits (message_id, conversation_id, edited_ms) VALUES (?, ?, ?)
		ON CONFLICT(message_id) DO UPDATE SET edited_ms=excluded.edited_ms`, messageID, conversationID, time.Now().UnixMilli())
	return err
}

// MarkMessageDeletedForEveryone keeps the row as a "This message was
// deleted" placeholder: content and media are wiped, status is
// MESSAGE_DELETED and it drops out of search.
func (s *Store) MarkMessageDeletedForEveryone(messageID string) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`UPDATE messages SET body='', media_id='', mime_type='', decryption_key='', reactions='', status=? WHERE message_id=?`, StatusMessageDeleted, messageID); err != nil {
		return err
	}
	if err := s.syncMessageSearchIndex(tx, messageID, ""); err != nil {
		return err
	}
	return tx.Commit()
}

// FillEdited sets Edited on the messages in msgs that were edited.
func (s *Store) FillEdited(msgs []*Message) {
	if s == nil || s.db == nil {
		return
	}
	var ids []any
	byID := map[string]*Message{}
	for _, m := range msgs {
		if m != nil && m.MessageID != "" {
			ids = append(ids, m.MessageID)
			byID[m.MessageID] = m
		}
	}
	if len(ids) == 0 {
		return
	}
	s.ensureEditsTable()
	for start := 0; start < len(ids); start += 500 {
		end := min(start+500, len(ids))
		chunk := ids[start:end]
		rows, err := s.db.Query(`SELECT message_id FROM message_edits WHERE message_id IN (?`+strings.Repeat(",?", len(chunk)-1)+`)`, chunk...)
		if err != nil {
			return
		}
		for rows.Next() {
			var id string
			if rows.Scan(&id) == nil {
				if m := byID[id]; m != nil {
					m.Edited = true
				}
			}
		}
		rows.Close()
	}
}
