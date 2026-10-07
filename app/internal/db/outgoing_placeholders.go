package db

import (
	"database/sql"
	"fmt"
	"sort"
	"strings"
	"time"
)

// Outgoing placeholders.
//
// When a Google Messages send succeeds, the app stores a local row with
// MessageID = the send's temporary ID and status OUTGOING_SENDING, so the
// message shows immediately. Temporary IDs are either "tm-<ms>-<rand>" from
// the car/web UI or "tmp_<digits>" from server-side sends (MCP tools,
// app.SendTextToConversation, the scheduler). The real copy arrives later
// from Google with its own message ID. Normally the echo carries the same
// TmpID and the placeholder is deleted by ID, but that can miss: the echo
// can be processed before the placeholder row is written (the send response
// and the echo race over the same long-poll), and copies that come in
// through the reconcile/backfill fetch path may not carry a TmpID at all.
// The placeholder then stays forever as a duplicate stuck on "Sending".
//
// The helpers below remove such a placeholder by content instead: same
// conversation, both from me, placeholder still in a sending state, timestamps
// within OutgoingPlaceholderMatchWindowMS, and either the same trimmed text
// (no media on either side) or media on both sides with a compatible MIME
// type. A placeholder with no matching real message is never touched: it may
// be a genuinely failed send the user needs to see.

const (
	// OutgoingPlaceholderPrefix is the web/car UI idempotency-key prefix
	// ("tm-<ms>-<rand>"). Server-side sends use OutgoingServerTmpPrefix instead.
	OutgoingPlaceholderPrefix = "tm-"
	// OutgoingServerTmpPrefix is the temporary ID prefix from newSendTmpID
	// (MCP connector, app.SendTextToConversation, scheduler).
	OutgoingServerTmpPrefix = "tmp_"
	// OutgoingPlaceholderMatchWindowMS is how far apart (either direction) the
	// placeholder's and the real message's timestamps may be.
	OutgoingPlaceholderMatchWindowMS int64 = 3 * 60 * 1000
)

// IsOutgoingPlaceholderID reports whether id is a local optimistic-send
// placeholder (web "tm-…" or server/MCP "tmp_…").
func IsOutgoingPlaceholderID(id string) bool {
	return strings.HasPrefix(id, OutgoingPlaceholderPrefix) || strings.HasPrefix(id, OutgoingServerTmpPrefix)
}

func isLocalOutgoingID(id string) bool {
	return IsOutgoingPlaceholderID(id)
}

// isPlaceholderSendingStatus: still waiting for the real copy. Failed or
// canceled rows are deliberately excluded so they stay visible.
func isPlaceholderSendingStatus(status string) bool {
	s := strings.ToUpper(strings.TrimSpace(status))
	if s == "" || strings.Contains(s, "FAIL") || strings.Contains(s, "CANCEL") {
		return false
	}
	for _, marker := range []string{"SENDING", "YET_TO_SEND", "VALIDATING", "AWAITING_RETRY"} {
		if strings.Contains(s, marker) {
			return true
		}
	}
	return false
}

func normalizedMIME(mime string) string {
	m := strings.ToLower(strings.TrimSpace(mime))
	if i := strings.IndexByte(m, ';'); i >= 0 {
		m = strings.TrimSpace(m[:i])
	}
	return m
}

// placeholderMIMECompatible: equal major type (image/png vs image/jpeg is fine,
// since Google may re-encode), or either side unknown.
func placeholderMIMECompatible(a, b string) bool {
	a, b = normalizedMIME(a), normalizedMIME(b)
	if a == "" || b == "" || a == "application/octet-stream" || b == "application/octet-stream" {
		return true
	}
	ma, _, _ := strings.Cut(a, "/")
	mb, _, _ := strings.Cut(b, "/")
	return ma == mb
}

func absInt64(v int64) int64 {
	if v < 0 {
		return -v
	}
	return v
}

