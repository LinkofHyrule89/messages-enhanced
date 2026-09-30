package db

import (
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"strings"
)

// Group conversation icons (Google Messages RCS groups) are cached in
// contact_avatars like people's photos, under the participant ID
// "conv:<conversationID>", so GET /api/avatar?source=sms&participant_id=conv:<id>
// serves them with no new endpoint. source_url_hash records which icon URL the
// cached image came from.

// GroupAvatarParticipantPrefix prefixes the synthetic participant ID of a
// group conversation's icon.
const GroupAvatarParticipantPrefix = "conv:"

// GroupAvatarParticipantID returns the contact_avatars participant_id used for
// a group conversation's icon ("" for an empty conversation ID).
func GroupAvatarParticipantID(conversationID string) string {
	conversationID = strings.TrimSpace(conversationID)
	if conversationID == "" {
		return ""
	}
	return GroupAvatarParticipantPrefix + conversationID
}

// GroupAvatarURLHash is the hex sha256 of a (trimmed) icon URL. Only the hash
// is stored; the URL itself is never persisted.
func GroupAvatarURLHash(rawURL string) string {
	rawURL = strings.TrimSpace(rawURL)
	if rawURL == "" {
		return ""
	}
	sum := sha256.Sum256([]byte(rawURL))
	return hex.EncodeToString(sum[:])
}

// GroupAvatarState is what's cached for a group icon.
type GroupAvatarState struct {
	SourceURLHash   string
	ImageHash       string
	LastCheckedAtMS int64
}

// GetGroupAvatarState returns the cached state for a candidate's group icon,
// or nil when nothing has been recorded yet.
func (s *Store) GetGroupAvatarState(candidate ContactAvatarCandidate) (*GroupAvatarState, error) {
	avatarID := ContactAvatarID(candidate)
	if avatarID == "" {
		return nil, nil
	}
	st := &GroupAvatarState{}
	err := s.db.QueryRow(`
		SELECT source_url_hash, image_hash, last_checked_at_ms
		FROM contact_avatars WHERE avatar_id = ?
	`, avatarID).Scan(&st.SourceURLHash, &st.ImageHash, &st.LastCheckedAtMS)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return st, nil
}

// UpsertGroupAvatar stores a downloaded group icon and the hash of the URL it
// came from.
func (s *Store) UpsertGroupAvatar(candidate ContactAvatarCandidate, urlHash string, imageData []byte, mimeType, imageHash string, nowMS int64) error {
	if err := s.UpsertContactAvatar(candidate, imageData, mimeType, imageHash, nowMS); err != nil {
		return err
	}
	_, err := s.db.Exec(`UPDATE contact_avatars SET source_url_hash = ? WHERE avatar_id = ?`, urlHash, ContactAvatarID(candidate))
	return err
}

// MarkGroupAvatarChecked records a failed or skipped check. urlHash, when
// non-empty, is recorded as the URL that was tried (callers pass "" when an
// older image is still cached, so a changed URL keeps being retried).
func (s *Store) MarkGroupAvatarChecked(candidate ContactAvatarCandidate, urlHash string, nowMS int64) error {
	if err := s.MarkContactAvatarChecked(candidate, nowMS); err != nil {
		return err
	}
	if urlHash == "" {
		return nil
	}
	_, err := s.db.Exec(`UPDATE contact_avatars SET source_url_hash = ? WHERE avatar_id = ?`, urlHash, ContactAvatarID(candidate))
	return err
}
