package web

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"net/url"
	"path"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/rs/zerolog"
	"golang.org/x/net/html"
)

var (
	ErrInvalidLinkPreviewURL = errors.New("invalid link preview url")
	ErrBlockedLinkPreviewURL = errors.New("link preview url is blocked")
	ErrNoLinkPreview         = errors.New("no link preview available")
)

const maxLinkPreviewImageBytes = 5 << 20

type LinkPreview struct {
	URL         string `json:"url,omitempty"`
	Title       string `json:"title,omitempty"`
	Description string `json:"description,omitempty"`
	ImageURL    string `json:"image_url,omitempty"`
	SiteName    string `json:"site_name,omitempty"`
	IconURL     string `json:"icon_url,omitempty"`
	Domain      string `json:"domain,omitempty"`
	IsVideo     bool   `json:"is_video,omitempty"`
	ImageWidth  int    `json:"image_width,omitempty"`
	ImageHeight int    `json:"image_height,omitempty"`
}

type LinkPreviewFetcher func(ctx context.Context, rawURL string) (*LinkPreview, error)
type LinkPreviewImageFetcher func(ctx context.Context, rawURL string) ([]byte, string, error)

type linkPreviewCacheEntry struct {
	preview   *LinkPreview
	expiresAt time.Time
	touchedAt time.Time
}

type LinkPreviewService struct {
	logger            zerolog.Logger
	client            *http.Client
	ttl               time.Duration
	maxEntries        int
	allowPrivateHosts bool

	mu    sync.Mutex
	cache map[string]linkPreviewCacheEntry
}

func NewLinkPreviewService(logger zerolog.Logger) *LinkPreviewService {
	s := &LinkPreviewService{
		logger:     logger,
		ttl:        6 * time.Hour,
		maxEntries: 256,
		cache:      make(map[string]linkPreviewCacheEntry),
	}
	s.client = &http.Client{
		Timeout: 6 * time.Second,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 5 {
				return errors.New("too many redirects")
			}
			return nil
		},
		Transport: &http.Transport{
			// Validate AND connect to the same resolved IP, closing the
			// resolve-then-fetch TOCTOU (DNS-rebinding) gap where a hostname
			// could resolve to a public IP for the guard check and a private
			// IP for the actual connection. This covers the initial request
			// and every redirect in one place.
			DialContext:           s.guardedDialContext,
			ForceAttemptHTTP2:     true,
			MaxIdleConns:          10,
			IdleConnTimeout:       30 * time.Second,
			TLSHandshakeTimeout:   5 * time.Second,
			ExpectContinueTimeout: time.Second,
		},
	}
	return s
}

// guardedDialContext resolves the target host, rejects it if any resolved
// address is private/loopback (unless allowPrivateHosts), and dials the exact
// IP it validated — so there is no second, unchecked DNS resolution. TLS still
// verifies against the original hostname (the transport sets ServerName from
// the request URL, not the dialed address).
func (s *LinkPreviewService) guardedDialContext(ctx context.Context, network, addr string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, err
	}
	ips, err := net.DefaultResolver.LookupIPAddr(ctx, host)
	if err != nil {
		return nil, fmt.Errorf("resolve preview host: %w", err)
	}
	if !s.allowPrivateHosts {
		for _, ip := range ips {
			if isPrivatePreviewIP(ip.IP) {
				return nil, ErrBlockedLinkPreviewURL
			}
		}
	}
	dialer := &net.Dialer{Timeout: 5 * time.Second}
	var lastErr error
	for _, ip := range ips {
		conn, err := dialer.DialContext(ctx, network, net.JoinHostPort(ip.IP.String(), port))
		if err == nil {
			return conn, nil
		}
		lastErr = err
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("no addresses for preview host %q", host)
	}
	return nil, lastErr
}