// OutgoingPlaceholderMatches reports whether placeholder is the local stand-in
// for the real (Google) copy real, by the rule described at the top of this
// file.
func OutgoingPlaceholderMatches(placeholder, real *Message) bool {
	if placeholder == nil || real == nil {
		return false
	}
	if !IsOutgoingPlaceholderID(placeholder.MessageID) || isLocalOutgoingID(real.MessageID) || placeholder.MessageID == real.MessageID {
		return false
	}
	if !placeholder.IsFromMe || !real.IsFromMe {
		return false
	}
	if placeholder.ConversationID == "" || placeholder.ConversationID != real.ConversationID {
		return false
	}
	if !isPlaceholderSendingStatus(placeholder.Status) {
		return false
	}
	if placeholder.TimestampMS <= 0 || real.TimestampMS <= 0 ||
		absInt64(placeholder.TimestampMS-real.TimestampMS) > OutgoingPlaceholderMatchWindowMS {
		return false
	}
	pMedia := strings.TrimSpace(placeholder.MediaID) != ""
	rMedia := strings.TrimSpace(real.MediaID) != ""
	switch {
	case pMedia && rMedia:
		return placeholderMIMECompatible(placeholder.MimeType, real.MimeType)
	case !pMedia && !rMedia:
		body := strings.TrimSpace(placeholder.Body)
		return body != "" && body == strings.TrimSpace(real.Body)
	default:
		return false
	}
}

