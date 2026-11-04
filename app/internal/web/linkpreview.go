package web

import (
	"bytes"
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
	"golang.org/x/sync/singleflight"
)

var (
	ErrInvalidLinkPreviewURL = errors.New("invalid link preview url")
	ErrBlockedLinkPreviewURL = errors.New("link preview url is blocked")
	ErrNoLinkPreview         = errors.New("no link preview available")
)

const maxLinkPreviewImageBytes = 5 << 20

const (
	// Page HTML is small. Preview images (a few hundred KB, often fetched
	// through a VPN) need their own budget; the old shared 6s client gave up
	// while the download was still in flight.
	linkPreviewPageTimeout  = 10 * time.Second
	linkPreviewImageTimeout = 25 * time.Second
	linkPreviewDialTimeout  = 5 * time.Second
	linkPreviewTLSTimeout   = 5 * time.Second

	// Successful previews and images expire 7 days after the fetch and are
	// not refreshed before that. Failures are kept only briefly so the next
	// view (a scroll back onto the message) fetches again. The byte caps
	// evict earlier when the cache is full.
	linkPreviewSuccessTTL   = 7 * 24 * time.Hour
	linkPreviewFailureTTL   = time.Minute
	linkPreviewCleanupEvery = time.Hour

	linkPreviewImageMemCap  = 64 << 20  // 64 MB
	linkPreviewImageDiskCap = 300 << 20 // 300 MB
	linkPreviewImageDirName = "link-preview-images"
)

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
	err       error
	failed    bool
	expiresAt time.Time
	touchedAt time.Time
}

type LinkPreviewService struct {
	logger            zerolog.Logger
	client            *http.Client
	imageClient       *http.Client
	ttl               time.Duration
	failureTTL        time.Duration
	maxEntries        int
	allowPrivateHosts bool
	now               func() time.Time
	cleanupEvery      time.Duration

	mu    sync.Mutex
	cache map[string]linkPreviewCacheEntry

	images      previewImageCache
	imageFlight singleflight.Group

	maintMu      sync.Mutex
	maintStarted bool
	maintStop    chan struct{}
}

func NewLinkPreviewService(logger zerolog.Logger) *LinkPreviewService {
	s := &LinkPreviewService{
		logger:       logger,
		ttl:          linkPreviewSuccessTTL,
		failureTTL:   linkPreviewFailureTTL,
		maxEntries:   256,
		cleanupEvery: linkPreviewCleanupEvery,
		cache:        make(map[string]linkPreviewCacheEntry),
		images: previewImageCache{
			mem:     make(map[string]*previewImageEntry),
			disk:    make(map[string]diskPreviewImage),
			memCap:  linkPreviewImageMemCap,
			diskCap: linkPreviewImageDiskCap,
		},
	}
	// One transport so dial, TLS, and the private-host guard stay shared.
	// The clients differ only in their total timeout: page HTML versus images.
	transport := &http.Transport{
		// Validate AND connect to the same resolved IP, closing the
		// resolve-then-fetch TOCTOU (DNS-rebinding) gap where a hostname
		// could resolve to a public IP for the guard check and a private
		// IP for the actual connection. This covers the initial request
		// and every redirect in one place.
		DialContext:           s.guardedDialContext,
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          10,
		IdleConnTimeout:       30 * time.Second,
		TLSHandshakeTimeout:   linkPreviewTLSTimeout,
		ExpectContinueTimeout: time.Second,
	}
	s.client = &http.Client{
		Timeout:       linkPreviewPageTimeout,
		CheckRedirect: linkPreviewRedirect,
		Transport:     transport,
	}
	s.imageClient = &http.Client{
		Timeout:       linkPreviewImageTimeout,
		CheckRedirect: linkPreviewRedirect,
		Transport:     transport,
	}
	return s
}

