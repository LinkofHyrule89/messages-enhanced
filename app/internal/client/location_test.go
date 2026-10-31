package client

import (
	"strings"
	"testing"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
	"google.golang.org/protobuf/encoding/protowire"
	"google.golang.org/protobuf/proto"
)

func TestLocationContentFromUnknownField(t *testing.T) {
	text, _ := proto.Marshal(&gmproto.MessageContent{Content: "Example Park, Springfield"})
	media, _ := proto.Marshal(&gmproto.MediaContent{MediaID: "synthetic-map", MimeType: "image/png"})
	var loc []byte
	loc = protowire.AppendTag(loc, 1, protowire.BytesType)
	loc = protowire.AppendBytes(loc, media)
	loc = protowire.AppendTag(loc, 2, protowire.BytesType)
	loc = protowire.AppendBytes(loc, text)
	var raw []byte
	raw = protowire.AppendTag(raw, 5, protowire.BytesType)
	raw = protowire.AppendBytes(raw, loc)
	info := &gmproto.MessageInfo{}
	if err := proto.Unmarshal(raw, info); err != nil {
		t.Fatal(err)
	}
	msg := &gmproto.Message{MessageInfo: []*gmproto.MessageInfo{info}}
	if !IsLocationMessage(msg) {
		t.Fatal("not detected")
	}
	if got := ExtractMessageBody(msg); got != "Example Park, Springfield" {
		t.Fatalf("body %q", got)
	}
	if mi := ExtractMediaInfo(msg); mi == nil || mi.MediaID != "synthetic-map" {
		t.Fatalf("media %+v", mi)
	}
	b := LocationBody(ExtractMessageBody(msg))
	if !HasMapsLink(b) || !strings.HasPrefix(b, "Example Park") {
		t.Fatalf("location body %q", b)
	}
	if LocationBody("geo:0,0") != "geo:0,0" {
		t.Fatal("kept link")
	}
}
