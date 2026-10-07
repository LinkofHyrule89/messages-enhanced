package app

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"syscall"
	"time"
	"unicode"
)

// Image answers for @Grok ("show me a picture of …"): Grok finds a real
// image through web search (never generated) and names it on a trailing
// "IMAGE: <url>" line, which is stripped from the text. The server then
// downloads it with SSRF protection, checks it's really a JPEG/PNG/GIF/WebP
// (Content-Type and magic bytes), caps size and time, and sends at most one
// image per reply after the text.
const (
	grokImageMaxBytes  = 5 << 20
	grokImageTimeout   = 15 * time.Second
	grokImageRedirects = 3
	grokImageUA        = "messages-enhanced/1.0 (personal SMS assistant; https://github.com/maxghenis/openmessage)"
)

// grokImageDomains limits picture searches (xAI allows at most five).
var grokImageDomains = []string{"commons.wikimedia.org", "upload.wikimedia.org", "en.wikipedia.org"}

var errGrokImageTooLarge = errors.New("image too large")

// Wikimedia Commons file URLs as the model gives them: an upload URL
// (original or thumb) or a File: page. Commons stores a file under
// /a/ab/ where "ab" is the start of the MD5 of its name; the model tends to
// get the name right but guess those folders, so they're recomputed here.
var (
	grokCommonsUploadRe = regexp.MustCompile(`^https://upload\.wikimedia\.org/wikipedia/commons/(?:thumb/)?[0-9a-f]/[0-9a-f]{2}/([^/?#]+)(?:/[^/?#]*)?$`)
	grokCommonsFileRe   = regexp.MustCompile(`^https://(?:commons|en)\.(?:m\.)?wiki[mp]edia\.org/wiki/(?:File|Image):([^?#]+)$`)
)

// grokCommonsName returns the canonical Commons file name in a URL, if any.
func grokCommonsName(raw string) string {
	m := grokCommonsUploadRe.FindStringSubmatch(raw)
	if m == nil {
		m = grokCommonsFileRe.FindStringSubmatch(raw)
	}
	if m == nil {
		return ""
	}
	name, err := url.PathUnescape(m[1])
	if err != nil || name == "" || strings.ContainsAny(name, "/\\") {
		return ""
	}
	name = strings.ReplaceAll(strings.TrimSpace(name), " ", "_")
	r := []rune(name)
	r[0] = unicode.ToUpper(r[0]) // MediaWiki capitalizes the first letter
	return string(r)
}

// grokImageCandidates: URLs to try in order. A Commons file becomes its
// canonical 1280px thumbnail (~100-300 KB, right for MMS) and then the
// original; anything else is tried as is.
func grokImageCandidates(raw string) []string {
	name := grokCommonsName(raw)
	if name == "" {
		return []string{raw}
	}
	sum := md5.Sum([]byte(name))
	h := hex.EncodeToString(sum[:])
	esc := url.PathEscape(name)
	base := "https://upload.wikimedia.org/wikipedia/commons/"
	orig := base + h[:1] + "/" + h[:2] + "/" + esc
	lower := strings.ToLower(name)
	if strings.HasSuffix(lower, ".svg") || strings.HasSuffix(lower, ".tif") || strings.HasSuffix(lower, ".tiff") || strings.HasSuffix(lower, ".pdf") {
		return []string{orig} // no usable raster thumb by this URL; type check decides
	}
	return []string{base + "thumb/" + h[:1] + "/" + h[:2] + "/" + esc + "/1280px-" + esc, orig}
}

var (
	grokWantsImageRe = regexp.MustCompile(`(?i)\b(pictures?|photos?|pics?|images?|selfies?|wallpapers?|show me|what does .{1,60} look like)\b`)
	grokImageLineRe  = regexp.MustCompile(`(?mi)^[ \t]*IMAGE:[ \t]*<?(\S+?)>?[ \t]*$`)
	grokMDImageURLRe = regexp.MustCompile(`!\[[^\]]*\]\((https?://[^)\s]+)\)`)
)

// grokWantsImage: does the triggering message ask for a picture?
func grokWantsImage(body string) bool { return grokWantsImageRe.MatchString(body) }

// grokExtractImage removes "IMAGE: <url>" lines (and markdown image embeds)
// from Grok's text and returns the first http(s) image URL found.
func grokExtractImage(text string) (string, string) {
	img := ""
	for _, m := range grokImageLineRe.FindAllStringSubmatch(text, -1) {
		if img == "" && grokImageURLAllowed(m[1]) == nil {
			img = m[1]
		}
	}
	text = grokImageLineRe.ReplaceAllString(text, "")
	if img == "" {
		for _, m := range grokMDImageURLRe.FindAllStringSubmatch(text, -1) {
			if grokImageURLAllowed(m[1]) == nil {
				img = m[1]
				break
			}
		}
	}
	return strings.TrimSpace(text), img
}

// grokImageURLAllowed: absolute http(s) URL, a host name, default ports,
// no credentials.
func grokImageURLAllowed(raw string) error {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return err
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return errors.New("not an http(s) URL")
	}
	if u.User != nil || u.Hostname() == "" {
		return errors.New("bad host")
	}
	if p := u.Port(); p != "" && p != "80" && p != "443" {
		return errors.New("non-default port")
	}
	if ip := net.ParseIP(u.Hostname()); ip != nil && !grokPublicIP(ip) {
		return errors.New("private address")
	}
	if h := strings.ToLower(u.Hostname()); h == "localhost" || strings.HasSuffix(h, ".localhost") || strings.HasSuffix(h, ".internal") || strings.HasSuffix(h, ".local") || strings.HasSuffix(h, ".ts.net") {
		return errors.New("private host")
	}
	return nil
}

