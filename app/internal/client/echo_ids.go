package client

import (
	"strings"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
	"google.golang.org/protobuf/encoding/protowire"
	"google.golang.org/protobuf/reflect/protoreflect"

	"github.com/maxghenis/openmessage/internal/db"
)

// PlaceholderIDs returns every tm-/tmp_ id carried on a Google echo. Group and
// MMS echoes often leave Message.tmpID empty and put the send's temporary id
// on another string field, including fields this proto build does not name.
func PlaceholderIDs(msg *gmproto.Message) []string {
	if msg == nil {
		return nil
	}
	seen := map[string]struct{}{}
	var ids []string
	add := func(id string) {
		id = strings.TrimSpace(id)
		if !db.IsOutgoingPlaceholderID(id) {
			return
		}
		if _, ok := seen[id]; ok {
			return
		}
		seen[id] = struct{}{}
		ids = append(ids, id)
	}
	add(msg.GetTmpID())
	if other := msg.GetAnotherMessageID(); other != nil {
		add(other.GetMessageID())
		add(other.GetAnotherID())
	}
	walkPlaceholderIDs(msg.ProtoReflect(), add)
	return ids
}

func walkPlaceholderIDs(m protoreflect.Message, add func(string)) {
	if !m.IsValid() {
		return
	}
	m.Range(func(fd protoreflect.FieldDescriptor, v protoreflect.Value) bool {
		if fd.IsList() {
			if fd.Kind() == protoreflect.MessageKind {
				list := v.List()
				for i := 0; i < list.Len(); i++ {
					walkPlaceholderIDs(list.Get(i).Message(), add)
				}
			} else if fd.Kind() == protoreflect.StringKind {
				list := v.List()
				for i := 0; i < list.Len(); i++ {
					add(list.Get(i).String())
				}
			}
			return true
		}
		switch fd.Kind() {
		case protoreflect.StringKind:
			add(v.String())
		case protoreflect.MessageKind:
			walkPlaceholderIDs(v.Message(), add)
		}
		return true
	})
	scanUnknownPlaceholderIDs(m.GetUnknown(), add)
}

func scanUnknownPlaceholderIDs(b []byte, add func(string)) {
	for len(b) > 0 {
		num, typ, n := protowire.ConsumeTag(b)
		if n < 0 {
			return
		}
		b = b[n:]
		if typ != protowire.BytesType {
			m := protowire.ConsumeFieldValue(num, typ, b)
			if m < 0 {
				return
			}
			b = b[m:]
			continue
		}
		v, m := protowire.ConsumeBytes(b)
		if m < 0 {
			return
		}
		b = b[m:]
		add(string(v))
		scanUnknownPlaceholderIDs(v, add)
	}
}
