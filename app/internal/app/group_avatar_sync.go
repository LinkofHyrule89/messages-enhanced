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

// fetchGoogleGroupAvatar downloads a group conversation's icon and caches it
// under participant_id "conv:<conversationID>". An icon whose URL hash matches
// the cached one is not downloaded again; a failed URL is retried after
// googleAvatarMissingTTL. The URL is never logged or stored.
func (a *App) fetchGoogleGroupAvatar(candidate db.ContactAvatarCandidate) {
	now := time.Now().UnixMilli()
	convID := strings.TrimPrefix(candidate.ParticipantID, db.GroupAvatarParticipantPrefix)
	iconURL := strings.TrimSpace(candidate.GroupAvatarURL)
	urlHash := db.GroupAvatarURLHash(iconURL)
	log := a.Logger.With().Str("conv_id", convID).Logger()

	state, err := a.Store.GetGroupAvatarState(candidate)
	if err != nil {
		log.Debug().Err(err).Msg("Google group icon lookup before fetch failed")
		return
	}
	if state != nil && state.SourceURLHash == urlHash {
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
	if err := a.Store.UpsertGroupAvatar(candidate, urlHash, image, mimeType, hex.EncodeToString(sum[:]), now); err != nil {
		log.Debug().Err(err).Msg("Google group icon cache write failed")
		return
	}
	log.Info().Int("bytes", len(image)).Str("mime", mimeType).Msg("Google group icon cached")
}
