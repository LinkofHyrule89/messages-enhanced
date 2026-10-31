package client

import (
	"fmt"
	"mime"
	"path/filepath"
	"regexp"
	"strings"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// formatMimes maps Google's MediaFormats to a MIME type for media that
// arrives without one, so the apps can pick the right card (contact,
// audio, file, ...) instead of a bare "attachment".
var formatMimes = map[gmproto.MediaFormats]string{
	gmproto.MediaFormats_IMAGE_JPEG: "image/jpeg", gmproto.MediaFormats_IMAGE_JPG: "image/jpeg",
	gmproto.MediaFormats_IMAGE_PNG: "image/png", gmproto.MediaFormats_IMAGE_GIF: "image/gif",
	gmproto.MediaFormats_IMAGE_WBMP: "image/vnd.wap.wbmp", gmproto.MediaFormats_IMAGE_X_MS_BMP: "image/bmp",
	gmproto.MediaFormats_IMAGE_UNSPECIFIED: "image/jpeg",
	gmproto.MediaFormats_VIDEO_MP4: "video/mp4", gmproto.MediaFormats_VIDEO_3G2: "video/3gpp2",
	gmproto.MediaFormats_VIDEO_3GPP: "video/3gpp", gmproto.MediaFormats_VIDEO_WEBM: "video/webm",
	gmproto.MediaFormats_VIDEO_MKV: "video/x-matroska", gmproto.MediaFormats_VIDEO_UNSPECIFIED: "video/mp4",
	gmproto.MediaFormats_AUDIO_AAC: "audio/aac", gmproto.MediaFormats_AUDIO_AMR: "audio/amr",
	gmproto.MediaFormats_AUDIO_MP3: "audio/mpeg", gmproto.MediaFormats_AUDIO_MPEG: "audio/mpeg",
	gmproto.MediaFormats_AUDIO_MPG: "audio/mpeg", gmproto.MediaFormats_AUDIO_MP4: "audio/mp4",
	gmproto.MediaFormats_AUDIO_MP4_LATM: "audio/mp4", gmproto.MediaFormats_AUDIO_3GPP: "audio/3gpp",
	gmproto.MediaFormats_AUDIO_OGG: "audio/ogg", gmproto.MediaFormats_AUDIO_UNSPECIFIED: "audio/mpeg",
	gmproto.MediaFormats_TEXT_VCARD: "text/vcard",
	gmproto.MediaFormats_APP_PDF: "application/pdf", gmproto.MediaFormats_APP_TXT: "text/plain",
	gmproto.MediaFormats_APP_HTML: "text/html", gmproto.MediaFormats_APP_DOC: "application/msword",
	gmproto.MediaFormats_APP_DOCX: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
	gmproto.MediaFormats_APP_PPTX: "application/vnd.openxmlformats-officedocument.presentationml.presentation",
	gmproto.MediaFormats_APP_PPT: "application/vnd.ms-powerpoint",
	gmproto.MediaFormats_APP_XLSX: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
	gmproto.MediaFormats_APP_XLS: "application/vnd.ms-excel",
	gmproto.MediaFormats_APP_APK: "application/vnd.android.package-archive",
	gmproto.MediaFormats_APP_ZIP: "application/zip",
}

var extMimes = map[string]string{
	".vcf": "text/vcard", ".vcard": "text/vcard", ".ics": "text/calendar", ".ical": "text/calendar",
	".webp": "image/webp", ".m4a": "audio/mp4", ".amr": "audio/amr", ".opus": "audio/ogg",
}

// InferMime picks the best MIME type from Google's type, the format enum and
// the file name. Generic types are refined by the extension (a .vcf sent as
// application/octet-stream is a contact).
func InferMime(given string, format gmproto.MediaFormats, name string) string {
	given = strings.ToLower(strings.TrimSpace(given))
	if i := strings.IndexByte(given, ';'); i >= 0 {
		given = strings.TrimSpace(given[:i])
	}
	if given == "text/x-vcard" || given == "text/directory" {
		return "text/vcard"
	}
	if given != "" && given != "application/octet-stream" {
		return given
	}
	ext := strings.ToLower(filepath.Ext(name))
	if m, ok := extMimes[ext]; ok {
		return m
	}
	if ext != "" {
		if m := mime.TypeByExtension(ext); m != "" {
			if i := strings.IndexByte(m, ';'); i >= 0 {
				m = m[:i]
			}
			return m
		}
	}
	if m, ok := formatMimes[format]; ok {
		return m
	}
	return "application/octet-stream"
}

var (
	pushPosRE   = regexp.MustCompile(`<(?:[A-Za-z0-9]+:)?pos[^>]*>\s*(-?\d+(?:\.\d+)?)[\s,]+(-?\d+(?:\.\d+)?)\s*</`)
	pushLabelRE = regexp.MustCompile(`(?s)\blabel="([^"]*)"|<(?:[A-Za-z0-9]+:)?(?:note|label)[^>]*>(.*?)</`)
)

// PushLocationBody turns an RCS location push document
// (application/vnd.gsma.rcspushlocation+xml, the older location encoding)
// into "label\nhttps://maps.google.com/?q=lat,lon". Empty when it has no
// position.
func PushLocationBody(xml []byte) string {
	m := pushPosRE.FindSubmatch(xml)
	if m == nil {
		return ""
	}
	link := fmt.Sprintf("https://maps.google.com/?q=%s,%s", m[1], m[2])
	label := ""
	if l := pushLabelRE.FindSubmatch(xml); l != nil {
		label = strings.TrimSpace(string(l[1]) + string(l[2]))
		label = strings.NewReplacer("&amp;", "&", "&lt;", "<", "&gt;", ">", "&quot;", `"`, "&apos;", "'").Replace(label)
	}
	if label == "" {
		return link
	}
	return label + "\n" + link
}

// IsPushLocationMime reports the RCS location push MIME type.
func IsPushLocationMime(m string) bool { return strings.Contains(strings.ToLower(m), "rcspushlocation") }
