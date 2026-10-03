package db

// Per-message end-to-end-encryption state from Google Messages (see
// client.MessageEncryption): 2 = encrypted, 1 = RCS not encrypted, 0 = none
// reported (SMS, or older RCS).

import "sync"

var encTableOnce sync.Map // *Store -> *sync.Once

func (s *Store) ensureEncTable() {
	o, _ := encTableOnce.LoadOrStore(s, &sync.Once{})
	o.(*sync.Once).Do(func() {
		_, _ = s.db.Exec(`CREATE TABLE IF NOT EXISTS message_enc (
			message_id TEXT PRIMARY KEY,
			conversation_id TEXT NOT NULL DEFAULT '',
			enc INTEGER NOT NULL DEFAULT 0,
			timestamp_ms INTEGER NOT NULL DEFAULT 0)`)
		_, _ = s.db.Exec(`CREATE INDEX IF NOT EXISTS idx_message_enc_conv ON message_enc(conversation_id, timestamp_ms)`)
	})
}

func (s *Store) SetMessageEncryption(messageID, conversationID string, timestampMS int64, enc int) error {
	if messageID == "" {
		return nil
	}
	s.ensureEncTable()
	_, err := s.db.Exec(`INSERT INTO message_enc (message_id, conversation_id, enc, timestamp_ms) VALUES (?, ?, ?, ?)
		ON CONFLICT(message_id) DO UPDATE SET conversation_id=excluded.conversation_id, enc=excluded.enc,
		timestamp_ms=CASE WHEN excluded.timestamp_ms > 0 THEN excluded.timestamp_ms ELSE message_enc.timestamp_ms END`,
		messageID, conversationID, enc, timestampMS)
	return err
}

// ConversationEncryption: the newest message's state (ok=false: nothing
// recorded) and the ids of encrypted messages (newest first, up to limit).
func (s *Store) ConversationEncryption(conversationID string, limit int) (latest int, ok bool, encrypted []string, err error) {
	s.ensureEncTable()
	row := s.db.QueryRow(`SELECT enc FROM message_enc WHERE conversation_id = ? ORDER BY timestamp_ms DESC LIMIT 1`, conversationID)
	if e := row.Scan(&latest); e == nil {
		ok = true
	}
	rows, err := s.db.Query(`SELECT message_id FROM message_enc WHERE conversation_id = ? AND enc = 2 ORDER BY timestamp_ms DESC LIMIT ?`, conversationID, limit)
	if err != nil {
		return latest, ok, nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id string
		if rows.Scan(&id) == nil {
			encrypted = append(encrypted, id)
		}
	}
	return latest, ok, encrypted, rows.Err()
}
