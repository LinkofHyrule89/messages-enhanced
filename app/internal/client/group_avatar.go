package client

import (
	"strings"
	"sync"

	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"

	"github.com/maxghenis/openmessage/internal/db"
)

// GroupAvatarCandidate returns the avatar-sync candidate for a Google group
// conversation's icon (Conversation.groupAvatarURL, seen on RCS groups), or
// ok=false when the conversation isn't a group or has no icon URL.
func GroupAvatarCandidate(conv *gmproto.Conversation, source string) (db.ContactAvatarCandidate, bool) {
	if conv == nil || !conv.GetIsGroupChat() {
		return db.ContactAvatarCandidate{}, false
	}
	iconURL := strings.TrimSpace(conv.GetGroupAvatarURL())
	participantID := db.GroupAvatarParticipantID(conv.GetConversationID())
	if iconURL == "" || participantID == "" {
		return db.ContactAvatarCandidate{}, false
	}
	return db.ContactAvatarCandidate{
		SourcePlatform: "sms",
		ParticipantID:  participantID,
		DisplayName:    conv.GetName(),
		Source:         source,
		GroupAvatarURL: iconURL,
	}, true
}

// groupAvatarPresenceLogged remembers, per group conversation ID, the last
// logged presence of groupAvatarURL, so each group is logged once per process
// (and again only if the field appears or disappears).
var groupAvatarPresenceLogged sync.Map

// LogGroupAvatarPresence logs (Info) whether Google sent a group icon URL for
// a group conversation. Only the conversation ID is logged, never the URL.
func LogGroupAvatarPresence(logger zerolog.Logger, conv *gmproto.Conversation) {
	if conv == nil || !conv.GetIsGroupChat() {
		return
	}
	convID := strings.TrimSpace(conv.GetConversationID())
	if convID == "" {
		return
	}
	present := strings.TrimSpace(conv.GetGroupAvatarURL()) != ""
	if prev, loaded := groupAvatarPresenceLogged.Swap(convID, present); loaded && prev.(bool) == present {
		return
	}
	logger.Info().
		Str("conv_id", convID).
		Bool("group_avatar_url_present", present).
		Msg("Google group conversation icon")
}