func (s *LinkPreviewService) Fetch(ctx context.Context, rawURL string) (*LinkPreview, error) {
	normalizedURL, parsedURL, err := normalizeLinkPreviewURL(rawURL)
	if err != nil {
		return nil, err
	}
	if !s.allowPrivateHosts {
		if err := ensurePublicPreviewHost(ctx, parsedURL); err != nil {
			return nil, err
		}
	}

	if preview := s.cached(normalizedURL); preview != nil {
		return preview, nil
	}

	if id := youTubeVideoID(parsedURL); id != "" {
		preview := s.fetchYouTube(ctx, normalizedURL, id)
		s.store(normalizedURL, preview)
		return cloneLinkPreview(preview), nil
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, normalizedURL, nil)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrInvalidLinkPreviewURL, err)
	}
	req.Header.Set("User-Agent", "OpenMessage/1.0 (+https://openmessage.ai)")
	req.Header.Set("Accept", "text/html,application/xhtml+xml")

	resp, err := s.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("fetch link preview: %w", err)
	}
	defer resp.Body.Close()

	if resp.Request != nil && resp.Request.URL != nil && !s.allowPrivateHosts {
		if err := ensurePublicPreviewHost(ctx, resp.Request.URL); err != nil {
			return nil, err
		}
	}

	if resp.StatusCode < http.StatusOK || resp.StatusCode >= http.StatusBadRequest {
		return nil, fmt.Errorf("fetch link preview: unexpected status %d", resp.StatusCode)
	}

	contentType := strings.ToLower(resp.Header.Get("Content-Type"))
	if contentType != "" && !strings.Contains(contentType, "text/html") && !strings.Contains(contentType, "application/xhtml+xml") {
		return nil, ErrNoLinkPreview
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, 3<<20))
	if err != nil {
		return nil, fmt.Errorf("read link preview: %w", err)
	}

	finalURL := parsedURL
	if resp.Request != nil && resp.Request.URL != nil {
		finalURL = resp.Request.URL
	}

	preview, err := extractLinkPreview(body, finalURL)
	if err != nil {
		return nil, err
	}
	preview.URL = finalURL.String()
	preview.Domain = finalURL.Hostname()
	if preview.SiteName == "" {
		preview.SiteName = prettifyPreviewHost(finalURL.Hostname())
	}

	s.store(normalizedURL, preview)
	return cloneLinkPreview(preview), nil
}

func (s *LinkPreviewService) FetchImage(ctx context.Context, rawURL string) ([]byte, string, error) {
	normalizedURL, parsedURL, err := normalizeLinkPreviewURL(rawURL)
	if err != nil {
		return nil, "", err
	}
	if !s.allowPrivateHosts {
		if err := ensurePublicPreviewHost(ctx, parsedURL); err != nil {
			return nil, "", err
		}
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, normalizedURL, nil)
	if err != nil {
		return nil, "", fmt.Errorf("%w: %v", ErrInvalidLinkPreviewURL, err)
	}
	req.Header.Set("User-Agent", "OpenMessage/1.0 (+https://openmessage.ai)")
	req.Header.Set("Accept", "image/avif,image/webp,image/apng,image/png,image/jpeg,image/gif,*/*;q=0.8")

	resp, err := s.client.Do(req)
	if err != nil {
		return nil, "", fmt.Errorf("fetch link preview image: %w", err)
	}
	defer resp.Body.Close()

	if resp.Request != nil && resp.Request.URL != nil && !s.allowPrivateHosts {
		if err := ensurePublicPreviewHost(ctx, resp.Request.URL); err != nil {
			return nil, "", err
		}
	}
	if resp.StatusCode < http.StatusOK || resp.StatusCode >= http.StatusBadRequest {
		return nil, "", fmt.Errorf("fetch link preview image: unexpected status %d", resp.StatusCode)
	}

	contentType, _, err := mime.ParseMediaType(resp.Header.Get("Content-Type"))
	if err != nil || strings.TrimSpace(contentType) == "" {
		contentType = http.DetectContentType(nil)
	}
	contentType = strings.ToLower(strings.TrimSpace(contentType))
	if !isAllowedLinkPreviewImageType(contentType) {
		return nil, "", ErrNoLinkPreview
	}
	limited := io.LimitReader(resp.Body, maxLinkPreviewImageBytes+1)
	data, err := io.ReadAll(limited)
	if err != nil {
		return nil, "", fmt.Errorf("read link preview image: %w", err)
	}
	if len(data) > maxLinkPreviewImageBytes {
		return nil, "", fmt.Errorf("link preview image exceeds %d bytes", maxLinkPreviewImageBytes)
	}
	return data, contentType, nil
}

func isAllowedLinkPreviewImageType(contentType string) bool {
	switch strings.ToLower(strings.TrimSpace(contentType)) {
	case "image/avif", "image/webp", "image/apng", "image/png", "image/jpeg", "image/gif", "image/x-icon", "image/vnd.microsoft.icon":
		return true
	default:
		return false
	}
}

