package client

import (
	"encoding/json"
	"testing"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

func TestExtractCustomReactions(t *testing.T) {
	uuid := "11111111-2222-4333-8444-555555555555"
	image := func(uri string) *gmproto.CustomEmojiData {
		return &gmproto.CustomEmojiData{Uuid: uuid, InnerData: &gmproto.CustomEmojiData_Inner{
			Second: &gmproto.CustomEmojiData_Inner_WrappedImageData{Data: &gmproto.CustomEmojiData_Inner_WrappedImageData_ImageData{
				MimeType: "image/png", Uri: uri, Width: 512, Height: 512}}}}
	}
	msg := &gmproto.Message{Reactions: []*gmproto.ReactionEntry{
		// What Google sends for an image (Emotify) reaction: the UUID as
		// "unicode" and the picture's path on the phone.
		{Data: &gmproto.ReactionData{Unicode: uuid, Type: gmproto.EmojiType_EMOTIFY,
			CustomEmoji: image("file:///data/user/0/com.google.android.apps.messaging/files/example.png")},
			ParticipantIDs: []string{"p2"}},
		// A plain emoji picked from the full list is typed CUSTOM, without image data.
		{Data: &gmproto.ReactionData{Unicode: "🦜", Type: gmproto.EmojiType_CUSTOM}, ParticipantIDs: []string{"p1"}},
		// No unicode at all: the UUID stands in; an https image is kept.
		{Data: &gmproto.ReactionData{Type: gmproto.EmojiType_CUSTOM, CustomEmoji: image("https://lh3.googleusercontent.com/x.png")}},
	}}
	got := ExtractReactions(msg)
	if len(got) != 3 {
		t.Fatalf("got %d reactions: %+v", len(got), got)
	}
	if c := got[0].Custom; got[0].Emoji != uuid || c == nil || c.UUID != uuid || c.Type != "EMOTIFY" || c.URI != "" || c.Mime != "image/png" || c.Width != 512 {
		t.Fatalf("emotify = %+v custom=%+v", got[0], got[0].Custom)
	}
	if got[1].Emoji != "🦜" || got[1].Custom != nil {
		t.Fatalf("plain custom-typed emoji = %+v", got[1])
	}
	if got[2].Emoji != uuid || got[2].Custom == nil || got[2].Custom.URI != "https://lh3.googleusercontent.com/x.png" {
		t.Fatalf("no-unicode custom = %+v %+v", got[2], got[2].Custom)
	}
	b, _ := json.Marshal(got[0])
	if string(b) != `{"emoji":"`+uuid+`","count":1,"actors":["p2"],"custom":{"uuid":"`+uuid+`","type":"EMOTIFY","mime":"image/png","w":512,"h":512}}` {
		t.Fatalf("json = %s", b)
	}
}
