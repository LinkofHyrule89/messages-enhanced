package app

import (
	"fmt"
	"strings"
	"time"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"

	"github.com/maxghenis/openmessage/internal/db"
)

// Same slack as the web API: Google's echo timestamp can land a few seconds
// before the local clock when the placeholder is written.
const outgoingEchoClockSlackMS = int64(5000)

// DropPlaceholderIfEchoed removes a just-written optimistic row when Google's
// real copy already arrived (echo won the race). Covers both tm- (web) and
// tmp_ (MCP / SendTextToConversation) IDs.
func (a *App) DropPlaceholderIfEchoed(placeholderID string, sendStartedMS int64) {
	if a == nil || a.Store == nil || !db.IsOutgoingPlaceholderID(placeholderID) {
		return
	}
	realID, err := a.Store.DeleteOutgoingPlaceholderIfEchoed(placeholderID, sendStartedMS-outgoingEchoClockSlackMS)
	if err != nil {
		a.Logger.Warn().Err(err).Str("tmp_id", placeholderID).Msg("Failed to check send placeholder against stored echo")
		return
	}
	if realID != "" {
		a.Logger.Debug().Str("tmp_id", placeholderID).Str("msg_id", realID).Msg("Echo arrived before placeholder; removed placeholder")
	}
}

var (
	sendWhatsAppConversationText = func(a *App, conversationID, body, replyToID string) (*db.Message, error) {
		return a.SendWhatsAppText(conversationID, body, replyToID)
	}
	sendSignalConversationText = func(a *App, conversationID, body, replyToID string) (*db.Message, error) {
		return a.SendSignalText(conversationID, body, replyToID)
	}
)

func (a *App) SendTextToConversation(conversationID, body string) (*db.Conversation, *db.Message, error) {
	conv, err := a.Store.GetConversation(conversationID)
	if err != nil {
		return nil, nil, fmt.Errorf("get conversation: %w", err)
	}
	if conv == nil {
		return nil, nil, fmt.Errorf("conversation %s not found", conversationID)
	}

	switch normalizeConversationPlatform(conv.SourcePlatform) {
	case "whatsapp":
		msg, err := sendWhatsAppConversationText(a, conversationID, body, "")
		if err != nil {
			return conv, nil, fmt.Errorf("send WhatsApp message: %w", err)
		}
		if err := a.Store.RecordOutgoingMessage(msg, ""); err != nil {
			return conv, nil, fmt.Errorf("persist sent message: %w", err)
		}
		return conv, msg, nil
	case "signal":
		msg, err := sendSignalConversationText(a, conversationID, body, "")
		if err != nil {
			return conv, nil, fmt.Errorf("send Signal message: %w", err)
		}
		if err := a.Store.RecordOutgoingMessage(msg, ""); err != nil {
			return conv, nil, fmt.Errorf("persist sent message: %w", err)
		}
		return conv, msg, nil
	case "sms":
		gmConv, err := getGoogleConversationForSend(a, conversationID)
		if err != nil {
			if !a.HandleGoogleAuthExpiredError(err) {
				a.RecordGoogleSendError(err)
			}
			return conv, nil, fmt.Errorf("get Google conversation: %w", err)
		}
		payload, err := buildGoogleTextPayload(gmConv, conversationID, body)
		if err != nil {
			return conv, nil, err
		}
		resp, err := sendGoogleTextPayload(a, payload)
		if err != nil {
			if !a.HandleGoogleAuthExpiredError(err) {
				a.RecordGoogleSendError(err)
			}
			return conv, nil, fmt.Errorf("send Google message: %w", err)
		}
		if resp.GetStatus() != gmproto.SendMessageResponse_SUCCESS {
			a.RecordGoogleSendOutcomeWithPhone(false, a.GooglePhoneResponding())
			return conv, nil, fmt.Errorf("%s", GoogleSendRejectedMessage(resp.GetStatus().String(), a.GooglePhoneResponding()))
		}
		a.RecordGoogleSendOutcome(true)
		msg := &db.Message{
			MessageID:      payload.TmpID,
			ConversationID: conversationID,
			Body:           body,
			IsFromMe:       true,
			TimestampMS:    time.Now().UnixMilli(),
			Status:         "OUTGOING_SENDING",
			SourcePlatform: "sms",
		}
		if err := a.Store.RecordOutgoingMessage(msg, ""); err != nil {
			return conv, nil, fmt.Errorf("persist sent message: %w", err)
		}
		a.DropPlaceholderIfEchoed(msg.MessageID, msg.TimestampMS)
		return conv, msg, nil
	default:
		return conv, nil, fmt.Errorf("sending is not supported for platform %s via OpenMessage yet", conv.SourcePlatform)
	}
}

// GoogleSendRejectedMessage builds the user-facing error for a non-SUCCESS
// Google send. UNKNOWN can mean either a temporarily unreachable phone or a
// stale linked-device session; phone reachability lets us point at the right
// recovery path.
func GoogleSendRejectedMessage(status string, phoneResponding bool) string {
	if !phoneResponding {
		return "send failed (Google Messages returned " + status + "): your phone isn't responding to OpenMessage right now. " +
			"Make sure your phone is on and connected to the internet, then try again."
	}
	return "send failed (Google Messages returned " + status + "). If this keeps happening your phone has " +
		"likely unlinked OpenMessage - open Platforms -> Google Messages -> Pair again. " +
		"Also confirm Messages is set as your phone's default SMS app."
}

func normalizeConversationPlatform(platform string) string {
	switch strings.ToLower(strings.TrimSpace(platform)) {
	case "", "sms":
		return "sms"
	case "whatsapp", "signal":
		return strings.ToLower(strings.TrimSpace(platform))
	default:
		return strings.ToLower(strings.TrimSpace(platform))
	}
}

func buildGoogleTextPayload(conv *gmproto.Conversation, conversationID, body string) (*gmproto.SendMessageRequest, error) {
	if conv == nil {
		return nil, fmt.Errorf("get Google conversation: no conversation returned")
	}
	myParticipantID, simPayload := ExtractSIMAndParticipant(conv)
	return BuildSendPayload(conversationID, body, "", myParticipantID, simPayload), nil
}
