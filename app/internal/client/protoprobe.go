package client

// Proto probe (diagnostic): logs the Google Messages fields that might carry
// end-to-end-encryption state (conversation type/subType/thirdType/sendMode,
// message type/someInt, and any protobuf fields libgm doesn't know), so the
// real E2EE flag can be identified. No message text is logged. Each distinct
// field combination is logged once per conversation; bounded memory.

import (
	"fmt"
	"sort"
	"strings"
	"sync"

	"github.com/rs/zerolog"
	"google.golang.org/protobuf/encoding/protowire"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/reflect/protoreflect"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"

	"github.com/maxghenis/openmessage/internal/db"
)

var probeSeen = struct {
	sync.Mutex
	m map[string]bool
}{m: map[string]bool{}}

func probeOnce(key string) bool {
	probeSeen.Lock()
	defer probeSeen.Unlock()
	if probeSeen.m[key] || len(probeSeen.m) > 5000 {
		return false
	}
	probeSeen.m[key] = true
	return true
}

// unknownFields lists unknown fields of m (recursively, as path=value; byte
// values are shown only as a length).
func unknownFields(m protoreflect.Message, prefix string, depth int) []string {
	var out []string
	b := m.GetUnknown()
	for len(b) > 0 {
		num, typ, n := protowire.ConsumeTag(b)
		if n < 0 {
			break
		}
		b = b[n:]
		var v string
		switch typ {
		case protowire.VarintType:
			x, k := protowire.ConsumeVarint(b)
			if k < 0 {
				return out
			}
			v, b = fmt.Sprint(x), b[k:]
		case protowire.BytesType:
			x, k := protowire.ConsumeBytes(b)
			if k < 0 {
				return out
			}
			v, b = decodeBlob(x, 0), b[k:]
		case protowire.Fixed32Type:
			_, k := protowire.ConsumeFixed32(b)
			v, b = "f32", b[k:]
		case protowire.Fixed64Type:
			_, k := protowire.ConsumeFixed64(b)
			v, b = "f64", b[k:]
		default:
			k := protowire.ConsumeFieldValue(num, typ, b)
			if k < 0 {
				return out
			}
			v, b = "?", b[k:]
		}
		out = append(out, fmt.Sprintf("%s%d=%s", prefix, num, v))
	}
	if depth < 2 {
		m.Range(func(fd protoreflect.FieldDescriptor, val protoreflect.Value) bool {
			if fd.Kind() == protoreflect.MessageKind && !fd.IsList() && !fd.IsMap() {
				out = append(out, unknownFields(val.Message(), prefix+string(fd.Name())+".", depth+1)...)
			}
			return true
		})
	}
	sort.Strings(out)
	return out
}

func ProbeConversation(logger zerolog.Logger, conv *gmproto.Conversation) {
	if conv == nil {
		return
	}
	unk := strings.Join(unknownFields(proto.Clone(conv).ProtoReflect(), "", 0), " ")
	key := fmt.Sprintf("c|%s|%v|%v|%v|%v|%s", conv.GetConversationID(), conv.GetType(), conv.GetSubType(), conv.GetThirdType(), conv.GetSendMode(), unk)
	if !probeOnce(key) {
		return
	}
	logger.Info().Str("conv_id", conv.GetConversationID()).Bool("group", conv.GetIsGroupChat()).
		Str("type", conv.GetType().String()).Bool("sub_type", conv.GetSubType()).Bool("third_type", conv.GetThirdType()).
		Str("send_mode", conv.GetSendMode().String()).Str("unknown", unk).Msg("E2EE probe: conversation")
}

func ProbeMessage(logger zerolog.Logger, msg *gmproto.Message) {
	if msg == nil {
		return
	}
	unk := unknownFields(msg.ProtoReflect(), "", 0)
	var keep []string
	for _, u := range unk {
		if !strings.HasPrefix(u, "messageInfo") {
			keep = append(keep, u)
		}
	}
	u := strings.Join(keep, " ")
	key := fmt.Sprintf("m|%s|%d|%d|%v|%v|%s", msg.GetConversationID(), msg.GetType(), msg.GetSomeInt(), msg.GetMessageStatus().GetStatus(), msg.GetMsgType(), u)
	if !probeOnce(key) {
		return
	}
	logger.Info().Str("conv_id", msg.GetConversationID()).Int64("type", msg.GetType()).Int64("some_int", msg.GetSomeInt()).
		Str("status", msg.GetMessageStatus().GetStatus().String()).Str("msg_type", fmt.Sprint(msg.GetMsgType())).Str("unknown", u).Msg("E2EE probe: message")
}

