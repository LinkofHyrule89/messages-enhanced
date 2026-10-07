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
// participant_id "conv:<conversationID>". Two sources:
//
//   - Conversation.groupAvatarURL, when Google sends it: downloaded unless the
//     cached image came from the same URL (Force downloads it anyway);
//   - otherwise (or if the download fails) GetParticipantThumbnail with the
//     conversation ID, which returns the group's current icon. Google often
//     omits the URL (e.g. after the icon is changed on the phone), so this is
//     the path that picks up changes; it's re-checked like contact photos
//     (googleAvatarSuccessTTL / googleAvatarMissingTTL) or at once when forced.
//
// A cached icon is replaced only when the image bytes differ (that bumps
// AvatarVersion so clients refetch), and cleared when Google reports no icon
// at all (no URL and an empty thumbnail). URLs are never logged or stored.
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
	cachedImage := ""
	if state != nil {
		cachedImage = state.ImageHash
	}
	if !candidate.Force && state != nil {
		checkedAgo := time.Since(time.UnixMilli(state.LastCheckedAtMS))
		switch {
		case iconURL != "" && state.SourceURLHash == urlHash:
			if state.ImageHash != "" {
				return // this exact icon is already cached
			}
			if checkedAgo < googleAvatarMissingTTL {
				return // this URL failed recently
			}
		case iconURL == "":
			if state.ImageHash != "" && checkedAgo < googleAvatarSuccessTTL {
				return
			}
			if state.ImageHash == "" && checkedAgo < googleAvatarMissingTTL {
				return
			}
		}
	}
	gm := a.getGMClient()
	if gm == nil {
		return
	}
	store := func(image []byte, from string) bool {
		if len(image) == 0 {
			return false
		}
		if len(image) > googleAvatarMaxBytes {
			log.Info().Str("reason", "too_large").Str("from", from).Msg("Google group icon not cached")
			return false
		}
		mimeType := http.DetectContentType(image)
		if !strings.HasPrefix(mimeType, "image/") || mimeType == "image/svg+xml" {
			log.Info().Str("reason", "not_an_image").Str("from", from).Msg("Google group icon not cached")
			return false
		}
		sum := sha256.Sum256(image)
		imageHash := hex.EncodeToString(sum[:])
		if err := a.Store.UpsertGroupAvatar(candidate, urlHash, image, mimeType, imageHash, now); err != nil {
			log.Debug().Err(err).Msg("Google group icon cache write failed")
			return true
		}
		if imageHash != cachedImage {
			log.Info().Int("bytes", len(image)).Str("mime", mimeType).Str("from", from).Bool("replaced", cachedImage != "").Msg("Google group icon cached")
		}
		return true
	}

	urlFailed := ""
	if iconURL != "" {
		if !allowedGroupAvatarURL(iconURL) {
			urlFailed = "url_not_allowed"
		} else {
			ctx, cancel := context.WithTimeout(context.Background(), googleGroupAvatarTimeout)
			image, err := gm.DownloadAvatar(ctx, iconURL)
			cancel()
			switch {
			case err != nil:
				urlFailed = "download_" + groupAvatarErrKind(err)
			case len(image) == 0:
				urlFailed = "empty_image"
			case store(image, "url"):
				return
			default:
				urlFailed = "bad_image"
			}
		}
		log.Info().Str("reason", urlFailed).Msg("Google group icon URL not usable; trying thumbnail")
	}

	resp, err := gm.GetParticipantThumbnail(convID)
	if err != nil {
		log.Info().Str("reason", "thumbnail_failed").Msg("Google group icon not cached")
		a.markGroupAvatarChecked(candidate, state, urlHash, now)
		return
	}
	image := thumbnailImageForIdentifier(resp, convID)
	if len(image) == 0 {
		if iconURL == "" && cachedImage != "" {
			// No URL and no thumbnail: the group has no icon any more.
			if cleared, err := a.Store.ClearGroupAvatar(candidate, now); err == nil && cleared {
				log.Info().Msg("Google group icon removed; cleared cached icon")
				return
			}
		}
		a.markGroupAvatarChecked(candidate, state, urlHash, now)
		return
	}
	if !store(image, "thumbnail") {
		a.markGroupAvatarChecked(candidate, state, urlHash, now)
	}
}

// markGroupAvatarChecked records a check that cached nothing new. A tried
// URL is recorded (so it isn't retried before googleAvatarMissingTTL) only
// when no older image is cached; otherwise a changed URL keeps being retried.
func (a *App) markGroupAvatarChecked(candidate db.ContactAvatarCandidate, state *db.GroupAvatarState, urlHash string, now int64) {
	tried := urlHash
	if state != nil && state.ImageHash != "" {
		tried = ""
	}
	_ = a.Store.MarkGroupAvatarChecked(candidate, tried, now)
}