func (s *LinkPreviewService) cached(rawURL string) *LinkPreview {
	s.mu.Lock()
	defer s.mu.Unlock()

	entry, ok := s.cache[rawURL]
	if !ok {
		return nil
	}
	if time.Now().After(entry.expiresAt) {
		delete(s.cache, rawURL)
		return nil
	}
	entry.touchedAt = time.Now()
	s.cache[rawURL] = entry
	return cloneLinkPreview(entry.preview)
}

func (s *LinkPreviewService) store(rawURL string, preview *LinkPreview) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	s.pruneExpiredLocked(now)
	s.cache[rawURL] = linkPreviewCacheEntry{
		preview:   cloneLinkPreview(preview),
		expiresAt: now.Add(s.ttl),
		touchedAt: now,
	}
	s.evictIfNeededLocked()
}

func (s *LinkPreviewService) pruneExpiredLocked(now time.Time) {
	for rawURL, entry := range s.cache {
		if now.After(entry.expiresAt) {
			delete(s.cache, rawURL)
		}
	}
}

func (s *LinkPreviewService) evictIfNeededLocked() {
	if s.maxEntries <= 0 {
		return
	}
	for len(s.cache) > s.maxEntries {
		oldestURL := ""
		var oldestTouched time.Time
		for rawURL, entry := range s.cache {
			if oldestURL == "" || entry.touchedAt.Before(oldestTouched) {
				oldestURL = rawURL
				oldestTouched = entry.touchedAt
			}
		}
		if oldestURL == "" {
			return
		}
		delete(s.cache, oldestURL)
	}
}

func cloneLinkPreview(preview *LinkPreview) *LinkPreview {
	if preview == nil {
		return nil
	}
	copy := *preview
	return &copy
}

func normalizeLinkPreviewURL(raw string) (string, *url.URL, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return "", nil, ErrInvalidLinkPreviewURL
	}
	if !strings.Contains(trimmed, "://") {
		trimmed = "https://" + trimmed
	}
	parsed, err := url.Parse(trimmed)
	if err != nil {
		return "", nil, fmt.Errorf("%w: %v", ErrInvalidLinkPreviewURL, err)
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return "", nil, ErrInvalidLinkPreviewURL
	}
	if parsed.Hostname() == "" {
		return "", nil, ErrInvalidLinkPreviewURL
	}
	parsed.Fragment = ""
	return parsed.String(), parsed, nil
}

func ensurePublicPreviewHost(ctx context.Context, target *url.URL) error {
	host := strings.ToLower(target.Hostname())
	if host == "" {
		return ErrInvalidLinkPreviewURL
	}
	if host == "localhost" || strings.HasSuffix(host, ".local") {
		return ErrBlockedLinkPreviewURL
	}

	if ip := net.ParseIP(host); ip != nil {
		if isPrivatePreviewIP(ip) {
			return ErrBlockedLinkPreviewURL
		}
		return nil
	}

	addrs, err := net.DefaultResolver.LookupIPAddr(ctx, host)
	if err != nil {
		return fmt.Errorf("resolve preview host: %w", err)
	}
	for _, addr := range addrs {
		if isPrivatePreviewIP(addr.IP) {
			return ErrBlockedLinkPreviewURL
		}
	}
	return nil
}

func isPrivatePreviewIP(ip net.IP) bool {
	if ip == nil {
		return true
	}
	return ip.IsLoopback() ||
		ip.IsPrivate() ||
		ip.IsLinkLocalUnicast() ||
		ip.IsLinkLocalMulticast() ||
		ip.IsMulticast() ||
		ip.IsInterfaceLocalMulticast() ||
		ip.IsUnspecified()
}