func queryMessagesTx(tx *sql.Tx, query string, args ...any) ([]*Message, error) {
	rows, err := tx.Query(query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanMessages(rows)
}

// candidate placeholders for a real message (SQL prefilter; the Go matcher
// makes the final decision).
func placeholderCandidatesTx(tx *sql.Tx, conversationID string, fromMS, toMS int64) ([]*Message, error) {
	return queryMessagesTx(tx, `
		SELECT `+messageColumns+`
		FROM messages
		WHERE conversation_id = ?
		  AND is_from_me = 1
		  AND (substr(message_id, 1, 3) = ? OR substr(message_id, 1, 4) = ?)
		  AND timestamp_ms BETWEEN ? AND ?
		ORDER BY timestamp_ms ASC, message_id ASC
	`, conversationID, OutgoingPlaceholderPrefix, OutgoingServerTmpPrefix, fromMS, toMS)
}

// candidate real (non-local) from-me messages around a placeholder.
func realOwnCandidatesTx(tx *sql.Tx, conversationID string, fromMS, toMS int64) ([]*Message, error) {
	return queryMessagesTx(tx, `
		SELECT `+messageColumns+`
		FROM messages
		WHERE conversation_id = ?
		  AND is_from_me = 1
		  AND substr(message_id, 1, 3) != ?
		  AND substr(message_id, 1, 4) != ?
		  AND timestamp_ms BETWEEN ? AND ?
		ORDER BY timestamp_ms ASC, message_id ASC
	`, conversationID, OutgoingPlaceholderPrefix, OutgoingServerTmpPrefix, fromMS, toMS)
}

// closestPlaceholderMatch picks the matching placeholder nearest in time to
// real (ties: the earlier one), or nil.
func closestPlaceholderMatch(real *Message, candidates []*Message) *Message {
	var best *Message
	for _, c := range candidates {
		if !OutgoingPlaceholderMatches(c, real) {
			continue
		}
		if best == nil || absInt64(c.TimestampMS-real.TimestampMS) < absInt64(best.TimestampMS-real.TimestampMS) {
			best = c
		}
	}
	return best
}

// DeleteMatchingOutgoingPlaceholder is the echo-side fallback: given a real
// from-me message that was just stored, delete at most one matching local
// placeholder (tm- or tmp_; the closest in time). It returns the deleted
// placeholder ID, or "" when nothing matched.
func (s *Store) DeleteMatchingOutgoingPlaceholder(real *Message) (string, error) {
	if real == nil || !real.IsFromMe || isLocalOutgoingID(real.MessageID) || real.ConversationID == "" || real.TimestampMS <= 0 {
		return "", nil
	}
	tx, err := s.db.Begin()
	if err != nil {
		return "", err
	}
	defer tx.Rollback()

	candidates, err := placeholderCandidatesTx(tx, real.ConversationID,
		real.TimestampMS-OutgoingPlaceholderMatchWindowMS, real.TimestampMS+OutgoingPlaceholderMatchWindowMS)
	if err != nil {
		return "", err
	}
	best := closestPlaceholderMatch(real, candidates)
	if best == nil {
		return "", nil
	}
	if _, err := s.deleteMessages(tx, `message_id = ?`, best.MessageID); err != nil {
		return "", err
	}
	if err := tx.Commit(); err != nil {
		return "", err
	}
	return best.MessageID, nil
}

// DeleteOutgoingPlaceholderIfEchoed is the send-side check, run right after a
// placeholder is written: if Google's real copy already arrived (the echo won
// the race against the placeholder insert), delete the placeholder now.
// Only real messages timestamped at or after notBeforeMS count (pass the time
// the send started, minus a little clock slack) so an earlier identical send
// can't be mistaken for this one. Returns the ID of the matching real message,
// or "" when the placeholder was kept.
func (s *Store) DeleteOutgoingPlaceholderIfEchoed(placeholderID string, notBeforeMS int64) (string, error) {
	if !IsOutgoingPlaceholderID(placeholderID) {
		return "", nil
	}
	tx, err := s.db.Begin()
	if err != nil {
		return "", err
	}
	defer tx.Rollback()

	rows, err := queryMessagesTx(tx, `SELECT `+messageColumns+` FROM messages WHERE message_id = ?`, placeholderID)
	if err != nil || len(rows) == 0 {
		return "", err
	}
	p := rows[0]
	from := p.TimestampMS - OutgoingPlaceholderMatchWindowMS
	if notBeforeMS > from {
		from = notBeforeMS
	}
	reals, err := realOwnCandidatesTx(tx, p.ConversationID, from, p.TimestampMS+OutgoingPlaceholderMatchWindowMS)
	if err != nil {
		return "", err
	}
	var match *Message
	for _, r := range reals {
		if OutgoingPlaceholderMatches(p, r) {
			if match == nil || absInt64(r.TimestampMS-p.TimestampMS) < absInt64(match.TimestampMS-p.TimestampMS) {
				match = r
			}
		}
	}
	if match == nil {
		return "", nil
	}
	if _, err := s.deleteMessages(tx, `message_id = ?`, p.MessageID); err != nil {
		return "", err
	}
	if err := tx.Commit(); err != nil {
		return "", err
	}
	return match.MessageID, nil
}

// ReconcileOwnEcho cleans up the local placeholder for a from-me message that
// was just stored with its real ID. tmpID is the echo's TmpID (may be empty)
// and isNew says whether the real row did not exist before this store. The
// exact TmpID delete runs first; the content-matching fallback only runs when
// that deleted nothing and the real row is new, so later status-only
// re-deliveries of an already-known message can't consume another pending
// placeholder. Returns the deleted placeholder ID ("" if none).
func (s *Store) ReconcileOwnEcho(real *Message, tmpID string, isNew bool) (string, error) {
	if real == nil || !real.IsFromMe || isLocalOutgoingID(real.MessageID) {
		return "", nil
	}
	if tmpID != "" && tmpID != real.MessageID {
		deleted, err := s.DeleteMessageIfExists(tmpID)
		if err != nil {
			return "", err
		}
		if deleted {
			return tmpID, nil
		}
	}
	if !isNew {
		return "", nil
	}
	return s.DeleteMatchingOutgoingPlaceholder(real)
}

// DeleteMessageIfExists deletes one message by ID and reports whether a row
// was removed.
func (s *Store) DeleteMessageIfExists(messageID string) (bool, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return false, err
	}
	defer tx.Rollback()
	res, err := s.deleteMessages(tx, `message_id = ?`, messageID)
	if err != nil {
		return false, err
	}
	if err := tx.Commit(); err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

// MessageExists reports whether a message with this ID is stored.
func (s *Store) MessageExists(messageID string) (bool, error) {
	var one int
	err := s.db.QueryRow(`SELECT 1 FROM messages WHERE message_id = ? LIMIT 1`, messageID).Scan(&one)
	if err == sql.ErrNoRows {
		return false, nil
	}
	return err == nil, err
}

// outgoingPlaceholderCleanupTask names the one-time startup sweep in
// store_maintenance_tasks.
const outgoingPlaceholderCleanupTask = "cleanup_matched_outgoing_placeholders_v2"

// CleanupMatchedOutgoingPlaceholdersOnce runs CleanupMatchedOutgoingPlaceholders
// the first time it is called on a database and records that it ran, in the
// same transaction. It's one-time on purpose: the content rule can't tell
// whether a real message's own placeholder was already removed by TmpID, so
// re-running it on every start could let a later, genuinely undelivered
// identical send pair up with an already-claimed real message. Returns
// whether the sweep ran and how many placeholders it removed.
func (s *Store) CleanupMatchedOutgoingPlaceholdersOnce() (bool, int, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return false, 0, err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`CREATE TABLE IF NOT EXISTS store_maintenance_tasks (
		name TEXT PRIMARY KEY,
		done_at INTEGER NOT NULL DEFAULT 0,
		result TEXT NOT NULL DEFAULT ''
	)`); err != nil {
		return false, 0, err
	}
	var done int
	err = tx.QueryRow(`SELECT 1 FROM store_maintenance_tasks WHERE name = ?`, outgoingPlaceholderCleanupTask).Scan(&done)
	if err == nil {
		return false, 0, nil
	}
	if err != sql.ErrNoRows {
		return false, 0, err
	}
	removed, err := s.cleanupMatchedOutgoingPlaceholdersTx(tx)
	if err != nil {
		return false, 0, err
	}
	if _, err := tx.Exec(`INSERT INTO store_maintenance_tasks (name, done_at, result) VALUES (?, ?, ?)`,
		outgoingPlaceholderCleanupTask, time.Now().UnixMilli(), fmt.Sprintf("removed=%d", removed)); err != nil {
		return false, 0, err
	}
	if err := tx.Commit(); err != nil {
		return false, 0, err
	}
	return true, removed, nil
}

