package app

import (
	"net/http"
	"strings"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// Mute, mark-read and composer details for the car page.

// CarConversationMeta is what the composer and header need.
type CarConversationMeta struct {
	ConversationID string `json:"conversation_id"`
	Protocol       string `json:"protocol"` // "RCS", "SMS" or "" (unknown / other platform)
	E2EE           bool   `json:"e2ee"`
	Muted          bool   `json:"muted"`
}

func (a *App) CarConversationMeta(conversationID string) (*CarConversationMeta, error) {
	conversationID = strings.TrimSpace(conversationID)
	if conversationID == "" {
		return nil, carErr(http.StatusBadRequest, "conversation_id is required")
	}
	out := &CarConversationMeta{ConversationID: conversationID}
	c, err := a.Store.GetConversation(conversationID)
	if err != nil || c == nil {
		return out, nil // e.g. a live folder conversation: nothing known
	}
	out.Muted = c.NotificationMode == "muted"
	if !isGooglePlatform(c.SourcePlatform) {
		return out, nil
	}
	switch c.DisplayProtocol {
	case "RCS":
		out.Protocol = "RCS"
	case "Text":
		out.Protocol = "SMS"
	}
	if out.Protocol == "RCS" {
		if st, err := a.Store.LatestEncryptionTombstone(conversationID); err == nil && st != "" {
			out.E2EE = strings.Contains(st, "ENCRYPTED") && !strings.Contains(st, "LOST_ENCRYPTION")
		}
	}
	return out, nil
}

// CarConversationMuteResult reports a mute change.
type CarConversationMuteResult struct {
	ConversationID string `json:"conversation_id"`
	Muted          bool   `json:"muted"`
	// Scope: "google" when the phone was muted too, "local" when only this
	// server mutes it (no push notifications), e.g. Google disconnected.
	Scope string `json:"scope"`
}

// CarMuteConversation mutes/unmutes on the phone (Google's conversation
// mute) when it can, and always on this server (muted = no push).
func (a *App) CarMuteConversation(conversationID string, muted bool) (*CarConversationMuteResult, error) {
	conversationID = strings.TrimSpace(conversationID)
	if conversationID == "" {
		return nil, carErr(http.StatusBadRequest, "conversation_id is required")
	}
	c, err := a.Store.GetConversation(conversationID)
	if err != nil || c == nil {
		return nil, carErr(http.StatusNotFound, "conversation not found")
	}
	scope := "local"
	if gm := a.getGMClient(); gm != nil && isGooglePlatform(c.SourcePlatform) {
		st := gmproto.ConversationMuteStatus_UNMUTE
		if muted {
			st = gmproto.ConversationMuteStatus_MUTE
		}
		resp, err := gm.UpdateConversation(&gmproto.UpdateConversationRequest{
			ConversationID: conversationID,
			Data: &gmproto.UpdateConversationRequest_UpdateData{UpdateData: &gmproto.UpdateConversationData{
				ConversationID: conversationID,
				Data:           &gmproto.UpdateConversationData_Mute{Mute: st},
			}},
		})
		if err != nil {
			a.HandleGoogleAuthExpiredError(err)
			a.Logger.Warn().Err(err).Str("conv_id", conversationID).Msg("Google mute failed; muting on this server only")
		} else if resp.GetSuccess() {
			scope = "google"
		}
	}
	mode := "all"
	if muted {
		mode = "muted"
	}
	if err := a.Store.SetConversationNotificationMode(conversationID, mode); err != nil {
		return nil, carErr(http.StatusInternalServerError, "save mute: %v", err)
	}
	a.Logger.Info().Str("conv_id", conversationID).Bool("muted", muted).Str("scope", scope).Msg("Car page: conversation mute changed")
	a.emitConversationsChange()
	return &CarConversationMuteResult{ConversationID: conversationID, Muted: muted, Scope: scope}, nil
}

// CarMarkConversationRead marks it read on the phone (when connected) and here.
func (a *App) CarMarkConversationRead(conversationID string) (map[string]any, error) {
	conversationID = strings.TrimSpace(conversationID)
	if conversationID == "" {
		return nil, carErr(http.StatusBadRequest, "conversation_id is required")
	}
	scope := "local"
	if gm := a.getGMClient(); gm != nil {
		if msgs, err := a.Store.GetMessagesByConversation(conversationID, 1); err == nil && len(msgs) > 0 && isGooglePlatform(msgs[0].SourcePlatform) {
			if err := gm.MarkRead(conversationID, msgs[0].MessageID); err == nil {
				scope = "google"
			} else {
				a.HandleGoogleAuthExpiredError(err)
			}
		}
	}
	if err := a.Store.MarkConversationRead(conversationID); err != nil {
		return nil, carErr(http.StatusInternalServerError, "mark read: %v", err)
	}
	a.emitConversationsChange()
	return map[string]any{"conversation_id": conversationID, "read": true, "scope": scope}, nil
}