// decodeBlob shows a bytes field as a nested message ({n=v ...}) when it
// parses as one, else only its length (no raw content).
func decodeBlob(x []byte, depth int) string {
	if len(x) > 0 && len(x) <= 4 {
		return fmt.Sprintf("x%x", x) // tiny: flags / packed enums, never text worth hiding
	}
	if depth > 3 || len(x) == 0 {
		return fmt.Sprintf("b(%d)", len(x))
	}
	var parts []string
	b := x
	for len(b) > 0 {
		num, typ, n := protowire.ConsumeTag(b)
		if n < 0 || num > 200 {
			return fmt.Sprintf("b(%d)", len(x))
		}
		b = b[n:]
		switch typ {
		case protowire.VarintType:
			v, k := protowire.ConsumeVarint(b)
			if k < 0 {
				return fmt.Sprintf("b(%d)", len(x))
			}
			parts, b = append(parts, fmt.Sprintf("%d=%d", num, v)), b[k:]
		case protowire.BytesType:
			v, k := protowire.ConsumeBytes(b)
			if k < 0 {
				return fmt.Sprintf("b(%d)", len(x))
			}
			parts, b = append(parts, fmt.Sprintf("%d=%s", num, decodeBlob(v, depth+1))), b[k:]
		case protowire.Fixed32Type:
			_, k := protowire.ConsumeFixed32(b)
			if k < 0 {
				return fmt.Sprintf("b(%d)", len(x))
			}
			parts, b = append(parts, fmt.Sprintf("%d=f32", num)), b[k:]
		case protowire.Fixed64Type:
			_, k := protowire.ConsumeFixed64(b)
			if k < 0 {
				return fmt.Sprintf("b(%d)", len(x))
			}
			parts, b = append(parts, fmt.Sprintf("%d=f64", num)), b[k:]
		default:
			return fmt.Sprintf("b(%d)", len(x))
		}
	}
	return "{" + strings.Join(parts, " ") + "}"
}

// MessageEncryption reads Google's per-message encryption state. libgm's
// MessageStatus has no field for it, but Messages for Web gets it as
// MessageStatus field 8 (unknown to libgm): observed 2 on RCS messages in
// end-to-end-encrypted chats, 1 on some RCS messages, absent on SMS/MMS
// and on RCS messages from before a chat switched to encrypted RCS.
// ok=false: not an RCS/SMS message whose state we can tell.
func MessageEncryption(msg *gmproto.Message) (enc int, ok bool) {
	switch msg.GetType() {
	case 4: // RCS
	case 1: // SMS: never encrypted
		return 0, true
	default:
		return 0, false
	}
	st := msg.GetMessageStatus()
	if st == nil {
		return 0, true
	}
	b := st.ProtoReflect().GetUnknown()
	for len(b) > 0 {
		num, typ, n := protowire.ConsumeTag(b)
		if n < 0 {
			break
		}
		b = b[n:]
		if num == 8 && typ == protowire.VarintType {
			v, k := protowire.ConsumeVarint(b)
			if k < 0 {
				break
			}
			return int(v), true
		}
		k := protowire.ConsumeFieldValue(num, typ, b)
		if k < 0 {
			break
		}
		b = b[k:]
	}
	return 0, true
}

// RecordMessageEncryption stores MessageEncryption for msg.
func RecordMessageEncryption(store *db.Store, msg *gmproto.Message) {
	if store == nil || msg == nil {
		return
	}
	if enc, ok := MessageEncryption(msg); ok {
		_ = store.SetMessageEncryption(msg.GetMessageID(), msg.GetConversationID(), msg.GetTimestamp()/1000, enc)
	}
}

// RecordMessageStatusText stores Google's status text for an outgoing
// message (in RCS groups: "Read by Alice, Bob"); see db.SetMessageStatusText.
func RecordMessageStatusText(store *db.Store, msg *gmproto.Message) {
	if store == nil || msg == nil || msg.GetMessageStatus() == nil {
		return
	}
	st := msg.GetMessageStatus().GetStatus()
	if st < gmproto.MessageStatusType_OUTGOING_COMPLETE || st >= gmproto.MessageStatusType_INCOMING_COMPLETE {
		return
	}
	_ = store.SetMessageStatusText(msg.GetMessageID(), msg.GetConversationID(), msg.GetMessageStatus().GetStatusText())
}
