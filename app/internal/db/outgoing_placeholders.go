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
// message shows immediately. Temporary IDs are "tm-<ms>-<rand>" from the
// car/web UI, "tmp_<digits>" from server-side sends (MCP tools,
// app.SendTextToConversation, the scheduler), a UUID from the Android app's
// idempotency key, or 32 hex characters from a v2 transport request id.
// /api/send stores the caller's idempotency key as the row id and as Google's
// TmpID. The real copy arrives later with its own message ID. Normally the
// echo carries that same TmpID and the placeholder is deleted by ID, but that
// can miss: the echo can be processed before the placeholder row is written
// (the send response and the echo race over the same long-poll), and copies
// that come in through the reconcile/backfill fetch path may not carry a
// TmpID at all. The placeholder then stays forever as a duplicate stuck on
// "Sending". Group and MMS echoes sometimes put some other message id in a
// tmp field; only a row that is still an optimistic send is deleted for those.
//
// The helpers below remove such a placeholder by content instead: same
// conversation, both from me, placeholder still in a sending state, timestamps
// within OutgoingPlaceholderMatchWindowMS, and either the same trimmed text
// (no media on either side) or media on both sides with a compatible MIME
// type. A still-sending placeholder older than that window, and still
// unmatched, is expired: the echo is not coming. OUTGOING_FAILED and
// OUTGOING_CANCELLED stay, because those are the sends the user needs to see.

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
// placeholder with the web "tm-…" or server/MCP "tmp_…" prefix.
func IsOutgoingPlaceholderID(id string) bool {
	return strings.HasPrefix(id, OutgoingPlaceholderPrefix) || strings.HasPrefix(id, OutgoingServerTmpPrefix)
}

// IsOpaqueClientPlaceholderID reports a send id this server stores that is not
// a tm-/tmp_ prefix. The Android app's idempotency key is a UUID. The v2
// transport request id is 32 hex characters. Google message ids are neither.
func IsOpaqueClientPlaceholderID(id string) bool {
	return isCanonicalUUID(id) || isHex32(id)
}

func isCanonicalUUID(id string) bool {
	if len(id) != 36 {
		return false
	}
	for i := 0; i < len(id); i++ {
		c := id[i]
		switch i {
		case 8, 13, 18, 23:
			if c != '-' {
				return false
			}
		default:
			if !isHexByte(c) {
				return false
			}
		}
	}
	return true
}

func isHex32(id string) bool {
	if len(id) != 32 {
		return false
	}
	for i := 0; i < len(id); i++ {
		if !isHexByte(id[i]) {
			return false
		}
	}
	return true
}

func isHexByte(c byte) bool {
	return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
}

// localPlaceholderRow is an optimistic send still waiting for Google's copy.
// tm-/tmp_ ids are always that row. A UUID or 32-hex id counts only when it
// has no sender: /api/send and the legacy v2 placeholder write the row that
// way, while a Google message carries the sender from the echo.
func localPlaceholderRow(m *Message) bool {
	if m == nil || !m.IsFromMe || !isPlaceholderSendingStatus(m.Status) {
		return false
	}
	if IsOutgoingPlaceholderID(m.MessageID) {
		return true
	}
	if !IsOpaqueClientPlaceholderID(m.MessageID) {
		return false
	}
	return strings.TrimSpace(m.SenderName) == "" && strings.TrimSpace(m.SenderNumber) == ""
}

// PreferRealOverPlaceholder reports whether next should replace prev when the
// two rows are the same send. Used by person-message dedupe so the optimistic
// row does not hide Google's copy.
func PreferRealOverPlaceholder(prev, next *Message) bool {
	if prev == nil || next == nil {
		return false
	}
	if IsOutgoingPlaceholderID(prev.MessageID) && !IsOutgoingPlaceholderID(next.MessageID) {
		return true
	}
	return localPlaceholderRow(prev) && !localPlaceholderRow(next) && !IsOutgoingPlaceholderID(next.MessageID)
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
	return outgoingEchoMatch(placeholder, real, true)
}

