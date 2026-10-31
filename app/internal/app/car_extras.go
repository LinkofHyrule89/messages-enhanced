package app

import (
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"

	"github.com/maxghenis/openmessage/internal/db"
)

// Phone-app parity actions: block / block + report spam, resend a failed
// message, tap-to-download MMS, full-size images, and Google's top contacts.

func (a *App) gmExtras() (GMExtras, error) {
	gm := a.getGMClient()
	if gm == nil {
		return nil, carErr(http.StatusServiceUnavailable, carGoogleDisconnectedMsg)
	}
	x, ok := gm.(GMExtras)
	if !ok {
		return nil, carErr(http.StatusNotImplemented, "not supported by this connection")
	}
	return x, nil
}

// CarBlockConversation blocks (optionally reporting spam) or unblocks a
// conversation on the phone. Blocked chats move to Spam & blocked.
func (a *App) CarBlockConversation(conversationID string, block, report bool) (*CarConversationActionResult, error) {
	c, gm, err := a.carGoogleConversation(conversationID)
	if err != nil {
		return nil, err
	}
	conversationID = strings.TrimSpace(conversationID)
	action, status, name := gmproto.ConversationActionStatus_UNBLOCK, gmproto.ConversationStatus_ACTIVE, "unblock"
	if block {
		action, status, name = gmproto.ConversationActionStatus_BLOCK, gmproto.ConversationStatus_SPAM_FOLDER, "block"
		if report {
			action, name = gmproto.ConversationActionStatus_BLOCK_AND_REPORT, "block_report"
		}
	}
	resp, err := gm.UpdateConversation(&gmproto.UpdateConversationRequest{
		Action:         action,
		ConversationID: conversationID,
		Data: &gmproto.UpdateConversationRequest_UpdateData{UpdateData: &gmproto.UpdateConversationData{
			ConversationID: conversationID,
			Data:           &gmproto.UpdateConversationData_Status{Status: status},
		}},
	})
	if err != nil {
		a.HandleGoogleAuthExpiredError(err)
		return nil, carErr(http.StatusBadGateway, "Google Messages couldn't %s it: %v", name, err)
	}
	if !resp.GetSuccess() {
		return nil, carErr(http.StatusBadGateway, "Google Messages refused to %s this conversation", name)
	}
	if c != nil {
		tab := db.TabInbox
		if block {
			tab = db.TabSpam
		}
		_ = a.Store.SetConversationTab(conversationID, tab)
	}
	a.Logger.Info().Str("conv_id", conversationID).Str("action", name).Msg("conversation block changed")
	a.emitConversationsChange()
	return &CarConversationActionResult{ConversationID: conversationID, Action: name, Scope: "google"}, nil
}

// CarMessageAction runs resend / download / fullsize for one message.
func (a *App) CarMessageAction(messageID, action string) (map[string]any, error) {
	messageID = strings.TrimSpace(messageID)
	if messageID == "" {
		return nil, carErr(http.StatusBadRequest, "message_id is required")
	}
	x, err := a.gmExtras()
	if err != nil {
		return nil, err
	}
	switch action {
	case "resend":
		err = x.ResendMessage(messageID)
	case "download":
		err = x.DownloadMessage(messageID)
	case "fullsize":
		_, err = x.GetFullSizeImage(messageID, uuid.NewString())
	default:
		return nil, carErr(http.StatusBadRequest, "unknown action")
	}
	if err != nil {
		a.HandleGoogleAuthExpiredError(err)
		return nil, carErr(http.StatusBadGateway, "Google Messages couldn't do that: %v", err)
	}
	return map[string]any{"message_id": messageID, "action": action, "ok": true}, nil
}

// googleTopContacts returns phone numbers in Google's "top contacts" order
// (best effort; nil when unavailable).
var topContactsCache struct {
	sync.Mutex
	at   time.Time
	list []CarContact
}

func (a *App) googleTopContacts() []CarContact {
	topContactsCache.Lock()
	defer topContactsCache.Unlock()
	if time.Since(topContactsCache.at) < 15*time.Minute {
		return topContactsCache.list
	}
	topContactsCache.at = time.Now()
	topContactsCache.list = a.fetchGoogleTopContacts()
	return topContactsCache.list
}

func (a *App) fetchGoogleTopContacts() []CarContact {
	x, err := a.gmExtras()
	if err != nil {
		return nil
	}
	resp, err := x.ListTopContacts()
	if err != nil || resp == nil {
		return nil
	}
	var out []CarContact
	for _, c := range resp.GetContacts() {
		n := strings.TrimSpace(c.GetNumber().GetNumber())
		if n == "" {
			continue
		}
		out = append(out, CarContact{Name: strings.TrimSpace(c.GetName()), Number: n, ContactID: c.GetContactID()})
	}
	return out
}