func extractLinkPreview(body []byte, baseURL *url.URL) (*LinkPreview, error) {
	doc, err := html.Parse(strings.NewReader(string(body)))
	if err != nil {
		return nil, fmt.Errorf("parse link preview: %w", err)
	}

	meta := map[string]string{}
	title := ""
	icon := ""
	iconRank := 0
	var walk func(*html.Node)
	walk = func(node *html.Node) {
		if node.Type == html.ElementNode {
			switch strings.ToLower(node.Data) {
			case "title":
				if title == "" {
					title = collapsePreviewWhitespace(textContent(node))
				}
			case "link":
				rel, href := "", ""
				for _, attr := range node.Attr {
					switch strings.ToLower(attr.Key) {
					case "rel":
						rel = strings.ToLower(strings.TrimSpace(attr.Val))
					case "href":
						href = strings.TrimSpace(attr.Val)
					}
				}
				rank := 0
				switch {
				case rel == "apple-touch-icon" || rel == "apple-touch-icon-precomposed":
					rank = 3
				case rel == "icon" || rel == "shortcut icon":
					rank = 2
				case strings.Contains(rel, "icon") && !strings.Contains(rel, "mask"):
					rank = 1
				}
				if href != "" && rank > iconRank && !strings.HasSuffix(strings.ToLower(strings.SplitN(href, "?", 2)[0]), ".svg") {
					icon, iconRank = href, rank
				}
			case "meta":
				key := ""
				content := ""
				for _, attr := range node.Attr {
					switch strings.ToLower(attr.Key) {
					case "property", "name", "itemprop":
						if key == "" {
							key = strings.ToLower(strings.TrimSpace(attr.Val))
						}
					case "content":
						content = collapsePreviewWhitespace(attr.Val)
					}
				}
				if key != "" && content != "" {
					if _, exists := meta[key]; !exists {
						meta[key] = content
					}
				}
			}
		}
		for child := node.FirstChild; child != nil; child = child.NextSibling {
			walk(child)
		}
	}
	walk(doc)

	preview := &LinkPreview{
		Title:       firstNonEmpty(meta["og:title"], meta["twitter:title"], meta["title"], title),
		Description: firstNonEmpty(meta["og:description"], meta["twitter:description"], meta["description"]),
		ImageURL:    resolvePreviewURL(baseURL, firstNonEmpty(meta["og:image"], meta["twitter:image"], meta["twitter:image:src"], meta["image"])),
		SiteName:    firstNonEmpty(meta["og:site_name"], meta["application-name"]),
		IconURL:     resolvePreviewURL(baseURL, icon),
		IsVideo:     firstNonEmpty(meta["og:video"], meta["og:video:url"], meta["og:video:secure_url"], meta["twitter:player"]) != "" || strings.Contains(firstNonEmpty(meta["og:image"], meta["twitter:image"]), "video_thumb"),
		ImageWidth:  atoiSafe(meta["og:image:width"]),
		ImageHeight: atoiSafe(meta["og:image:height"]),
	}
	if preview.IconURL == "" && baseURL != nil && (baseURL.Scheme == "http" || baseURL.Scheme == "https") && baseURL.Host != "" {
		preview.IconURL = baseURL.Scheme + "://" + baseURL.Host + "/favicon.ico"
	}

	preview.Title = truncatePreviewText(preview.Title, 180)
	preview.Description = truncatePreviewText(preview.Description, 320)
	if preview.Title == "" && preview.Description == "" && preview.ImageURL == "" && preview.SiteName == "" {
		return nil, ErrNoLinkPreview
	}
	return preview, nil
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if trimmed := strings.TrimSpace(value); trimmed != "" {
			return trimmed
		}
	}
	return ""
}

func collapsePreviewWhitespace(value string) string {
	return strings.Join(strings.Fields(strings.TrimSpace(value)), " ")
}

func truncatePreviewText(value string, limit int) string {
	if limit <= 0 || len(value) <= limit {
		return value
	}
	if limit <= 1 {
		return value[:limit]
	}
	return strings.TrimSpace(value[:limit-1]) + "…"
}

func resolvePreviewURL(baseURL *url.URL, raw string) string {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return ""
	}
	if strings.HasPrefix(trimmed, "//") {
		if baseURL == nil || baseURL.Scheme == "" {
			return "https:" + trimmed
		}
		return baseURL.Scheme + ":" + trimmed
	}
	parsed, err := url.Parse(trimmed)
	if err != nil {
		return ""
	}
	if parsed.IsAbs() {
		// Only http(s) image URLs are allowed through. og:image is
		// attacker-controlled (it comes from the linked page's meta tags), so
		// reject data:/javascript:/file:/etc. before it reaches an <img src>.
		if parsed.Scheme != "http" && parsed.Scheme != "https" {
			return ""
		}
		return parsed.String()
	}
	if baseURL == nil {
		return ""
	}
	return baseURL.ResolveReference(parsed).String()
}

