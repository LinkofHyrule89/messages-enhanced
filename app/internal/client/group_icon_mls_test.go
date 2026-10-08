package client

import (
	"bytes"
	"testing"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
	"google.golang.org/protobuf/encoding/protowire"
)

func bytesField(num protowire.Number, v []byte) []byte {
	b := protowire.AppendTag(nil, num, protowire.BytesType)
	return protowire.AppendBytes(b, v)
}

func TestEncryptedGroupIconFromField39(t *testing.T) {
	key, iv, tag := bytes.Repeat([]byte{1}, 32), bytes.Repeat([]byte{2}, 12), bytes.Repeat([]byte{3}, 32)
	enc := append(append(append(bytesField(1, key), bytesField(2, iv)...), bytesField(3, tag)...),
		protowire.AppendVarint(protowire.AppendTag(nil, 4, protowire.VarintType), 1)...)
	enc = protowire.AppendFixed32(protowire.AppendTag(enc, 5, protowire.Fixed32Type), 1234)
	info := append(append(bytesField(1, []byte("group_icon")), bytesField(2, []byte("icon-name.jpeg"))...), bytesField(3, enc)...)
	thumb := append(bytesField(1, []byte("https://rcs-copper-us.googleapis.com/x")), bytesField(3, info)...)
	f39 := append(protowire.AppendVarint(protowire.AppendTag(nil, 1, protowire.VarintType), 1), bytesField(4, thumb)...)

	conv := &gmproto.Conversation{ConversationID: "g1", IsGroupChat: true}
	conv.ProtoReflect().SetUnknown(bytesField(39, f39))
	got := EncryptedGroupIcon(conv)
	if got == nil || got.URL != "https://rcs-copper-us.googleapis.com/x" || got.FileName != "group_icon" ||
		!bytes.Equal(got.Key, key) || !bytes.Equal(got.IV, iv) || !bytes.Equal(got.Tag, tag) || got.Length != 1234 {
		t.Fatalf("parsed = %+v", got)
	}
	c, ok := GroupAvatarCandidate(conv, "live")
	if !ok || c.EncryptedGroupIcon == nil || c.GroupAvatarURL != "" {
		t.Fatalf("candidate = %+v", c)
	}
	// Incomplete (no key) or not a group: nil.
	conv2 := &gmproto.Conversation{ConversationID: "g2", IsGroupChat: true}
	conv2.ProtoReflect().SetUnknown(bytesField(39, bytesField(4, bytesField(1, []byte("https://x.googleapis.com/y")))))
	if EncryptedGroupIcon(conv2) != nil {
		t.Fatal("incomplete reference must be ignored")
	}
	conv.IsGroupChat = false
	if EncryptedGroupIcon(conv) != nil {
		t.Fatal("1:1 conversation has no group icon")
	}
}
