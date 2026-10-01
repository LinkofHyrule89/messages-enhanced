package app

import (
	"database/sql"
	"errors"
	"net/http"
	"strings"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"

	"github.com/maxghenis/openmessage/internal/db"
)

// Conversation actions from the car page's ⋮ menu: Archive / Unarchive and
// Move to trash. Both go to Google Messages on the paired phone first (so
// they show up on the phone and in Messages for web), then the local copy is
// updated; with Google disconnected nothing changes, because a local-only
// change would be undone by the next sync.

// CarConversationActionResult reports a conversation action.
type CarConversationActionResult struct {
	ConversationID string `json:"conversation_id"`
	Action         string `json:"action"` // archive, unarchive, trash
	Archived       bool   `json:"archived,omitempty"`
	Scope          string `json:"scope"` // "google"
}

func (a *App) carGoogleConversation(conversationID string) (*db.Conversation, GMClient, error) {
	conversationID = strings.TrimSpace(conversationID)
	if conversationID == "" {
		return nil, nil, carErr(http.StatusBadRequest, "conversation_id is required")
	}
	c, err := a.Store.GetConversation(conversationID)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return nil, nil, carErr(http.StatusInternalServerError, "load conversation: %v", err)
	}
	// Conversations only in a live Google folder (Archived / Spam) aren't
	// stored locally; they're Google conversations by definition.
	if c != nil && !isGooglePlatform(c.SourcePlatform) {
		return nil, nil, carErr(http.StatusBadRequest, "only Google Messages conversations can be changed here")
	}
	gm := a.getGMClient()
	if gm == nil {
		return nil, nil, carErr(http.StatusServiceUnavailable, carGoogleDisconnectedMsg)
	}
	return c, gm, nil
}

// CarArchiveConversation archives (or unarchives) a conversation on the phone
// and mirrors it locally (tab "archive" hides it from the car's list).
func (a *App) CarArchiveConversation(conversationID string, archived bool) (*CarConversationActionResult, error) {
	c, gm, err := a.carGoogleConversation(conversationID)
	if err != nil {
		return nil, err
	}
	conversationID = strings.TrimSpace(conversationID)
	status := gmproto.ConversationStatus_ACTIVE
	action := "unarchive"
	if archived {
		status = gmproto.ConversationStatus_ARCHIVED
		action = "archive"
	}
	resp, err := gm.UpdateConversation(&gmproto.UpdateConversationRequest{
		ConversationID: conversationID,
		Data: &gmproto.UpdateConversationRequest_UpdateData{
			UpdateData: &gmproto.UpdateConversationData{
				ConversationID: conversationID,
				Data:           &gmproto.UpdateConversationData_Status{Status: status},
			},
		},
	})
	if err != nil {
		a.HandleGoogleAuthExpiredError(err)
		return nil, carErr(http.StatusBadGateway, "Google Messages couldn't %s it: %v", action, err)
	}
	if !resp.GetSuccess() {
		return nil, carErr(http.StatusBadGateway, "Google Messages refused to %s this conversation", action)
	}
	if c != nil {
		tab := db.TabInbox
		if archived {
			tab = db.TabArchive
		}
		if err := a.Store.SetConversationTab(conversationID, tab); err != nil {
			return nil, carErr(http.StatusInternalServerError, "update local copy: %v", err)
		}
	}
	a.Logger.Info().Str("conv_id", conversationID).Bool("archived", archived).Msg("Car page: conversation archive changed")
	a.emitConversationsChange()
	return &CarConversationActionResult{ConversationID: conversationID, Action: action, Archived: archived, Scope: "google"}, nil
}

// CarTrashConversation deletes a conversation on the phone (Google's delete
// conversation action, like Messages for web's "Delete") and removes the
// local copy.
func (a *App) CarTrashConversation(conversationID string) (*CarConversationActionResult, error) {
	c, gm, err := a.carGoogleConversation(conversationID)
	if err != nil {
		return nil, err
	}
	conversationID = strings.TrimSpace(conversationID)
	// Google wants the other person's number for 1:1 chats (empty for groups).
	phone := ""
	isGroup := false
	if c != nil {
		isGroup = c.IsGroup
		if !c.IsGroup {
			for _, p := range parseCarParticipants(c.Participants) {
				if !p.IsMe && strings.TrimSpace(p.Number) != "" {
					phone = strings.TrimSpace(p.Number)
					break
				}
			}
		}
	}
	if phone == "" && !isGroup {
		if conv, err := gm.GetConversation(conversationID); err == nil && conv != nil {
			if conv.GetStatus() == gmproto.ConversationStatus_DELETED {
				phone = "-" // already gone on the phone
			} else if !conv.GetIsGroupChat() {
				for _, p := range conv.GetParticipants() {
					if !p.GetIsMe() && p.GetID().GetNumber() != "" {
						phone = p.GetID().GetNumber()
						break
					}
				}
			}
		}
	}
	if phone != "-" {
		resp, err := gm.UpdateConversation(&gmproto.UpdateConversationRequest{
			Action:         gmproto.ConversationActionStatus_DELETE,
			ConversationID: conversationID,
			Data: &gmproto.UpdateConversationRequest_DeleteData{
				DeleteData: &gmproto.DeleteConversationData{ConversationID: conversationID, Phone: phone},
			},
		})
		if err != nil {
			a.HandleGoogleAuthExpiredError(err)
			return nil, carErr(http.StatusBadGateway, "Google Messages couldn't delete it: %v", err)
		}
		if !resp.GetSuccess() {
			return nil, carErr(http.StatusBadGateway, "Google Messages refused to delete this conversation")
		}
	}
	if c != nil {
		if err := a.Store.DeleteConversation(conversationID); err != nil {
			return nil, carErr(http.StatusInternalServerError, "delete local copy: %v", err)
		}
	}
	a.Logger.Info().Str("conv_id", conversationID).Msg("Car page: conversation moved to trash")
	a.emitMessagesChange(conversationID)
	a.emitConversationsChange()
	return &CarConversationActionResult{ConversationID: conversationID, Action: "trash", Scope: "google"}, nil
}
