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

// ClearGroupAvatar drops a cached group icon Google no longer provides. The row is kept, empty, with updated_at_ms
// bumped so AvatarVersion changes and clients drop their cached copy.
// Returns whether an image was cleared.
func (s *Store) ClearGroupAvatar(candidate ContactAvatarCandidate, nowMS int64) (bool, error) {
	avatarID := ContactAvatarID(candidate)
	if avatarID == "" {
		return false, nil
	}
	res, err := s.db.Exec(`
		UPDATE contact_avatars
		SET image_data = NULL, image_hash = '', source_url_hash = '',
			updated_at_ms = ?, last_checked_at_ms = ?
		WHERE avatar_id = ? AND image_hash != ''
	`, nowMS, nowMS, avatarID)
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

// AvatarHashUsedByPerson reports whether an image hash is cached as a
// person's photo (any non-group avatar row). A group icon must never be a
// member's photo.
func (s *Store) AvatarHashUsedByPerson(imageHash string) bool {
	if imageHash == "" {
		return false
	}
	var one int
	err := s.db.QueryRow(`
		SELECT 1 FROM contact_avatars
		WHERE image_hash = ? AND participant_id NOT LIKE 'conv:%'
		LIMIT 1
	`, imageHash).Scan(&one)
	return err == nil
}

// RepairGroupAvatars clears cached group icons that can't be the group's
// own icon: images identical to a person's cached photo, and images with no
// source URL (written by a thumbnail lookup by conversation ID, which
// returns the photo of the participant whose ID happens to equal the
// conversation ID). updated_at_ms is bumped so AvatarVersion changes and
// clients refetch. Returns how many rows were cleared.
func (s *Store) RepairGroupAvatars(nowMS int64) (int64, error) {
	res, err := s.db.Exec(`
		UPDATE contact_avatars
		SET image_data = NULL, image_hash = '', source_url_hash = '',
			updated_at_ms = ?, last_checked_at_ms = ?
		WHERE participant_id LIKE 'conv:%' AND image_hash != ''
		AND (source_url_hash = '' OR image_hash IN (
			SELECT image_hash FROM contact_avatars
			WHERE participant_id NOT LIKE 'conv:%' AND image_hash != ''
		))
	`, nowMS, nowMS)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

// EncryptedGroupIcon references an end-to-end encrypted group icon (Google
// Messages "MlsConversationIcon", Conversation field 39.4): a download URL
// and the file's encryption parameters. Held in memory only; the URL and key
// are never persisted or logged.
type EncryptedGroupIcon struct {
	URL      string
	FileName string // HKDF info (e.g. "group_icon")
	Key      []byte // 32-byte input key
	IV       []byte // 12-byte AES-CTR nonce
	Tag      []byte // 32-byte HMAC-SHA256 tag
	Length   uint32 // plaintext length
}

// SourceHash identifies this icon version for the cache (stored as
// source_url_hash): it changes whenever the icon is replaced.
func (e *EncryptedGroupIcon) SourceHash() string {
	if e == nil || e.URL == "" {
		return ""
	}
	sum := sha256.Sum256([]byte("mls:" + e.URL + "#" + hex.EncodeToString(e.Tag)))
	return hex.EncodeToString(sum[:])
}
