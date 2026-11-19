package libgm

import (
	"errors"
	"testing"

	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

func TestFinishSendMessageTreatsMissingStreamAckAsSent(t *testing.T) {
	resp, err := finishSendMessage(zerolog.Nop(), &gmproto.SendMessageRequest{
		TmpID:          "request-1",
		ConversationID: "conversation-1",
	}, nil, ErrResponseTimeout)
	if err != nil {
		t.Fatalf("timeout error = %v, want nil so the send is not retried", err)
	}
	if resp.GetStatus() != gmproto.SendMessageResponse_SUCCESS {
		t.Fatalf("status = %s, want SUCCESS", resp.GetStatus())
	}
}

func TestFinishSendMessageKeepsExplicitFailures(t *testing.T) {
	network := errors.New("dns timeout")
	resp, err := finishSendMessage(zerolog.Nop(), nil, &gmproto.SendMessageResponse{
		Status: gmproto.SendMessageResponse_FAILURE_4,
	}, network)
	if !errors.Is(err, network) {
		t.Fatalf("error = %v, want the transport error", err)
	}
	if resp.GetStatus() != gmproto.SendMessageResponse_FAILURE_4 {
		t.Fatalf("status = %s", resp.GetStatus())
	}

	ok, err := finishSendMessage(zerolog.Nop(), nil, &gmproto.SendMessageResponse{
		Status: gmproto.SendMessageResponse_SUCCESS,
	}, nil)
	if err != nil || ok.GetStatus() != gmproto.SendMessageResponse_SUCCESS {
		t.Fatalf("success passthrough = %+v, %v", ok, err)
	}
}
