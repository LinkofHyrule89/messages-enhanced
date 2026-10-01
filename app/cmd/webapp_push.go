package cmd

import (
	"github.com/maxghenis/openmessage/internal/app"
	"github.com/maxghenis/openmessage/internal/webapp"
)

// webPushSource feeds the Web Push notifier from the message store.
func webPushSource(a *app.App) webapp.PushSource {
	return func(convID string, limit int) (webapp.PushConversation, []webapp.PushMessage, error) {
		var pc webapp.PushConversation
		conv, err := a.Store.GetConversation(convID)
		if err != nil {
			return pc, nil, err
		}
		if conv != nil {
			pc = webapp.PushConversation{ID: conv.ConversationID, Name: conv.Name, IsGroup: conv.IsGroup, NotificationMode: conv.NotificationMode}
		}
		pc.ID = convID
		msgs, err := a.Store.GetMessagesByConversation(convID, limit)
		if err != nil {
			return pc, nil, err
		}
		out := make([]webapp.PushMessage, 0, len(msgs))
		for _, m := range msgs {
			out = append(out, webapp.PushMessage{
				ID: m.MessageID, SenderName: m.SenderName, SenderNum: m.SenderNumber, Body: m.Body,
				MimeType: m.MimeType, HasMedia: m.MediaID != "", TimestampMS: m.TimestampMS,
				FromMe: m.IsFromMe, MentionsMe: m.MentionsMe,
			})
		}
		return pc, out, nil
	}
}