func linkPreviewRedirect(req *http.Request, via []*http.Request) error {
	if len(via) >= 5 {
		return errors.New("too many redirects")
	}
	return nil
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
	dialer := &net.Dialer{Timeout: linkPreviewDialTimeout}
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

	if preview, cachedErr, ok := s.cached(normalizedURL); ok {
		return preview, cachedErr
	}

	if id := youTubeVideoID(parsedURL); id != "" {
		preview := s.fetchYouTube(ctx, normalizedURL, id)
		// Only cache a real oEmbed result: a fallback ("YouTube" title) is
		// retried next time instead of sticking for the whole cache TTL.
		if preview.Title != "YouTube" {
			s.store(normalizedURL, preview)
		}
		s.prewarmPreviewImage(preview.ImageURL)
		return cloneLinkPreview(preview), nil
	}

	preview, err := s.fetchPage(ctx, normalizedURL, parsedURL)
	if err != nil {
		if cacheablePreviewFailure(err) {
			s.storeFailure(normalizedURL, err)
		}
		return nil, err
	}
	s.store(normalizedURL, preview)
	s.prewarmPreviewImage(preview.ImageURL)
	return cloneLinkPreview(preview), nil
}

func (s *LinkPreviewService) fetchPage(ctx context.Context, normalizedURL string, parsedURL *url.URL) (*LinkPreview, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, normalizedURL, nil)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrInvalidLinkPreviewURL, err)
	}
	req.Header.Set("User-Agent", "OpenMessage/1.0 (+https://openmessage.ai)")
	req.Header.Set("Accept", "text/html,application/xhtml+xml")

	resp, err := s.client.Do(req)
	if err != nil {
		s.logFetchFailure("page", normalizedURL, 0, err)
		return nil, previewTransportError("fetch link preview", err)
	}
	defer resp.Body.Close()

	if resp.Request != nil && resp.Request.URL != nil && !s.allowPrivateHosts {
		if err := ensurePublicPreviewHost(ctx, resp.Request.URL); err != nil {
			return nil, err
		}
	}

	final := normalizedURL
	if resp.Request != nil && resp.Request.URL != nil {
		final = resp.Request.URL.String()
	}
	if resp.StatusCode < http.StatusOK || resp.StatusCode >= http.StatusBadRequest {
		s.logFetchFailure("page", final, resp.StatusCode, nil)
		return nil, fmt.Errorf("fetch link preview: unexpected status %d", resp.StatusCode)
	}

	contentType := strings.ToLower(resp.Header.Get("Content-Type"))
	if contentType != "" && !strings.Contains(contentType, "text/html") && !strings.Contains(contentType, "application/xhtml+xml") {
		s.logFetchFailure("page", final, resp.StatusCode, ErrNoLinkPreview)
		return nil, ErrNoLinkPreview
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, 3<<20))
	if err != nil {
		s.logFetchFailure("page", final, 0, err)
		return nil, previewTransportError("read link preview", err)
	}

	finalURL := parsedURL
	if resp.Request != nil && resp.Request.URL != nil {
		finalURL = resp.Request.URL
	}

	preview, err := extractLinkPreview(body, finalURL)
	if err != nil {
		s.logFetchFailure("page", final, resp.StatusCode, err)
		return nil, err
	}
	preview.URL = finalURL.String()
	preview.Domain = finalURL.Hostname()
	if preview.SiteName == "" {
		preview.SiteName = prettifyPreviewHost(finalURL.Hostname())
	}
	return preview, nil
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

	key := previewImageKey(normalizedURL)
	if data, contentType, cachedErr, ok := s.images.get(key, s.clock()); ok {
		if cachedErr != nil {
			return nil, "", cachedErr
		}
		return bytes.Clone(data), contentType, nil
	}

	v, err, _ := s.imageFlight.Do(key, func() (any, error) {
		if data, contentType, cachedErr, ok := s.images.get(key, s.clock()); ok {
			if cachedErr != nil {
				return nil, cachedErr
			}
			return fetchedPreviewImage{data: data, contentType: contentType}, nil
		}
		data, contentType, fetchErr := s.fetchImage(ctx, normalizedURL)
		if fetchErr != nil {
			if cacheablePreviewFailure(fetchErr) {
				s.images.putFailure(key, fetchErr, s.clock(), s.failureTTL)
			}
			return nil, fetchErr
		}
		if putErr := s.images.put(key, contentType, data, s.clock(), s.ttl); putErr != nil {
			s.logger.Warn().Str("host", previewHost(normalizedURL)).Str("error", sanitizePreviewError(putErr)).Msg("link preview image cache write failed")
		}
		return fetchedPreviewImage{data: data, contentType: contentType}, nil
	})
	if err != nil {
		return nil, "", err
	}
	img := v.(fetchedPreviewImage)
	return bytes.Clone(img.data), img.contentType, nil
}

