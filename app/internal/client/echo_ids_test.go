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

func TestPlaceholderIDsReadsAndroidUUIDButNotUnknownBytes(t *testing.T) {
	const uuid = "6ba7b810-9dad-41d1-80b4-00c04fd430c8"
	msg := &gmproto.Message{MessageID: "200342", TmpID: uuid}
	got := PlaceholderIDs(msg)
	if len(got) != 1 || got[0] != uuid {
		t.Fatalf("named tmp id = %v", got)
	}
	buried := &gmproto.Message{MessageID: "200342"}
	unknown := protowire.AppendTag(nil, 40, protowire.BytesType)
	unknown = protowire.AppendString(unknown, uuid)
	buried.ProtoReflect().SetUnknown(unknown)
	if got := PlaceholderIDs(buried); len(got) != 0 {
		t.Fatalf("unknown bytes must not be treated as a uuid placeholder: %v", got)
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
