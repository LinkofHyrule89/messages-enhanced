package client

import (
	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"

	"github.com/maxghenis/openmessage/internal/db"
)

// GoogleConversationDeleted reports a conversation Google says was deleted
// (on the phone or from Messages for web); it must not be stored again.
func GoogleConversationDeleted(conv *gmproto.Conversation) bool {
	return conv.GetStatus() == gmproto.ConversationStatus_DELETED
}

// MirrorGoogleConversationStatus keeps the local archive tab in step with
// the phone: archived there -> "archive" here, active again (e.g. a new
// message) -> back to Recent. Other statuses leave the tab alone.
func MirrorGoogleConversationStatus(store *db.Store, logger zerolog.Logger, conv *gmproto.Conversation) {
	// Google's conversation type drives the composer's "RCS message" /
	// "Text message" placeholder.
	switch conv.GetType() {
	case gmproto.ConversationType_RCS:
		_ = store.SetConversationDisplayProtocol(conv.GetConversationID(), "RCS")
	case gmproto.ConversationType_SMS:
		_ = store.SetConversationDisplayProtocol(conv.GetConversationID(), "Text")
	}
	var archived bool
	switch conv.GetStatus() {
	case gmproto.ConversationStatus_ARCHIVED, gmproto.ConversationStatus_KEEP_ARCHIVED:
		archived = true
	case gmproto.ConversationStatus_ACTIVE:
		archived = false
		if changed, _ := store.UnmirrorGoogleSpam(conv.GetConversationID()); changed {
			logger.Info().Str("conv_id", conv.GetConversationID()).Msg("Conversation left Google spam/blocked")
		}
	case gmproto.ConversationStatus_SPAM_FOLDER, gmproto.ConversationStatus_BLOCKED_FOLDER:
		if changed, err := store.MirrorGoogleSpam(conv.GetConversationID()); err != nil {
			logger.Warn().Err(err).Str("conv_id", conv.GetConversationID()).Msg("Failed to mirror Google spam state")
		} else if changed {
			logger.Info().Str("conv_id", conv.GetConversationID()).Msg("Mirrored Google spam/blocked state")
		}
		return
	default:
		return
	}
	if changed, err := store.MirrorGoogleArchive(conv.GetConversationID(), archived); err != nil {
		logger.Warn().Err(err).Str("conv_id", conv.GetConversationID()).Msg("Failed to mirror Google archive state")
	} else if changed {
		logger.Info().Str("conv_id", conv.GetConversationID()).Bool("archived", archived).Msg("Mirrored Google archive state")
	}
}
