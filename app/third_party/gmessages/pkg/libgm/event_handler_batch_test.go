package libgm

import (
	"strings"
	"testing"

	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

func TestMessageBatchKeepsLaterMessagesAfterRepeatedID(t *testing.T) {
	c := NewClient(NewAuthData(), nil, zerolog.Nop())
	var got []string
	c.SetEventHandler(func(evt any) {
		msg, ok := evt.(*WrappedMessage)
		if !ok {
			return
		}
		got = append(got, msg.GetMessageID())
	})
	c.handleUpdatesEvent(&IncomingRPCMessage{
		Message:       &gmproto.RPCMessageData{Action: gmproto.ActionType_GET_UPDATES},
		DecryptedData: []byte("one-batch"),
		DecryptedMessage: &gmproto.UpdateEvents{Event: &gmproto.UpdateEvents_MessageEvent{MessageEvent: &gmproto.MessageEvent{Data: []*gmproto.Message{
			{MessageID: "dup"},
			{MessageID: "dup"},
			{MessageID: "kept"},
		}}}},
	})
	if strings.Join(got, ",") != "dup,kept" {
		t.Fatalf("emitted %v, want dup then kept", got)
	}
}
