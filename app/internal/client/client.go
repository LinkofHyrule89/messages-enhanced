package client

import (
	"encoding/json"
	"fmt"
	"net/url"
	"strings"

	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
	"google.golang.org/protobuf/encoding/protowire"
	"google.golang.org/protobuf/proto"
)

type Client struct {
	GM     *libgm.Client
	Logger zerolog.Logger
}

func NewFromSession(sessionData *SessionData, logger zerolog.Logger) (*Client, error) {
	authData := libgm.NewAuthData()
	if err := json.Unmarshal(sessionData.AuthDataJSON, authData); err != nil {
		return nil, fmt.Errorf("unmarshal auth data: %w", err)
	}

	var pushKeys *libgm.PushKeys
	if len(sessionData.PushKeysJSON) > 0 {
		pushKeys = &libgm.PushKeys{}
		if err := json.Unmarshal(sessionData.PushKeysJSON, pushKeys); err != nil {
			return nil, fmt.Errorf("unmarshal push keys: %w", err)
		}
	}

	cli := libgm.NewClient(authData, pushKeys, logger)
	return &Client{GM: cli, Logger: logger}, nil
}

func NewForPairing(logger zerolog.Logger) *Client {
	authData := libgm.NewAuthData()
	cli := libgm.NewClient(authData, nil, logger)
	return &Client{GM: cli, Logger: logger}
}

func (c *Client) SessionData() (*SessionData, error) {
	authJSON, err := json.Marshal(c.GM.AuthData)
	if err != nil {
		return nil, fmt.Errorf("marshal auth data: %w", err)
	}
	var pushJSON json.RawMessage
	if c.GM.PushKeys != nil {
		pushJSON, err = json.Marshal(c.GM.PushKeys)
		if err != nil {
			return nil, fmt.Errorf("marshal push keys: %w", err)
		}
	}
	return &SessionData{
		AuthDataJSON: authJSON,
		PushKeysJSON: pushJSON,
	}, nil
}

// ExtractMessageBody extracts text content from a protobuf Message.
func ExtractMessageBody(msg *gmproto.Message) string {
	for _, info := range msg.GetMessageInfo() {
		if mc := info.GetMessageContent(); mc != nil {
			return mc.GetContent()
		}
	}
	for _, info := range msg.GetMessageInfo() {
		if _, text := locationContent(info); text != nil && text.GetContent() != "" {
			return text.GetContent()
		}
	}
	return ""
}

// locationContent reads a shared location (MessageInfo field 5,
// LocationContent{1: MediaContent map preview, 2: MessageContent text}).
// Our vendored proto predates that field, so it arrives as unknown bytes.
func locationContent(info *gmproto.MessageInfo) (*gmproto.MediaContent, *gmproto.MessageContent) {
	if info == nil {
		return nil, nil
	}
	raw := info.ProtoReflect().GetUnknown()
	for len(raw) > 0 {
		num, typ, n := protowire.ConsumeTag(raw)
		if n < 0 {
			return nil, nil
		}
		raw = raw[n:]
		if num == 5 && typ == protowire.BytesType {
			loc, m := protowire.ConsumeBytes(raw)
			if m < 0 {
				return nil, nil
			}
			return parseLocation(loc)
		}
		m := protowire.ConsumeFieldValue(num, typ, raw)
		if m < 0 {
			return nil, nil
		}
		raw = raw[m:]
	}
	return nil, nil
}