// CleanupMatchedOutgoingPlaceholders sweeps placeholders left behind before
// the fallback existed (or before tmp_ IDs were included). Each stuck
// placeholder is paired with at most one matching real message and each real
// message with at most one placeholder, closest timestamps first.
// Placeholders without a match are kept. Startup uses
// CleanupMatchedOutgoingPlaceholdersOnce.
func (s *Store) CleanupMatchedOutgoingPlaceholders() (int, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	removed, err := s.cleanupMatchedOutgoingPlaceholdersTx(tx)
	if err != nil {
		return 0, err
	}
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	return removed, nil
}

func (s *Store) cleanupMatchedOutgoingPlaceholdersTx(tx *sql.Tx) (int, error) {
	placeholders, err := queryMessagesTx(tx, `
		SELECT `+messageColumns+`
		FROM messages
		WHERE is_from_me = 1
		  AND (substr(message_id, 1, 3) = ? OR substr(message_id, 1, 4) = ?)
		ORDER BY conversation_id, timestamp_ms ASC, message_id ASC
	`, OutgoingPlaceholderPrefix, OutgoingServerTmpPrefix)
	if err != nil {
		return 0, err
	}
	byConv := map[string][]*Message{}
	var convOrder []string
	for _, p := range placeholders {
		if !isPlaceholderSendingStatus(p.Status) || p.TimestampMS <= 0 {
			continue
		}
		if _, ok := byConv[p.ConversationID]; !ok {
			convOrder = append(convOrder, p.ConversationID)
		}
		byConv[p.ConversationID] = append(byConv[p.ConversationID], p)
	}

	type pair struct {
		p, r *Message
		dist int64
	}
	var toDelete []string
	for _, conv := range convOrder {
		ps := byConv[conv]
		minTS, maxTS := ps[0].TimestampMS, ps[0].TimestampMS
		for _, p := range ps {
			if p.TimestampMS < minTS {
				minTS = p.TimestampMS
			}
			if p.TimestampMS > maxTS {
				maxTS = p.TimestampMS
			}
		}
		reals, err := realOwnCandidatesTx(tx, conv, minTS-OutgoingPlaceholderMatchWindowMS, maxTS+OutgoingPlaceholderMatchWindowMS)
		if err != nil {
			return 0, err
		}
		var pairs []pair
		for _, p := range ps {
			for _, r := range reals {
				if OutgoingPlaceholderMatches(p, r) {
					pairs = append(pairs, pair{p: p, r: r, dist: absInt64(p.TimestampMS - r.TimestampMS)})
				}
			}
		}
		sort.SliceStable(pairs, func(i, j int) bool { return pairs[i].dist < pairs[j].dist })
		usedP, usedR := map[string]bool{}, map[string]bool{}
		for _, pr := range pairs {
			if usedP[pr.p.MessageID] || usedR[pr.r.MessageID] {
				continue
			}
			usedP[pr.p.MessageID], usedR[pr.r.MessageID] = true, true
			toDelete = append(toDelete, pr.p.MessageID)
		}
	}
	for _, id := range toDelete {
		if _, err := s.deleteMessages(tx, `message_id = ?`, id); err != nil {
			return 0, err
		}
	}
	return len(toDelete), nil
}
