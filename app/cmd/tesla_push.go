package cmd

import (
	"github.com/maxghenis/openmessage/internal/app"
	"github.com/maxghenis/openmessage/internal/tesla"
)

// teslaPushSource feeds the Tesla Web Push notifier from the message store.
func teslaPushSource(a *app.App) tesla.PushSource {
	return func(convID string, limit int) (tesla.PushConversation, []tesla.PushMessage, error) {
		var pc tesla.PushConversation
		conv, err := a.Store.GetConversation(convID)
		if err != nil {
			return pc, nil, err
		}
		if conv != nil {
			pc = tesla.PushConversation{ID: conv.ConversationID, Name: conv.Name, IsGroup: conv.IsGroup, NotificationMode: conv.NotificationMode}
		}
		pc.ID = convID
		msgs, err := a.Store.GetMessagesByConversation(convID, limit)
		if err != nil {
			return pc, nil, err
		}
		out := make([]tesla.PushMessage, 0, len(msgs))
		for _, m := range msgs {
			out = append(out, tesla.PushMessage{
				ID: m.MessageID, SenderName: m.SenderName, SenderNum: m.SenderNumber, Body: m.Body,
				MimeType: m.MimeType, HasMedia: m.MediaID != "", TimestampMS: m.TimestampMS,
				FromMe: m.IsFromMe, MentionsMe: m.MentionsMe,
			})
		}
		return pc, out, nil
	}
}
