package client

import (
	"strings"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
	"google.golang.org/protobuf/encoding/protowire"

	"github.com/maxghenis/openmessage/internal/db"
)

// Google Messages sends the icon of an end-to-end encrypted (MLS) RCS group
// not as Conversation.groupAvatarURL (34) but in Conversation field 39, which
// the vendored libgm protos don't define. Layout, as read by Google's own web
// client (BugleConversationModelConversion, "Thumbnail encoding info"):
//
//	39       group E2EE state
//	39.4     thumbnail
//	39.4.1     download URL (string)
//	39.4.3     file info
//	39.4.3.1     file name (string; the HKDF info, e.g. "group_icon")
//	39.4.3.3     encryption
//	39.4.3.3.1     key (32 bytes)
//	39.4.3.3.2     iv (12 bytes)
//	39.4.3.3.3     tag (32 bytes)
//	39.4.3.3.5     plaintext length (fixed32)
//
// The fields are read from the message's unknown fields.

const (
	convFieldGroupE2EE  protowire.Number = 39
	groupE2EEThumbnail  protowire.Number = 4
	thumbnailURL        protowire.Number = 1
	thumbnailFileInfo   protowire.Number = 3
	fileInfoName        protowire.Number = 1
	fileInfoEncryption  protowire.Number = 3
	encryptionKey       protowire.Number = 1
	encryptionIV        protowire.Number = 2
	encryptionTag       protowire.Number = 3
	encryptionPlainSize protowire.Number = 5
)

// EncryptedGroupIcon returns the encrypted icon reference a group
// conversation carries in field 39, or nil when it has none (or it's
// incomplete).
func EncryptedGroupIcon(conv *gmproto.Conversation) *db.EncryptedGroupIcon {
	if conv == nil || !conv.GetIsGroupChat() {
		return nil
	}
	e2ee := protoBytesField(conv.ProtoReflect().GetUnknown(), convFieldGroupE2EE)
	thumb := protoBytesField(e2ee, groupE2EEThumbnail)
	info := protoBytesField(thumb, thumbnailFileInfo)
	enc := protoBytesField(info, fileInfoEncryption)
	icon := &db.EncryptedGroupIcon{
		URL:      strings.TrimSpace(string(protoBytesField(thumb, thumbnailURL))),
		FileName: string(protoBytesField(info, fileInfoName)),
		Key:      protoBytesField(enc, encryptionKey),
		IV:       protoBytesField(enc, encryptionIV),
		Tag:      protoBytesField(enc, encryptionTag),
	}
	size, ok := protoFixed32Field(enc, encryptionPlainSize)
	if !ok || size == 0 || icon.URL == "" || icon.FileName == "" ||
		len(icon.Key) != 32 || len(icon.IV) != 12 || len(icon.Tag) != 32 {
		return nil
	}
	icon.Length = size
	icon.Key = append([]byte(nil), icon.Key...)
	icon.IV = append([]byte(nil), icon.IV...)
	icon.Tag = append([]byte(nil), icon.Tag...)
	return icon
}

// protoBytesField returns the first length-delimited field num in a
// serialized message (nil if absent or malformed).
func protoBytesField(b []byte, num protowire.Number) []byte {
	for len(b) > 0 {
		n, typ, l := protowire.ConsumeTag(b)
		if l < 0 {
			return nil
		}
		b = b[l:]
		if n == num && typ == protowire.BytesType {
			v, m := protowire.ConsumeBytes(b)
			if m < 0 {
				return nil
			}
			return v
		}
		m := protowire.ConsumeFieldValue(n, typ, b)
		if m < 0 {
			return nil
		}
		b = b[m:]
	}
	return nil
}

// protoFixed32Field returns the first fixed32 field num in a serialized
// message.
func protoFixed32Field(b []byte, num protowire.Number) (uint32, bool) {
	for len(b) > 0 {
		n, typ, l := protowire.ConsumeTag(b)
		if l < 0 {
			return 0, false
		}
		b = b[l:]
		if n == num && typ == protowire.Fixed32Type {
			v, m := protowire.ConsumeFixed32(b)
			return v, m >= 0
		}
		m := protowire.ConsumeFieldValue(n, typ, b)
		if m < 0 {
			return 0, false
		}
		b = b[m:]
	}
	return 0, false
}
