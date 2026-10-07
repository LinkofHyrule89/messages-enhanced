package app

import (
	"strings"
	"sync"
	"time"

	"github.com/maxghenis/openmessage/internal/db"
)

// markReadResendAfter: the same conversation/message pair isn't sent to
// Google again within this window (the web app may ask repeatedly as
// messages re-render, the tab regains focus, etc.).
const markReadResendAfter = 10 * time.Minute

type markReadSent struct {
	messageID string
	at        time.Time
}

var markReadSentMu sync.Mutex

// MarkReadOnGoogle tells Google Messages that a conversation is read up to
// its latest message (as Messages for Web does when a chat is viewed), so
// the phone marks it read and clears its notifications. Returns "google"
// when sent (or already sent for that message recently), "local" when there
// was nothing to send (not connected, no Google message). Only the web app's
// viewed-chat path calls this; MCP/API reads never do.
func (a *App) MarkReadOnGoogle(conversationID string) (string, error) {
	conversationID = strings.TrimSpace(conversationID)
	if a == nil || a.Store == nil || conversationID == "" {
		return "local", nil
	}
	gm := a.getGMClient()
	if gm == nil {
		return "local", nil
	}
	msgs, err := a.Store.GetMessagesByConversation(conversationID, 10)
	if err != nil {
		return "local", err
	}
	target := latestGoogleMessageForRead(msgs)
	if target == "" {
		return "local", nil
	}
	markReadSentMu.Lock()
	if a.markReadSent == nil {
		a.markReadSent = map[string]markReadSent{}
	}
	prev, ok := a.markReadSent[conversationID]
	if ok && prev.messageID == target && time.Since(prev.at) < markReadResendAfter {
		markReadSentMu.Unlock()
		return "google", nil
	}
	markReadSentMu.Unlock()

	if err := gm.MarkRead(conversationID, target); err != nil {
		a.HandleGoogleAuthExpiredError(err)
		a.Logger.Warn().Str("conv_id", conversationID).Msg("Google mark read failed")
		return "local", err
	}
	markReadSentMu.Lock()
	a.markReadSent[conversationID] = markReadSent{messageID: target, at: time.Now()}
	if len(a.markReadSent) > 2000 {
		for k, v := range a.markReadSent {
			if time.Since(v.at) > markReadResendAfter {
				delete(a.markReadSent, k)
			}
		}
	}
	markReadSentMu.Unlock()
	a.Logger.Info().Str("conv_id", conversationID).Msg("Marked read on Google")
	return "google", nil
}

// latestGoogleMessageForRead picks the newest message (msgs newest first)
// Google knows about: Google platform, not a local send placeholder;
// system tombstones only when there's nothing else.
func latestGoogleMessageForRead(msgs []*db.Message) string {
	tomb := ""
	for _, m := range msgs {
		if m == nil || !isGooglePlatform(m.SourcePlatform) || m.MessageID == "" ||
			strings.HasPrefix(m.MessageID, "tmp_") || db.IsOutgoingPlaceholderID(m.MessageID) {
			continue
		}
		if strings.Contains(strings.ToUpper(m.Status), "TOMBSTONE") {
			if tomb == "" {
				tomb = m.MessageID
			}
			continue
		}
		return m.MessageID
	}
	return tomb
}
