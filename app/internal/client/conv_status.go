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
	var archived bool
	switch conv.GetStatus() {
	case gmproto.ConversationStatus_ARCHIVED, gmproto.ConversationStatus_KEEP_ARCHIVED:
		archived = true
	case gmproto.ConversationStatus_ACTIVE:
		archived = false
	default:
		return
	}
	if changed, err := store.MirrorGoogleArchive(conv.GetConversationID(), archived); err != nil {
		logger.Warn().Err(err).Str("conv_id", conv.GetConversationID()).Msg("Failed to mirror Google archive state")
	} else if changed {
		logger.Info().Str("conv_id", conv.GetConversationID()).Bool("archived", archived).Msg("Mirrored Google archive state")
	}
}
