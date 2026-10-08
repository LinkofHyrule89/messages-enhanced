package app

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/maxghenis/openmessage/internal/db"
)

const googleGroupAvatarTimeout = 30 * time.Second

// googleGroupAvatarHosts are the hosts a group icon URL may point at (suffix
// match). The URL comes from Google's conversation data; anything else is
// refused rather than fetched.
var googleGroupAvatarHosts = []string{"googleusercontent.com", "ggpht.com", "gstatic.com", "google.com", "googleapis.com"}

func allowedGroupAvatarURL(raw string) bool {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || u.Scheme != "https" || u.User != nil {
		return false
	}
	host := strings.ToLower(u.Hostname())
	for _, h := range googleGroupAvatarHosts {
		if host == h || strings.HasSuffix(host, "."+h) {
			return true
		}
	}
	return false
}

// groupAvatarErrKind describes a download error without its text, which for
// net/http errors contains the URL.
func groupAvatarErrKind(err error) string {
	var ue *url.Error
	if errors.As(err, &ue) {
		if ue.Timeout() {
			return "timeout"
		}
		return "request_failed"
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return "timeout"
	}
	if strings.Contains(err.Error(), "avatar download http ") {
		return "http_status"
	}
	return "other"
}

// fetchGoogleGroupAvatar caches a group conversation's icon under
// participant_id "conv:<conversationID>" from Conversation.groupAvatarURL.
// The download is skipped when the cached image came from the same URL
// (unless forced by Refresh everything); a failed URL keeps any older icon
// and is retried after googleAvatarMissingTTL.
//
// End-to-end encrypted (MLS) RCS groups carry no URL but an encrypted icon
// reference (EncryptedGroupIcon, see fetchEncryptedGroupIcon). With neither,
// the group has no icon: any cached icon is cleared so clients show the
// default group avatar instead of an outdated one. There is no thumbnail lookup by
// conversation ID: GetParticipantThumbnail takes participant IDs, and a
// conversation ID can equal a member's participant ID (it then returns that
// member's photo). An image identical to a person's cached photo is never
// stored as a group icon. URLs are never logged or stored.
func (a *App) fetchGoogleGroupAvatar(candidate db.ContactAvatarCandidate) {
	now := time.Now().UnixMilli()
	convID := strings.TrimPrefix(candidate.ParticipantID, db.GroupAvatarParticipantPrefix)
	iconURL := strings.TrimSpace(candidate.GroupAvatarURL)
	urlHash := db.GroupAvatarURLHash(iconURL)
	log := a.Logger.With().Str("conv_id", convID).Logger()

	if iconURL == "" && candidate.EncryptedGroupIcon != nil {
		a.fetchEncryptedGroupIcon(candidate, now)
		return
	}
	if iconURL == "" {
		if cleared, err := a.Store.ClearGroupAvatar(candidate, now); err != nil {
			log.Debug().Err(err).Msg("Google group icon clear failed")
		} else if cleared {
			log.Info().Msg("Google sent no group icon URL; cleared cached icon (default group avatar)")
		}
		return
	}
	state, err := a.Store.GetGroupAvatarState(candidate)
	if err != nil {
		log.Debug().Err(err).Msg("Google group icon lookup before fetch failed")
		return
	}
	if !candidate.Force && state != nil && state.SourceURLHash == urlHash {
		if state.ImageHash != "" {
			return // this exact icon is already cached
		}
		if time.Since(time.UnixMilli(state.LastCheckedAtMS)) < googleAvatarMissingTTL {
			return // this URL failed recently
		}
	}
	failed := func(reason string) {
		log.Info().Str("reason", reason).Msg("Google group icon not cached")
		tried := urlHash
		if state != nil && state.ImageHash != "" {
			tried = "" // keep the older icon, and keep retrying the new URL
		}
		_ = a.Store.MarkGroupAvatarChecked(candidate, tried, now)
	}
	if !allowedGroupAvatarURL(iconURL) {
		failed("url_not_allowed")
		return
	}
	gm := a.getGMClient()
	if gm == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), googleGroupAvatarTimeout)
	defer cancel()
	image, err := gm.DownloadAvatar(ctx, iconURL)
	if err != nil {
		failed("download_" + groupAvatarErrKind(err))
		return
	}
	if len(image) == 0 {
		failed("empty_image")
		return
	}
	if len(image) > googleAvatarMaxBytes {
		failed("too_large")
		return
	}
	mimeType := http.DetectContentType(image)
	if !strings.HasPrefix(mimeType, "image/") || mimeType == "image/svg+xml" {
		failed("not_an_image")
		return
	}
	sum := sha256.Sum256(image)
	imageHash := hex.EncodeToString(sum[:])
	if a.Store.AvatarHashUsedByPerson(imageHash) {
		_, _ = a.Store.ClearGroupAvatar(candidate, now)
		failed("matches_person_photo")
		return
	}
	if err := a.Store.UpsertGroupAvatar(candidate, urlHash, image, mimeType, imageHash, now); err != nil {
		log.Debug().Err(err).Msg("Google group icon cache write failed")
		return
	}
	if state == nil || state.ImageHash != imageHash {
		log.Info().Int("bytes", len(image)).Str("mime", mimeType).Msg("Google group icon cached")
	}
}