func (s *LinkPreviewService) fetchImage(ctx context.Context, normalizedURL string) ([]byte, string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, normalizedURL, nil)
	if err != nil {
		return nil, "", fmt.Errorf("%w: %v", ErrInvalidLinkPreviewURL, err)
	}
	req.Header.Set("User-Agent", "OpenMessage/1.0 (+https://openmessage.ai)")
	req.Header.Set("Accept", "image/avif,image/webp,image/apng,image/png,image/jpeg,image/gif,*/*;q=0.8")

	resp, err := s.imageClient.Do(req)
	if err != nil {
		s.logFetchFailure("image", normalizedURL, 0, err)
		return nil, "", previewTransportError("fetch link preview image", err)
	}
	defer resp.Body.Close()

	if resp.Request != nil && resp.Request.URL != nil && !s.allowPrivateHosts {
		if err := ensurePublicPreviewHost(ctx, resp.Request.URL); err != nil {
			return nil, "", err
		}
	}
	final := normalizedURL
	if resp.Request != nil && resp.Request.URL != nil {
		final = resp.Request.URL.String()
	}
	if resp.StatusCode < http.StatusOK || resp.StatusCode >= http.StatusBadRequest {
		s.logFetchFailure("image", final, resp.StatusCode, nil)
		return nil, "", fmt.Errorf("fetch link preview image: unexpected status %d", resp.StatusCode)
	}

	contentType, _, err := mime.ParseMediaType(resp.Header.Get("Content-Type"))
	if err != nil || strings.TrimSpace(contentType) == "" {
		contentType = http.DetectContentType(nil)
	}
	contentType = strings.ToLower(strings.TrimSpace(contentType))
	if !isAllowedLinkPreviewImageType(contentType) {
		s.logFetchFailure("image", final, resp.StatusCode, ErrNoLinkPreview)
		return nil, "", ErrNoLinkPreview
	}
	limited := io.LimitReader(resp.Body, maxLinkPreviewImageBytes+1)
	data, err := io.ReadAll(limited)
	if err != nil {
		s.logFetchFailure("image", final, 0, err)
		return nil, "", previewTransportError("read link preview image", err)
	}
	if len(data) > maxLinkPreviewImageBytes {
		err := fmt.Errorf("link preview image exceeds %d bytes", maxLinkPreviewImageBytes)
		s.logFetchFailure("image", final, resp.StatusCode, err)
		return nil, "", err
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

func (s *LinkPreviewService) cached(rawURL string) (*LinkPreview, error, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()

	entry, ok := s.cache[rawURL]
	if !ok {
		return nil, nil, false
	}
	now := s.clock()
	if now.After(entry.expiresAt) {
		delete(s.cache, rawURL)
		return nil, nil, false
	}
	// Touch time feeds the entry cap. It does not extend expiresAt: a hit
	// must not refresh a preview that was fetched earlier.
	entry.touchedAt = now
	s.cache[rawURL] = entry
	if entry.failed {
		err := entry.err
		if err == nil {
			err = ErrNoLinkPreview
		}
		return nil, err, true
	}
	return cloneLinkPreview(entry.preview), nil, true
}

func (s *LinkPreviewService) store(rawURL string, preview *LinkPreview) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.clock()
	s.pruneExpiredLocked(now)
	s.cache[rawURL] = linkPreviewCacheEntry{
		preview:   cloneLinkPreview(preview),
		expiresAt: now.Add(s.ttl),
		touchedAt: now,
	}
	s.evictIfNeededLocked()
}

