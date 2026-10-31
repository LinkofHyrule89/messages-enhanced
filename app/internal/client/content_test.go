package client

import (
	"testing"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

func TestInferMime(t *testing.T) {
	cases := []struct {
		given string
		f     gmproto.MediaFormats
		name  string
		want  string
	}{
		{"", gmproto.MediaFormats_TEXT_VCARD, "", "text/vcard"},
		{"application/octet-stream", 0, "Card.VCF", "text/vcard"},
		{"", 0, "invite.ics", "text/calendar"},
		{"text/x-vcard; charset=utf-8", 0, "", "text/vcard"},
		{"", gmproto.MediaFormats_AUDIO_AMR, "", "audio/amr"},
		{"", gmproto.MediaFormats_IMAGE_PNG, "", "image/png"},
		{"", 0, "", "application/octet-stream"},
		{"audio/ogg", 0, "x.vcf", "audio/ogg"},
	}
	for _, c := range cases {
		if got := InferMime(c.given, c.f, c.name); got != c.want {
			t.Errorf("InferMime(%q,%v,%q)=%q want %q", c.given, c.f, c.name, got, c.want)
		}
	}
}

func TestPushLocationBody(t *testing.T) {
	xml := []byte(`<?xml version="1.0"?><rcsenvelope><rcspushlocation label="Example &amp; Park"><geopriv><location-info><Circle><gml:pos>10.5 -20.25</gml:pos></Circle></location-info></geopriv></rcspushlocation></rcsenvelope>`)
	want := "Example & Park\nhttps://maps.google.com/?q=10.5,-20.25"
	if got := PushLocationBody(xml); got != want {
		t.Fatalf("got %q", got)
	}
	if PushLocationBody([]byte("<x/>")) != "" {
		t.Fatal("expected empty")
	}
}
