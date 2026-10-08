package client

import (
	"strings"
	"sync"

	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"

	"github.com/maxghenis/openmessage/internal/db"
)

// GroupAvatarCandidate returns the avatar-sync candidate for a Google group
// conversation's icon, or ok=false when the conversation isn't a group. It
// carries Conversation.groupAvatarURL when Google sends one, else the
// end-to-end encrypted icon reference of an MLS group (field 39), else
// neither: the group has no icon, and the sync drops any cached one so
// clients show the default group avatar.
func GroupAvatarCandidate(conv *gmproto.Conversation, source string) (db.ContactAvatarCandidate, bool) {
	if conv == nil || !conv.GetIsGroupChat() {
		return db.ContactAvatarCandidate{}, false
	}
	participantID := db.GroupAvatarParticipantID(conv.GetConversationID())
	if participantID == "" {
		return db.ContactAvatarCandidate{}, false
	}
	return db.ContactAvatarCandidate{
		SourcePlatform:     "sms",
		ParticipantID:      participantID,
		DisplayName:        conv.GetName(),
		Source:             source,
		GroupAvatarURL:     strings.TrimSpace(conv.GetGroupAvatarURL()),
		GroupIcon:          true,
		EncryptedGroupIcon: EncryptedGroupIcon(conv),
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
	encrypted := EncryptedGroupIcon(conv) != nil
	state := 0
	if present {
		state |= 1
	}
	if encrypted {
		state |= 2
	}
	if prev, loaded := groupAvatarPresenceLogged.Swap(convID, state); loaded && prev.(int) == state {
		return
	}
	logger.Info().
		Str("conv_id", convID).
		Bool("group_avatar_url_present", present).
		Bool("encrypted_icon_present", encrypted).
		Msg("Google group conversation icon")
}