var grokCGNAT = &net.IPNet{IP: net.IPv4(100, 64, 0, 0), Mask: net.CIDRMask(10, 32)}

// grokPublicIP rejects loopback, private, link-local, CGNAT (Tailscale),
// multicast and unspecified addresses.
func grokPublicIP(ip net.IP) bool {
	if ip4 := ip.To4(); ip4 != nil {
		ip = ip4
	}
	return !(ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() ||
		ip.IsInterfaceLocalMulticast() || ip.IsMulticast() || ip.IsUnspecified() || grokCGNAT.Contains(ip) ||
		ip.Equal(net.IPv4bcast))
}

// grokImageAllowPrivate is for tests (httptest serves on loopback).
var grokImageAllowPrivate = false

// grokImageClient checks the address actually dialed (after DNS, so a
// rebinding name can't reach an internal service), re-checks every
// redirect, and ignores proxy environment variables.
func grokImageClient() *http.Client {
	dialer := &net.Dialer{Timeout: 8 * time.Second, Control: func(network, address string, _ syscall.RawConn) error {
		host, _, err := net.SplitHostPort(address)
		if err != nil {
			return err
		}
		ip := net.ParseIP(host)
		if ip == nil || (!grokImageAllowPrivate && !grokPublicIP(ip)) {
			return fmt.Errorf("blocked address %s", host)
		}
		return nil
	}}
	return &http.Client{
		Timeout: grokImageTimeout,
		Transport: &http.Transport{
			Proxy:                 nil,
			DialContext:           dialer.DialContext,
			TLSHandshakeTimeout:   8 * time.Second,
			ResponseHeaderTimeout: 10 * time.Second,
			MaxIdleConns:          2,
			IdleConnTimeout:       30 * time.Second,
		},
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= grokImageRedirects {
				return errors.New("too many redirects")
			}
			if !grokImageAllowPrivate {
				return grokImageURLAllowed(req.URL.String())
			}
			return nil
		},
	}
}

// grokImageType identifies JPEG/PNG/GIF/WebP by magic bytes.
func grokImageType(b []byte) (mime, ext string) {
	switch {
	case len(b) >= 3 && b[0] == 0xFF && b[1] == 0xD8 && b[2] == 0xFF:
		return "image/jpeg", "jpg"
	case len(b) >= 8 && bytes.Equal(b[:8], []byte{0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}):
		return "image/png", "png"
	case len(b) >= 6 && (string(b[:6]) == "GIF87a" || string(b[:6]) == "GIF89a"):
		return "image/gif", "gif"
	case len(b) >= 12 && string(b[:4]) == "RIFF" && string(b[8:12]) == "WEBP":
		return "image/webp", "webp"
	}
	return "", ""
}

// fetchGrokImage downloads and validates one image (trying a Commons
// thumbnail first, see grokImageCandidates).
func fetchGrokImage(ctx context.Context, raw string) (data []byte, mime, filename string, err error) {
	ctx, cancel := context.WithTimeout(ctx, 2*grokImageTimeout) // all attempts
	defer cancel()
	for _, u := range grokImageCandidates(raw) {
		data, mime, filename, err = fetchGrokImageOnce(ctx, u)
		if err == nil {
			return data, mime, filename, nil
		}
	}
	return nil, "", "", err
}

func fetchGrokImageOnce(ctx context.Context, raw string) (data []byte, mime, filename string, err error) {
	if !grokImageAllowPrivate {
		if err := grokImageURLAllowed(raw); err != nil {
			return nil, "", "", err
		}
	}
	ctx, cancel := context.WithTimeout(ctx, grokImageTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, raw, nil)
	if err != nil {
		return nil, "", "", err
	}
	req.Header.Set("User-Agent", grokImageUA) // Wikimedia rate-limits generic browser UAs
	req.Header.Set("Accept", "image/jpeg,image/png,image/gif,image/webp;q=0.9,*/*;q=0.1")
	resp, err := grokImageClient().Do(req)
	if err != nil {
		return nil, "", "", fmt.Errorf("download: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, "", "", fmt.Errorf("download: HTTP %d", resp.StatusCode)
	}
	ct := strings.ToLower(resp.Header.Get("Content-Type"))
	if !strings.HasPrefix(ct, "image/") {
		return nil, "", "", fmt.Errorf("not an image (Content-Type %q)", ct)
	}
	if resp.ContentLength > grokImageMaxBytes {
		return nil, "", "", errGrokImageTooLarge
	}
	data, err = io.ReadAll(io.LimitReader(resp.Body, grokImageMaxBytes+1))
	if err != nil {
		return nil, "", "", fmt.Errorf("download: %w", err)
	}
	if len(data) > grokImageMaxBytes {
		return nil, "", "", errGrokImageTooLarge
	}
	mime, ext := grokImageType(data)
	if mime == "" {
		return nil, "", "", errors.New("not a JPEG, PNG, GIF or WebP image")
	}
	return data, mime, "grok-image." + ext, nil
}