func textContent(node *html.Node) string {
	if node == nil {
		return ""
	}
	var builder strings.Builder
	var walk func(*html.Node)
	walk = func(current *html.Node) {
		if current.Type == html.TextNode {
			builder.WriteString(current.Data)
		}
		for child := current.FirstChild; child != nil; child = child.NextSibling {
			walk(child)
		}
	}
	walk(node)
	return builder.String()
}

func prettifyPreviewHost(host string) string {
	trimmed := strings.ToLower(strings.TrimSpace(host))
	trimmed = strings.TrimPrefix(trimmed, "www.")
	trimmed = strings.TrimSpace(trimmed)
	trimmed = strings.TrimSuffix(trimmed, path.Ext(trimmed))
	if trimmed == "" {
		return host
	}
	parts := strings.Split(trimmed, ".")
	if len(parts) == 0 {
		return host
	}
	label := strings.ReplaceAll(parts[0], "-", " ")
	if label == "" {
		return host
	}
	return strings.ToUpper(label[:1]) + label[1:]
}

func atoiSafe(v string) int {
	n, err := strconv.Atoi(strings.TrimSpace(v))
	if err != nil || n < 0 {
		return 0
	}
	return n
}

var youTubeIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{11}$`)

// youTubeVideoID returns the 11-char video id for watch, youtu.be, shorts,
// live and embed links, or "" if the URL is not a YouTube video.
func youTubeVideoID(u *url.URL) string {
	if u == nil {
		return ""
	}
	host := strings.TrimPrefix(strings.ToLower(u.Hostname()), "www.")
	host = strings.TrimPrefix(host, "m.")
	var id string
	switch host {
	case "youtu.be":
		id = strings.Trim(u.Path, "/")
	case "youtube.com", "music.youtube.com", "youtube-nocookie.com":
		if u.Path == "/watch" {
			id = u.Query().Get("v")
		} else {
			parts := strings.Split(strings.Trim(u.Path, "/"), "/")
			if len(parts) >= 2 && (parts[0] == "shorts" || parts[0] == "live" || parts[0] == "embed" || parts[0] == "v") {
				id = parts[1]
			}
		}
	}
	if youTubeIDPattern.MatchString(id) {
		return id
	}
	return ""
}

// fetchYouTube builds a preview from YouTube's oEmbed endpoint, which is not
// subject to the consent/bot interstitials or huge watch-page HTML. It always
// returns a usable preview (thumbnail from i.ytimg.com) even if oEmbed fails.
func (s *LinkPreviewService) fetchYouTube(ctx context.Context, link, id string) *LinkPreview {
	preview := &LinkPreview{
		URL:         link,
		Title:       "YouTube",
		ImageURL:    "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg",
		SiteName:    "YouTube",
		IconURL:     "https://www.youtube.com/favicon.ico",
		Domain:      "youtube.com",
		IsVideo:     true,
		ImageWidth:  480,
		ImageHeight: 360,
	}
	q := url.Values{"url": {"https://www.youtube.com/watch?v=" + id}, "format": {"json"}}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "https://www.youtube.com/oembed?"+q.Encode(), nil)
	if err != nil {
		return preview
	}
	req.Header.Set("User-Agent", "OpenMessage/1.0 (+https://openmessage.ai)")
	resp, err := s.client.Do(req)
	if err != nil {
		return preview
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return preview
	}
	var o struct {
		Title        string `json:"title"`
		AuthorName   string `json:"author_name"`
		ThumbnailURL string `json:"thumbnail_url"`
		ThumbW       int    `json:"thumbnail_width"`
		ThumbH       int    `json:"thumbnail_height"`
	}
	if json.NewDecoder(io.LimitReader(resp.Body, 64<<10)).Decode(&o) != nil {
		return preview
	}
	if o.Title != "" {
		preview.Title = truncatePreviewText(o.Title, 180)
	}
	preview.Description = truncatePreviewText(o.AuthorName, 320)
	if strings.HasPrefix(o.ThumbnailURL, "https://i.ytimg.com/") {
		preview.ImageURL = o.ThumbnailURL
		if o.ThumbW > 0 && o.ThumbH > 0 {
			preview.ImageWidth, preview.ImageHeight = o.ThumbW, o.ThumbH
		}
	}
	return preview
}
