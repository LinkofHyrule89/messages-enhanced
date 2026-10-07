package client

import (
	"strings"
	"sync"

	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"

	"github.com/maxghenis/openmessage/internal/db"
)

// GroupAvatarCandidate returns the avatar-sync candidate for a Google group
// conversation's icon, or ok=false when the conversation isn't a group. The
// candidate carries Conversation.groupAvatarURL when Google sends one; it is
// returned without a URL too, since Google often omits the field and serves
// the (current) icon only through GetParticipantThumbnail(conversationID).
func GroupAvatarCandidate(conv *gmproto.Conversation, source string) (db.ContactAvatarCandidate, bool) {
	if conv == nil || !conv.GetIsGroupChat() {
		return db.ContactAvatarCandidate{}, false
	}
	c, ok := GroupIconCandidate(conv.GetConversationID(), source, false)
	if !ok {
		return c, false
	}
	c.DisplayName = conv.GetName()
	c.GroupAvatarURL = strings.TrimSpace(conv.GetGroupAvatarURL())
	return c, true
}

// GroupIconCandidate returns a group-icon candidate for a conversation ID
// (no URL); force re-checks Google even if the cached icon is fresh.
func GroupIconCandidate(conversationID, source string, force bool) (db.ContactAvatarCandidate, bool) {
	participantID := db.GroupAvatarParticipantID(conversationID)
	if participantID == "" {
		return db.ContactAvatarCandidate{}, false
	}
	return db.ContactAvatarCandidate{
		SourcePlatform: "sms",
		ParticipantID:  participantID,
		Source:         source,
		GroupIcon:      true,
		Force:          force,
	}, true
}

// GroupIconEventStatus reports whether a message status is Google's "group
// icon changed" / "group icon removed" event.
func GroupIconEventStatus(status gmproto.MessageStatusType) bool {
	switch status {
	case gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_GROUP_ICON_CHANGED_GLOBAL,
		gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_GROUP_ICON_CLEARED_GLOBAL:
		return true
	}
	return false
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
