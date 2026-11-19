package client

import (
	"testing"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
	"google.golang.org/protobuf/encoding/protowire"
)

func TestPlaceholderIDsReadsTmpAndAnotherMessageID(t *testing.T) {
	msg := &gmproto.Message{
		MessageID: "200342",
		TmpID:     "not-a-placeholder",
		AnotherMessageID: &gmproto.AnotherMessageID{
			MessageID: "tm-web-1",
			AnotherID: "tmp_264794425382",
		},
	}
	got := PlaceholderIDs(msg)
	if len(got) != 2 || got[0] != "tm-web-1" || got[1] != "tmp_264794425382" {
		t.Fatalf("ids = %v", got)
	}
}

func TestPlaceholderIDsReadsUnknownStringField(t *testing.T) {
	msg := &gmproto.Message{MessageID: "200342"}
	unknown := protowire.AppendTag(nil, 40, protowire.BytesType)
	unknown = protowire.AppendString(unknown, "tmp_999999999999")
	msg.ProtoReflect().SetUnknown(unknown)
	got := PlaceholderIDs(msg)
	if len(got) != 1 || got[0] != "tmp_999999999999" {
		t.Fatalf("ids = %v", got)
	}
}