func (s *LinkPreviewService) storeFailure(rawURL string, err error) {
	if err == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.clock()
	if existing, ok := s.cache[rawURL]; ok && !existing.failed && !now.After(existing.expiresAt) {
		return
	}
	s.pruneExpiredLocked(now)
	ttl := s.failureTTL
	if ttl <= 0 || ttl > linkPreviewFailureTTL {
		ttl = linkPreviewFailureTTL
	}
	s.cache[rawURL] = linkPreviewCacheEntry{
		failed:    true,
		err:       err,
		expiresAt: now.Add(ttl),
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

func (s *LinkPreviewService) clock() time.Time {
	if s.now != nil {
		return s.now()
	}
	return time.Now()
}

// SetDataDir enables the on-disk preview image cache under dataDir.
// Expired files are removed as the directory is loaded.
func (s *LinkPreviewService) SetDataDir(dataDir string) {
	dataDir = strings.TrimSpace(dataDir)
	if dataDir == "" {
		return
	}
	if err := s.images.open(dataDir, s.clock()); err != nil {
		s.logger.Warn().Err(err).Msg("link preview image disk cache unavailable")
	}
}

// StartMaintenance drops expired preview JSON and images now, then again
// every cleanup interval (an hour unless a test shortens it).
func (s *LinkPreviewService) StartMaintenance() {
	s.maintMu.Lock()
	if s.maintStarted {
		s.maintMu.Unlock()
		return
	}
	s.maintStarted = true
	if s.cleanupEvery <= 0 {
		s.cleanupEvery = linkPreviewCleanupEvery
	}
	interval := s.cleanupEvery
	stop := make(chan struct{})
	s.maintStop = stop
	s.maintMu.Unlock()

	s.cleanupExpired()
	go s.maintenanceLoop(interval, stop)
}

// StopMaintenance ends the periodic sweep. It is safe to call more than once.
func (s *LinkPreviewService) StopMaintenance() {
	s.maintMu.Lock()
	defer s.maintMu.Unlock()
	if s.maintStop != nil {
		close(s.maintStop)
		s.maintStop = nil
	}
}

func (s *LinkPreviewService) maintenanceLoop(interval time.Duration, stop <-chan struct{}) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-stop:
			return
		case <-ticker.C:
			s.cleanupExpired()
		}
	}
}

func (s *LinkPreviewService) cleanupExpired() {
	now := s.clock()
	s.mu.Lock()
	s.pruneExpiredLocked(now)
	s.mu.Unlock()
	s.images.prune(now)
}

func (s *LinkPreviewService) prewarmPreviewImage(raw string) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return
	}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), linkPreviewImageTimeout)
		defer cancel()
		_, _, _ = s.FetchImage(ctx, raw)
	}()
}

func (s *LinkPreviewService) logFetchFailure(kind, rawURL string, status int, err error) {
	ev := s.logger.Warn().Str("host", previewHost(rawURL)).Str("kind", kind)
	if status > 0 {
		ev = ev.Int("status", status)
	}
	if err != nil {
		ev = ev.Str("error", sanitizePreviewError(err))
	}
	ev.Msg("link preview fetch failed")
}

func cacheablePreviewFailure(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, ErrInvalidLinkPreviewURL) || errors.Is(err, ErrBlockedLinkPreviewURL) {
		return false
	}
	// The caller went away. The next request has its own context and should
	// try the origin again immediately.
	if errors.Is(err, context.Canceled) {
		return false
	}
	return true
}

func previewTransportError(prefix string, err error) error {
	switch {
	case errors.Is(err, context.Canceled):
		return fmt.Errorf("%s: %w", prefix, context.Canceled)
	case errors.Is(err, context.DeadlineExceeded):
		return fmt.Errorf("%s: %w", prefix, context.DeadlineExceeded)
	default:
		return fmt.Errorf("%s: %s", prefix, sanitizePreviewError(err))
	}
}

func previewHost(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return ""
	}
	return u.Hostname()
}

func sanitizePreviewError(err error) string {
	if err == nil {
		return ""
	}
	var ue *url.Error
	if errors.As(err, &ue) {
		msg := ue.Op
		if ue.Err != nil {
			if inner := sanitizePreviewError(ue.Err); inner != "" {
				msg += ": " + inner
			}
		}
		return stripURLQuery(msg)
	}
	return stripURLQuery(err.Error())
}

func stripURLQuery(msg string) string {
	for {
		i := strings.IndexByte(msg, '?')
		if i < 0 {
			return msg
		}
		j := strings.IndexAny(msg[i:], " \t\"'")
		if j < 0 {
			return msg[:i]
		}
		msg = msg[:i] + msg[i+j:]
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
		s.logFetchFailure("page", "https://www.youtube.com/oembed", 0, err)
		return preview
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		s.logFetchFailure("page", "https://www.youtube.com/oembed", resp.StatusCode, nil)
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