func parseLocation(b []byte) (*gmproto.MediaContent, *gmproto.MessageContent) {
	var media *gmproto.MediaContent
	var text *gmproto.MessageContent
	for len(b) > 0 {
		num, typ, n := protowire.ConsumeTag(b)
		if n < 0 {
			break
		}
		b = b[n:]
		if typ != protowire.BytesType {
			m := protowire.ConsumeFieldValue(num, typ, b)
			if m < 0 {
				break
			}
			b = b[m:]
			continue
		}
		v, m := protowire.ConsumeBytes(b)
		if m < 0 {
			break
		}
		b = b[m:]
		switch num {
		case 1:
			mc := &gmproto.MediaContent{}
			if proto.Unmarshal(v, mc) == nil {
				media = mc
			}
		case 2:
			tc := &gmproto.MessageContent{}
			if proto.Unmarshal(v, tc) == nil {
				text = tc
			}
		}
	}
	return media, text
}

// LocationBody makes sure a shared location's text carries a Maps link the
// clients can render as a location card and open on tap.
func LocationBody(body string) string {
	t := strings.TrimSpace(body)
	if t == "" || HasMapsLink(t) {
		return body
	}
	return t + "\nhttps://maps.google.com/?q=" + url.QueryEscape(t)
}

// HasMapsLink reports a geo: URI or a Google/Apple Maps link in text.
func HasMapsLink(s string) bool {
	l := strings.ToLower(s)
	for _, k := range []string{"geo:", "maps.google.", "google.com/maps", "goo.gl/maps", "maps.app.goo.gl", "maps.apple.com"} {
		if strings.Contains(l, k) {
			return true
		}
	}
	return false
}

// IsLocationMessage reports whether a message is a shared location.
func IsLocationMessage(msg *gmproto.Message) bool {
	for _, info := range msg.GetMessageInfo() {
		if m, t := locationContent(info); m != nil || t != nil {
			return true
		}
	}
	return false
}

// MediaInfo holds extracted media metadata from a protobuf Message.
type MediaInfo struct {
	MediaID                string
	MimeType               string
	MediaName              string
	DecryptionKey          []byte
	Size                   int64
	ThumbnailMediaID       string
	ThumbnailDecryptionKey []byte
	InlineData             []byte // Inline thumbnail bytes from mediaData field
}

// ExtractMediaInfo extracts media content from a protobuf Message.
// Returns nil if the message has no media attachment.
// Falls back to thumbnail or inline data when full-size MediaID is unavailable.
func ExtractMediaInfo(msg *gmproto.Message) *MediaInfo {
	for _, info := range msg.GetMessageInfo() {
		mc := info.GetMediaContent()
		if mc == nil {
			mc, _ = locationContent(info)
		}
		if mc != nil {
			mime := mc.GetMimeType()
			if mime == "" {
				switch {
				case mc.GetFormat() >= 1 && mc.GetFormat() <= 7:
					mime = "image/jpeg"
				default:
					mime = "application/octet-stream"
				}
			}

			mi := &MediaInfo{
				MediaID:                mc.GetMediaID(),
				MimeType:               mime,
				MediaName:              mc.GetMediaName(),
				DecryptionKey:          mc.GetDecryptionKey(),
				Size:                   mc.GetSize(),
				ThumbnailMediaID:       mc.GetThumbnailMediaID(),
				ThumbnailDecryptionKey: mc.GetThumbnailDecryptionKey(),
				InlineData:             mc.GetMediaData(),
			}

			// If no full-size MediaID, fall back to thumbnail
			if mi.MediaID == "" && mi.ThumbnailMediaID != "" {
				mi.MediaID = mi.ThumbnailMediaID
				mi.DecryptionKey = mi.ThumbnailDecryptionKey
			}

			return mi
		}
	}
	return nil
}

// Reaction holds an emoji, how many people reacted with it, and the participant
// IDs of those reactors. Actors are Google Messages participant IDs that resolve
// to names via the conversation's participant list (see SmallInfo.ParticipantID).
// Image reactions (Google Messages EmojiType EMOTIFY/CUSTOM with custom
// emoji data) carry a UUID in the emoji field; Custom then describes the
// image. Google only sends the image's path on the phone
// (a file:// path inside the Messages app)
// and Messages for Web has no call to fetch it, so clients show a generic
// "custom reaction" icon; a downloadable https URL is kept if one ever comes.
type Reaction struct {
	Emoji  string          `json:"emoji"`
	Count  int             `json:"count"`
	Actors []string        `json:"actors,omitempty"`
	Custom *CustomReaction `json:"custom,omitempty"`
}