// outgoingEchoMatch is the content rule for a local send stand-in. requireLocalID
// keeps the sweep and the echo-side fallback limited to tm-/tmp_ ids. A durable
// send's transport request id is not one of those prefixes, but it is still a
// placeholder the send path just wrote; that path passes requireLocalID false.
func outgoingEchoMatch(placeholder, real *Message, requireLocalID bool) bool {
	if placeholder == nil || real == nil {
		return false
	}
	if placeholder.MessageID == real.MessageID || isLocalOutgoingID(real.MessageID) || localPlaceholderRow(real) {
		return false
	}
	if requireLocalID && !localPlaceholderRow(placeholder) {
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
		  AND (substr(message_id, 1, 3) = ? OR substr(message_id, 1, 4) = ? OR length(message_id) = 32 OR length(message_id) = 36)
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
	switch {
	case IsOutgoingPlaceholderID(placeholderID):
		return s.deleteOutgoingPlaceholderIfEchoed(placeholderID, notBeforeMS, true)
	case IsOpaqueClientPlaceholderID(placeholderID):
		// Android UUID keys and v2 transport request ids are not tm-/tmp_, but
		// the send path just stored this row and the echo may already be here.
		return s.deleteOutgoingPlaceholderIfEchoed(placeholderID, notBeforeMS, false)
	default:
		return "", nil
	}
}

// DeleteKnownOutgoingPlaceholderIfEchoed is DeleteOutgoingPlaceholderIfEchoed
// for a placeholder whose id is a transport request id rather than tm-/tmp_.
func (s *Store) DeleteKnownOutgoingPlaceholderIfEchoed(placeholderID string, notBeforeMS int64) (string, error) {
	if strings.TrimSpace(placeholderID) == "" {
		return "", nil
	}
	return s.deleteOutgoingPlaceholderIfEchoed(placeholderID, notBeforeMS, false)
}

func (s *Store) deleteOutgoingPlaceholderIfEchoed(placeholderID string, notBeforeMS int64, requireLocalID bool) (string, error) {
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
		if outgoingEchoMatch(p, r, requireLocalID) {
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
	return s.ReconcileOwnEchoIDs(real, []string{tmpID}, isNew)
}

// ReconcileOwnEchoIDs is ReconcileOwnEcho for every temporary id the echo
// actually carries. tm-/tmp_ ids are deleted, and so are Android UUID keys and
// v2 transport request ids when the stored row is still an optimistic send.
// A group/MMS echo sometimes names some other message id in the tmp field;
// that row is left in place so the content fallback can still run.
// When the placeholder lives in the conversation the user sent to and the echo
// was stored under a different id, the real row moves onto the send's
// conversation before the placeholder is removed. allowContentMatch is false
// for a status-only redelivery of a message that already had a body, so that
// update cannot consume a later identical send.
func (s *Store) ReconcileOwnEchoIDs(real *Message, tmpIDs []string, allowContentMatch bool) (string, error) {
	if real == nil || !real.IsFromMe || isLocalOutgoingID(real.MessageID) {
		return "", nil
	}
	seen := map[string]struct{}{}
	for _, tmpID := range tmpIDs {
		tmpID = strings.TrimSpace(tmpID)
		if tmpID == "" || tmpID == real.MessageID {
			continue
		}
		// Skip ids that are not one of ours. A group/MMS echo sometimes names
		// a real message id in the tmp field; deleting that row used to skip
		// the content fallback. UUID and 32-hex ids are still ours (Android
		// idempotency key, v2 transport request id) and are deleted only when
		// the stored row is still an optimistic send.
		if !IsOutgoingPlaceholderID(tmpID) && !IsOpaqueClientPlaceholderID(tmpID) {
			continue
		}
		if _, ok := seen[tmpID]; ok {
			continue
		}
		seen[tmpID] = struct{}{}
		deleted, err := s.deletePlaceholderForEcho(real, tmpID)
		if err != nil {
			return "", err
		}
		if deleted {
			return tmpID, nil
		}
	}
	if !allowContentMatch {
		return "", nil
	}
	return s.DeleteMatchingOutgoingPlaceholder(real)
}

// deletePlaceholderForEcho removes one known placeholder. If that placeholder
// was filed in a different conversation than the echo, the echo moves there
// first so the thread the user sent in is the one that keeps the real row.
func (s *Store) deletePlaceholderForEcho(real *Message, placeholderID string) (bool, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return false, err
	}
	defer tx.Rollback()

	rows, err := queryMessagesTx(tx, `SELECT `+messageColumns+` FROM messages WHERE message_id = ?`, placeholderID)
	if err != nil || len(rows) == 0 {
		return false, err
	}
	placeholder := rows[0]
	if !IsOutgoingPlaceholderID(placeholder.MessageID) && !localPlaceholderRow(placeholder) {
		return false, nil
	}
	if placeholder.ConversationID != "" && placeholder.ConversationID != real.ConversationID {
		if _, err := tx.Exec(`UPDATE messages SET conversation_id = ? WHERE message_id = ?`, placeholder.ConversationID, real.MessageID); err != nil {
			return false, err
		}
		if real.TimestampMS > 0 {
			if _, err := tx.Exec(`
				UPDATE conversations
				SET last_message_ts = CASE WHEN last_message_ts < ? THEN ? ELSE last_message_ts END
				WHERE conversation_id = ?
			`, real.TimestampMS, real.TimestampMS, placeholder.ConversationID); err != nil {
				return false, err
			}
		}
		real.ConversationID = placeholder.ConversationID
	}
	if _, err := s.deleteMessages(tx, `message_id = ?`, placeholder.MessageID); err != nil {
		return false, err
	}
	if err := tx.Commit(); err != nil {
		return false, err
	}
	return true, nil
}

// ExpireUnmatchedOutgoingPlaceholders deletes still-sending tm-/tmp_ rows
// older than the match window. A placeholder that never meets its echo would
// otherwise stay in the thread as "Sending" with no sender name.
func (s *Store) ExpireUnmatchedOutgoingPlaceholders(conversationIDs []string, nowMS int64) (int, error) {
	if len(conversationIDs) == 0 || nowMS <= 0 {
		return 0, nil
	}
	cutoff := nowMS - OutgoingPlaceholderMatchWindowMS
	holders := make([]string, len(conversationIDs))
	args := make([]any, 0, len(conversationIDs)+3)
	for i, id := range conversationIDs {
		holders[i] = "?"
		args = append(args, id)
	}
	args = append(args, cutoff, OutgoingPlaceholderPrefix, OutgoingServerTmpPrefix)
	rows, err := s.db.Query(`
		SELECT message_id, status, sender_name, sender_number
		FROM messages
		WHERE conversation_id IN (`+strings.Join(holders, ",")+`)
		  AND is_from_me = 1
		  AND timestamp_ms > 0
		  AND timestamp_ms < ?
		  AND (substr(message_id, 1, 3) = ? OR substr(message_id, 1, 4) = ? OR length(message_id) = 32 OR length(message_id) = 36)
	`, args...)
	if err != nil {
		return 0, err
	}
	var ids []string
	for rows.Next() {
		var id, status, senderName, senderNumber string
		if err := rows.Scan(&id, &status, &senderName, &senderNumber); err != nil {
			rows.Close()
			return 0, err
		}
		if localPlaceholderRow(&Message{MessageID: id, Status: status, IsFromMe: true, SenderName: senderName, SenderNumber: senderNumber}) {
			ids = append(ids, id)
		}
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return 0, err
	}
	rows.Close()
	if len(ids) == 0 {
		return 0, nil
	}
	tx, err := s.db.Begin()
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	in := make([]string, len(ids))
	delArgs := make([]any, len(ids))
	for i, id := range ids {
		in[i] = "?"
		delArgs[i] = id
	}
	if _, err := s.deleteMessages(tx, `message_id IN (`+strings.Join(in, ",")+`)`, delArgs...); err != nil {
		return 0, err
	}
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	return len(ids), nil
}

// dropMatchedOutgoingPlaceholders hides a sending placeholder when the real
// Google row is in the same result. The real row wins even if the placeholder
// is newer in the list.
func dropMatchedOutgoingPlaceholders(msgs []*Message) []*Message {
	if len(msgs) < 2 {
		return msgs
	}
	type pair struct {
		placeholderID string
		realID        string
		dist          int64
	}
	var pairs []pair
	for _, placeholder := range msgs {
		if !localPlaceholderRow(placeholder) {
			continue
		}
		for _, real := range msgs {
			if real == nil || !OutgoingPlaceholderMatches(placeholder, real) {
				continue
			}
			pairs = append(pairs, pair{placeholder.MessageID, real.MessageID, absInt64(placeholder.TimestampMS - real.TimestampMS)})
		}
	}
	if len(pairs) == 0 {
		return msgs
	}
	sort.Slice(pairs, func(i, j int) bool {
		if pairs[i].dist != pairs[j].dist {
			return pairs[i].dist < pairs[j].dist
		}
		return pairs[i].placeholderID < pairs[j].placeholderID
	})
	drop := map[string]bool{}
	usedReal := map[string]bool{}
	for _, pair := range pairs {
		if drop[pair.placeholderID] || usedReal[pair.realID] {
			continue
		}
		drop[pair.placeholderID] = true
		usedReal[pair.realID] = true
	}
	out := make([]*Message, 0, len(msgs)-len(drop))
	for _, msg := range msgs {
		if msg == nil || drop[msg.MessageID] {
			continue
		}
		out = append(out, msg)
	}
	return out
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

const clientIdempotencyPlaceholderCleanupTask = "cleanup_client_idempotency_placeholders_v1"

// CleanupClientIdempotencyPlaceholdersOnce removes optimistic sends stored
// under an Android UUID or a v2 transport request id. Those rows are not
// tm-/tmp_, so the earlier prefix sweep left them next to Google's copy.
// A still-sending row with a matching real message is deleted. One older than
// the match window is deleted even without a match: the echo is not coming,
// and the row is what the Android thread showed as a second bubble. A fresh
// unmatched send is kept. The sweep is one-time; later echoes and thread
// reads keep doing the same work.
func (s *Store) CleanupClientIdempotencyPlaceholdersOnce(nowMS int64) (bool, int, error) {
	if nowMS <= 0 {
		nowMS = time.Now().UnixMilli()
	}
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
	err = tx.QueryRow(`SELECT 1 FROM store_maintenance_tasks WHERE name = ?`, clientIdempotencyPlaceholderCleanupTask).Scan(&done)
	if err == nil {
		return false, 0, nil
	}
	if err != sql.ErrNoRows {
		return false, 0, err
	}
	removed, err := s.cleanupClientIdempotencyPlaceholdersTx(tx, nowMS)
	if err != nil {
		return false, 0, err
	}
	if _, err := tx.Exec(`INSERT INTO store_maintenance_tasks (name, done_at, result) VALUES (?, ?, ?)`,
		clientIdempotencyPlaceholderCleanupTask, time.Now().UnixMilli(), fmt.Sprintf("removed=%d", removed)); err != nil {
		return false, 0, err
	}
	if err := tx.Commit(); err != nil {
		return false, 0, err
	}
	return true, removed, nil
}

func (s *Store) cleanupClientIdempotencyPlaceholdersTx(tx *sql.Tx, nowMS int64) (int, error) {
	rows, err := queryMessagesTx(tx, `
		SELECT `+messageColumns+`
		FROM messages
		WHERE is_from_me = 1
		  AND (length(message_id) = 32 OR length(message_id) = 36)
		ORDER BY conversation_id, timestamp_ms ASC, message_id ASC
	`)
	if err != nil {
		return 0, err
	}
	var placeholders []*Message
	for _, m := range rows {
		if localPlaceholderRow(m) {
			placeholders = append(placeholders, m)
		}
	}
	if len(placeholders) == 0 {
		return 0, nil
	}
	byConv := map[string][]*Message{}
	var convOrder []string
	for _, p := range placeholders {
		if _, ok := byConv[p.ConversationID]; !ok {
			convOrder = append(convOrder, p.ConversationID)
		}
		byConv[p.ConversationID] = append(byConv[p.ConversationID], p)
	}
	cutoff := nowMS - OutgoingPlaceholderMatchWindowMS
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
		type pair struct {
			id, realID string
			dist       int64
		}
		var pairs []pair
		for _, p := range ps {
			for _, r := range reals {
				if !outgoingEchoMatch(p, r, false) {
					continue
				}
				pairs = append(pairs, pair{id: p.MessageID, realID: r.MessageID, dist: absInt64(p.TimestampMS - r.TimestampMS)})
			}
		}
		sort.SliceStable(pairs, func(i, j int) bool { return pairs[i].dist < pairs[j].dist })
		usedP, usedR := map[string]bool{}, map[string]bool{}
		for _, pr := range pairs {
			if usedP[pr.id] || usedR[pr.realID] {
				continue
			}
			usedP[pr.id], usedR[pr.realID] = true, true
			toDelete = append(toDelete, pr.id)
		}
		for _, p := range ps {
			if usedP[p.MessageID] {
				continue
			}
			if p.TimestampMS > 0 && p.TimestampMS < cutoff {
				toDelete = append(toDelete, p.MessageID)
			}
		}
	}
	for _, id := range toDelete {
		if _, err := s.deleteMessages(tx, `message_id = ?`, id); err != nil {
			return 0, err
		}
	}
	return len(toDelete), nil
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
