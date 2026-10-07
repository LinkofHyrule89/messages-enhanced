package db

import "database/sql"

// TabSpam holds conversations Google Messages files under Spam or Blocked.
// They're hidden from the normal list (mirrored from the phone).
const TabSpam = "spam"

// MirrorGoogleSpam moves a conversation Google lists as spam/blocked into
// the spam tab (from Recent or Archive only, so custom tabs are left alone).
// Reports whether the tab changed.
func (s *Store) MirrorGoogleSpam(id string) (bool, error) {
	res, err := s.db.Exec(`UPDATE conversations SET tab = ? WHERE conversation_id = ? AND tab IN ('', ?)`, TabSpam, id, TabArchive)
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

// UnmirrorGoogleSpam returns a conversation Google lists as active again
// from the spam tab to Recent.
func (s *Store) UnmirrorGoogleSpam(id string) (bool, error) {
	res, err := s.db.Exec(`UPDATE conversations SET tab = '' WHERE conversation_id = ? AND tab = ?`, id, TabSpam)
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

// ConversationExists reports whether a conversation row exists.
func (s *Store) ConversationExists(id string) bool {
	var one int
	return s.db.QueryRow(`SELECT 1 FROM conversations WHERE conversation_id = ?`, id).Scan(&one) == nil
}

// VisibleSMSConversationIDsSince lists Google Messages conversations shown
// in Recent or Archive whose last message is at or after sinceMS (newest
// first, at most limit).
func (s *Store) VisibleSMSConversationIDsSince(sinceMS int64, limit int) ([]string, error) {
	rows, err := s.db.Query(`SELECT conversation_id FROM conversations
		WHERE tab IN ('', ?) AND COALESCE(source_platform, 'sms') = 'sms' AND last_message_ts >= ?
		ORDER BY last_message_ts DESC LIMIT ?`, TabArchive, sinceMS, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var ids []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

// AvatarVersion changes whenever a cached contact photo or group icon
// changes (its newest updated_at_ms); clients use it to drop cached images.
func (s *Store) AvatarVersion() int64 {
	var v sql.NullInt64
	_ = s.db.QueryRow(`SELECT MAX(updated_at_ms) FROM contact_avatars`).Scan(&v)
	return v.Int64
}