// CustomReaction is the image behind a custom reaction, as Google sends it.
type CustomReaction struct {
	UUID   string `json:"uuid,omitempty"`
	Type   string `json:"type,omitempty"` // EmojiType name (EMOTIFY, CUSTOM)
	URI    string `json:"uri,omitempty"`  // https only (phone-local paths are dropped)
	Mime   string `json:"mime,omitempty"`
	Width  int    `json:"w,omitempty"`
	Height int    `json:"h,omitempty"`
}

// extractCustomReaction returns the image data of a non-unicode reaction.
func extractCustomReaction(data *gmproto.ReactionData) *CustomReaction {
	ce := data.GetCustomEmoji()
	if ce == nil { // e.g. CUSTOM with a plain unicode emoji picked from the full list
		return nil
	}
	c := &CustomReaction{UUID: ce.GetUuid(), Type: data.GetType().String()}
	if in := ce.GetInnerData(); in != nil {
		if d := in.GetSecond().GetData(); d != nil && d.GetUri() != "" {
			c.URI, c.Mime, c.Width, c.Height = d.GetUri(), d.GetMimeType(), int(d.GetWidth()), int(d.GetHeight())
		} else if f := in.GetFirst(); f != nil && f.GetUri() != "" {
			c.URI, c.Mime = f.GetUri(), f.GetMimeType()
		}
	}
	if !strings.HasPrefix(c.URI, "https://") {
		c.URI = ""
	}
	return c
}

// ExtractReactions extracts reaction data from a protobuf Message.
// Returns nil if there are no reactions.
func ExtractReactions(msg *gmproto.Message) []Reaction {
	entries := msg.GetReactions()
	if len(entries) == 0 {
		return nil
	}
	var reactions []Reaction
	for _, entry := range entries {
		if data := entry.GetData(); data != nil {
			emoji := data.GetUnicode()
			custom := extractCustomReaction(data)
			if emoji == "" && custom != nil {
				emoji = custom.UUID
			}
			if emoji == "" {
				continue
			}
			participantIDs := entry.GetParticipantIDs()
			reactions = append(reactions, Reaction{
				Emoji:  emoji,
				Count:  len(participantIDs),
				Actors: participantIDs,
				Custom: custom,
			})
		}
	}
	if len(reactions) == 0 {
		return nil
	}
	return reactions
}

// ExtractReplyToID extracts the replied-to message ID, if any.
func ExtractReplyToID(msg *gmproto.Message) string {
	if rm := msg.GetReplyMessage(); rm != nil {
		return rm.GetMessageID()
	}
	return ""
}

// ExtractSenderInfo gets the sender name and number from a Message.
func ExtractSenderInfo(msg *gmproto.Message) (name, number string) {
	if p := msg.GetSenderParticipant(); p != nil {
		name = p.GetFullName()
		if name == "" {
			name = p.GetFirstName()
		}
		if id := p.GetID(); id != nil {
			number = id.GetNumber()
		}
		if number == "" {
			number = p.GetFormattedNumber()
		}
	}
	return
}

// MessageIsFromMe infers whether a Google Messages/RCS message was sent by the
// local account. Historical backfill messages sometimes omit sender participant
// metadata even when the message status is clearly outgoing.
func MessageIsFromMe(msg *gmproto.Message) bool {
	if msg == nil {
		return false
	}
	if p := msg.GetSenderParticipant(); p != nil && p.GetIsMe() {
		return true
	}
	if ms := msg.GetMessageStatus(); ms != nil {
		return strings.HasPrefix(ms.GetStatus().String(), "OUTGOING")
	}
	return false
}