// RepairGroupAvatars clears wrongly cached group icons at startup (see
// db.Store.RepairGroupAvatars); clients refetch through AvatarVersion.
func (a *App) RepairGroupAvatars() {
	if a == nil || a.Store == nil {
		return
	}
	n, err := a.Store.RepairGroupAvatars(time.Now().UnixMilli())
	if err != nil {
		a.Logger.Warn().Err(err).Msg("Group icon repair failed")
		return
	}
	if n > 0 {
		a.Logger.Info().Int64("cleared", n).Msg("Cleared group icons that weren't the group's own icon")
	}
}

// fetchEncryptedGroupIcon downloads and decrypts an MLS group's icon (what
// Google's web client does for "MlsConversationIcon"), validates it as an
// image and caches it. A version already cached (same SourceHash) isn't
// fetched again unless forced; a failure drops the outdated cached icon and
// isn't retried before googleAvatarMissingTTL. The URL and key are never
// logged or stored.
func (a *App) fetchEncryptedGroupIcon(candidate db.ContactAvatarCandidate, now int64) {
	enc := candidate.EncryptedGroupIcon
	convID := strings.TrimPrefix(candidate.ParticipantID, db.GroupAvatarParticipantPrefix)
	log := a.Logger.With().Str("conv_id", convID).Logger()
	srcHash := enc.SourceHash()
	state, err := a.Store.GetGroupAvatarState(candidate)
	if err != nil {
		log.Debug().Err(err).Msg("Google group icon lookup before fetch failed")
		return
	}
	if !candidate.Force && state != nil && state.SourceURLHash == srcHash {
		if state.ImageHash != "" {
			return // this icon version is already cached
		}
		if time.Since(time.UnixMilli(state.LastCheckedAtMS)) < googleAvatarMissingTTL {
			return // failed recently
		}
	}
	failed := func(reason string) {
		log.Info().Str("reason", reason).Msg("Encrypted group icon not cached")
		_, _ = a.Store.ClearGroupAvatar(candidate, now)
		_ = a.Store.MarkGroupAvatarChecked(candidate, srcHash, now)
	}
	if !allowedGroupAvatarURL(enc.URL) {
		failed("url_not_allowed")
		return
	}
	if enc.Length == 0 || enc.Length > googleAvatarMaxBytes {
		failed("too_large")
		return
	}
	gm := a.getGMClient()
	if gm == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), googleGroupAvatarTimeout)
	defer cancel()
	blob, err := gm.DownloadAvatar(ctx, enc.URL)
	if err != nil {
		failed("download_" + groupAvatarErrKind(err))
		return
	}
	if len(blob) > 2*googleAvatarMaxBytes {
		failed("too_large")
		return
	}
	image, err := decryptMLSFile(enc.Key, enc.FileName, enc.IV, enc.Tag, enc.Length, blob)
	if err != nil {
		failed("decrypt_" + mlsFileErrKind(err))
		return
	}
	mimeType := http.DetectContentType(image)
	if !strings.HasPrefix(mimeType, "image/") || mimeType == "image/svg+xml" {
		failed("not_an_image")
		return
	}
	sum := sha256.Sum256(image)
	imageHash := hex.EncodeToString(sum[:])
	if a.Store.AvatarHashUsedByPerson(imageHash) {
		failed("matches_person_photo")
		return
	}
	if err := a.Store.UpsertGroupAvatar(candidate, srcHash, image, mimeType, imageHash, now); err != nil {
		log.Debug().Err(err).Msg("Google group icon cache write failed")
		return
	}
	if state == nil || state.ImageHash != imageHash {
		log.Info().Int("bytes", len(image)).Str("mime", mimeType).Msg("Encrypted group icon cached")
	}
}
