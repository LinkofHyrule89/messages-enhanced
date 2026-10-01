package app

import (
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/maxghenis/openmessage/internal/db"
)

// Pinned conversations. Google's pinned flag is synced read-only (stored on
// every conversation snapshot); the protocol has no pin/unpin action, so
// pins made from the car page are stored on this server only.

// CarConversationPinResult is returned after pinning or unpinning a conversation.
type CarConversationPinResult struct {
	ConversationID  string `json:"conversation_id"`
	Pinned          bool   `json:"pinned"`        // pinned locally
	GooglePinned    bool   `json:"google_pinned"` // pinned on the phone (read-only)
	LocalPinnedAtMS int64  `json:"local_pinned_at_ms"`
	Scope           string `json:"scope"` // "local": stored on this server only
	LocalPins       int    `json:"local_pins"`
	MaxLocalPins    int    `json:"max_local_pins"`
}

// CarPinConversation pins or unpins a conversation on this server.
func (a *App) CarPinConversation(conversationID string, pinned bool) (*CarConversationPinResult, error) {
	conversationID = strings.TrimSpace(conversationID)
	if conversationID == "" {
		return nil, carErr(http.StatusBadRequest, "conversation_id is required")
	}
	c, err := a.Store.SetConversationLocalPinned(conversationID, pinned, time.Now().UnixMilli())
	switch {
	case errors.Is(err, db.ErrConvPinNotFound):
		return nil, carErr(http.StatusNotFound, "conversation not found")
	case errors.Is(err, db.ErrConvPinLimit):
		return nil, carErr(http.StatusConflict, "You can pin up to %d conversations", db.MaxLocalConversationPins)
	case err != nil:
		return nil, carErr(http.StatusInternalServerError, "save pin: %v", err)
	}
	n, err := a.Store.CountLocalConversationPins()
	if err != nil {
		return nil, carErr(http.StatusInternalServerError, "count pins: %v", err)
	}
	a.Logger.Info().Str("conv_id", conversationID).Bool("pinned", pinned).Msg("Car page: conversation pin changed")
	a.emitConversationsChange()
	return &CarConversationPinResult{
		ConversationID:  c.ConversationID,
		Pinned:          c.LocalPinnedAtMS > 0,
		GooglePinned:    c.GooglePinned,
		LocalPinnedAtMS: c.LocalPinnedAtMS,
		Scope:           "local",
		LocalPins:       n,
		MaxLocalPins:    db.MaxLocalConversationPins,
	}, nil
}
