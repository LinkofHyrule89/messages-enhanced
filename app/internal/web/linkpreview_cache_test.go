package web

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/rs/zerolog"
)

func TestLinkPreviewClientsUseSeparateTimeouts(t *testing.T) {
	if linkPreviewPageTimeout != 10*time.Second {
		t.Fatalf("page timeout = %s, want 10s", linkPreviewPageTimeout)
	}
	if linkPreviewImageTimeout != 25*time.Second {
		t.Fatalf("image timeout = %s, want 25s", linkPreviewImageTimeout)
	}
	if linkPreviewDialTimeout != 5*time.Second || linkPreviewTLSTimeout != 5*time.Second {
		t.Fatalf("dial/tls = %s/%s, want 5s/5s", linkPreviewDialTimeout, linkPreviewTLSTimeout)
	}
	if linkPreviewSuccessTTL != 7*24*time.Hour {
		t.Fatalf("success ttl = %s, want 7d", linkPreviewSuccessTTL)
	}
	if linkPreviewFailureTTL > time.Minute {
		t.Fatalf("failure ttl = %s, want at most 1m", linkPreviewFailureTTL)
	}
	if linkPreviewCleanupEvery != time.Hour {
		t.Fatalf("cleanup interval = %s, want 1h", linkPreviewCleanupEvery)
	}

	svc := NewLinkPreviewService(zerolog.Nop())
	if svc.client == nil || svc.imageClient == nil {
		t.Fatal("missing preview clients")
	}
	if svc.client.Timeout != linkPreviewPageTimeout || svc.imageClient.Timeout != linkPreviewImageTimeout {
		t.Fatalf("client timeouts = %s and %s", svc.client.Timeout, svc.imageClient.Timeout)
	}
	for _, client := range []*http.Client{svc.client, svc.imageClient} {
		tr, ok := client.Transport.(*http.Transport)
		if !ok {
			t.Fatal("preview transport has the wrong type")
		}
		if tr.TLSHandshakeTimeout != linkPreviewTLSTimeout {
			t.Fatalf("TLS handshake timeout = %s", tr.TLSHandshakeTimeout)
		}
	}
}

func TestLinkPreviewImageCacheHitAndDiskReload(t *testing.T) {
	var hits atomic.Int32
	const body = "webp-bytes"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		w.Header().Set("Content-Type", "image/webp")
		_, _ = w.Write([]byte(body))
	}))
	defer srv.Close()

	dir := t.TempDir()
	svc := NewLinkPreviewService(zerolog.Nop())
	svc.allowPrivateHosts = true
	svc.SetDataDir(dir)
	raw := srv.URL + "/photo.webp?format=webp&name=large"

	data, contentType, err := svc.FetchImage(context.Background(), raw)
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != body || contentType != "image/webp" {
		t.Fatalf("got %q %s", data, contentType)
	}
	data, contentType, err = svc.FetchImage(context.Background(), raw)
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != body || contentType != "image/webp" {
		t.Fatalf("cached got %q %s", data, contentType)
	}
	if hits.Load() != 1 {
		t.Fatalf("upstream hits = %d, want 1", hits.Load())
	}

	reloaded := NewLinkPreviewService(zerolog.Nop())
	reloaded.allowPrivateHosts = true
	reloaded.SetDataDir(dir)
	data, contentType, err = reloaded.FetchImage(context.Background(), raw)
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != body || contentType != "image/webp" {
		t.Fatalf("disk got %q %s", data, contentType)
	}
	if hits.Load() != 1 {
		t.Fatalf("disk reload hit upstream, hits = %d", hits.Load())
	}
}

func TestLinkPreviewImageFetchSucceedsWhenSlowerThanPageTimeout(t *testing.T) {
	const delay = 11 * time.Second
	if delay <= linkPreviewPageTimeout || delay >= linkPreviewImageTimeout {
		t.Fatalf("delay %s must sit between the page and image timeouts", delay)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(delay)
		w.Header().Set("Content-Type", "image/webp")
		_, _ = w.Write([]byte("slow-webp"))
	}))
	defer srv.Close()

	svc := NewLinkPreviewService(zerolog.Nop())
	svc.allowPrivateHosts = true
	start := time.Now()
	data, contentType, err := svc.FetchImage(context.Background(), srv.URL+"/slow.webp?format=webp&name=large")
	if err != nil {
		t.Fatal(err)
	}
	if time.Since(start) < delay {
		t.Fatal("image fetch returned before the slow server responded")
	}
	if string(data) != "slow-webp" || contentType != "image/webp" {
		t.Fatalf("got %q %s", data, contentType)
	}
}

func TestLinkPreviewImageFetchDedupesInFlight(t *testing.T) {
	var hits atomic.Int32
	hold := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		<-hold
		w.Header().Set("Content-Type", "image/png")
		_, _ = w.Write([]byte("png-bytes"))
	}))
	defer srv.Close()

	svc := NewLinkPreviewService(zerolog.Nop())
	svc.allowPrivateHosts = true
	raw := srv.URL + "/a.png?x=1"

	const n = 8
	var ready sync.WaitGroup
	var start sync.WaitGroup
	ready.Add(n)
	start.Add(1)
	errCh := make(chan error, n)
	var wg sync.WaitGroup
	wg.Add(n)
	for i := 0; i < n; i++ {
		go func() {
			defer wg.Done()
			ready.Done()
			start.Wait()
			data, contentType, err := svc.FetchImage(context.Background(), raw)
			if err != nil {
				errCh <- err
				return
			}
			if string(data) != "png-bytes" || contentType != "image/png" {
				errCh <- fmt.Errorf("got %q %s", data, contentType)
			}
		}()
	}
	ready.Wait()
	start.Done()

	deadline := time.Now().Add(2 * time.Second)
	for hits.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	time.Sleep(200 * time.Millisecond)
	if got := hits.Load(); got != 1 {
		close(hold)
		wg.Wait()
		t.Fatalf("upstream hits while in flight = %d, want 1", got)
	}
	close(hold)
	wg.Wait()
	close(errCh)
	for err := range errCh {
		t.Error(err)
	}
	if got := hits.Load(); got != 1 {
		t.Fatalf("upstream hits = %d, want 1", got)
	}
}

func TestLinkPreviewSuccessNotRefreshedBeforeSevenDays(t *testing.T) {
	t.Run("json", func(t *testing.T) {
		var hits atomic.Int32
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			hits.Add(1)
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			_, _ = w.Write([]byte(`<!doctype html><html><head><meta property="og:title" content="Hello"></head></html>`))
		}))
		defer srv.Close()

		base := time.Now()
		cur := base
		svc := NewLinkPreviewService(zerolog.Nop())
		svc.allowPrivateHosts = true
		svc.now = func() time.Time { return cur }
		raw := srv.URL + "/story"
		first, err := svc.Fetch(context.Background(), raw)
		if err != nil || first.Title != "Hello" {
			t.Fatalf("first fetch: %v %+v", err, first)
		}
		key, _, err := normalizeLinkPreviewURL(raw)
		if err != nil {
			t.Fatal(err)
		}
		svc.mu.Lock()
		stored := svc.cache[key].expiresAt
		svc.mu.Unlock()
		if !stored.Equal(base.Add(linkPreviewSuccessTTL)) {
			t.Fatalf("expiresAt = %s, want %s", stored, base.Add(linkPreviewSuccessTTL))
		}

		cur = base.Add(linkPreviewSuccessTTL - time.Second)
		second, err := svc.Fetch(context.Background(), raw)
		if err != nil || second.Title != "Hello" {
			t.Fatalf("cached fetch: %v %+v", err, second)
		}
		if hits.Load() != 1 {
			t.Fatalf("refreshed a live preview, hits = %d", hits.Load())
		}
		svc.mu.Lock()
		if !svc.cache[key].expiresAt.Equal(stored) {
			t.Fatal("cache hit extended the preview expiry")
		}
		svc.mu.Unlock()

		cur = base.Add(linkPreviewSuccessTTL + time.Second)
		if _, err := svc.Fetch(context.Background(), raw); err != nil {
			t.Fatal(err)
		}
		if hits.Load() != 2 {
			t.Fatalf("expired preview was not fetched again, hits = %d", hits.Load())
		}
	})

	t.Run("image", func(t *testing.T) {
		var hits atomic.Int32
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			hits.Add(1)
			w.Header().Set("Content-Type", "image/webp")
			_, _ = w.Write([]byte("img"))
		}))
		defer srv.Close()

		dir := t.TempDir()
		base := time.Now()
		cur := base
		nowFn := func() time.Time { return cur }
		svc := NewLinkPreviewService(zerolog.Nop())
		svc.allowPrivateHosts = true
		svc.now = nowFn
		svc.SetDataDir(dir)
		raw := srv.URL + "/a.webp"

		if _, _, err := svc.FetchImage(context.Background(), raw); err != nil {
			t.Fatal(err)
		}
		cur = base.Add(linkPreviewSuccessTTL - time.Second)
		if _, _, err := svc.FetchImage(context.Background(), raw); err != nil {
			t.Fatal(err)
		}
		if hits.Load() != 1 {
			t.Fatalf("refreshed a live image, hits = %d", hits.Load())
		}

		other := NewLinkPreviewService(zerolog.Nop())
		other.allowPrivateHosts = true
		other.now = nowFn
		other.SetDataDir(dir)
		if _, _, err := other.FetchImage(context.Background(), raw); err != nil {
			t.Fatal(err)
		}
		if hits.Load() != 1 {
			t.Fatalf("disk image was refreshed early, hits = %d", hits.Load())
		}

		cur = base.Add(linkPreviewSuccessTTL + time.Second)
		if _, _, err := other.FetchImage(context.Background(), raw); err != nil {
			t.Fatal(err)
		}
		if hits.Load() != 2 {
			t.Fatalf("expired image was not fetched again, hits = %d", hits.Load())
		}
	})
}

func TestLinkPreviewFailureExpiresWithinAMinute(t *testing.T) {
	var pageHits, imageHits atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "img") {
			imageHits.Add(1)
		} else {
			pageHits.Add(1)
		}
		http.Error(w, "nope", http.StatusBadGateway)
	}))
	defer srv.Close()

	dir := t.TempDir()
	base := time.Now()
	cur := base
	svc := NewLinkPreviewService(zerolog.Nop())
	svc.allowPrivateHosts = true
	svc.now = func() time.Time { return cur }
	svc.SetDataDir(dir)

	pageURL := srv.URL + "/page?secret=topsecret"
	if _, err := svc.Fetch(context.Background(), pageURL); err == nil {
		t.Fatal("expected page failure")
	}
	if _, err := svc.Fetch(context.Background(), pageURL); err == nil {
		t.Fatal("expected cached page failure")
	}
	if pageHits.Load() != 1 {
		t.Fatalf("page hits inside the failure window = %d, want 1", pageHits.Load())
	}

	imageURL := srv.URL + "/img.webp?secret=topsecret"
	if _, _, err := svc.FetchImage(context.Background(), imageURL); err == nil {
		t.Fatal("expected image failure")
	}
	if _, _, err := svc.FetchImage(context.Background(), imageURL); err == nil {
		t.Fatal("expected cached image failure")
	}
	if imageHits.Load() != 1 {
		t.Fatalf("image hits inside the failure window = %d, want 1", imageHits.Load())
	}
	entries, err := os.ReadDir(filepath.Join(dir, linkPreviewImageDirName))
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 0 {
		t.Fatalf("failure was written to disk: %d files", len(entries))
	}

	cur = base.Add(linkPreviewFailureTTL + time.Second)
	if _, err := svc.Fetch(context.Background(), pageURL); err == nil {
		t.Fatal("expected a fresh page failure")
	}
	if _, _, err := svc.FetchImage(context.Background(), imageURL); err == nil {
		t.Fatal("expected a fresh image failure")
	}
	if pageHits.Load() != 2 || imageHits.Load() != 2 {
		t.Fatalf("after the failure window, page hits = %d image hits = %d, want 2 and 2", pageHits.Load(), imageHits.Load())
	}
}

func TestLinkPreviewCanceledFetchIsNotCached(t *testing.T) {
	var hits atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		w.Header().Set("Content-Type", "image/png")
		_, _ = w.Write([]byte("png"))
	}))
	defer srv.Close()

	svc := NewLinkPreviewService(zerolog.Nop())
	svc.allowPrivateHosts = true
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, _, err := svc.FetchImage(ctx, srv.URL+"/a.png")
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("got %v, want context.Canceled", err)
	}
	before := hits.Load()
	data, _, err := svc.FetchImage(context.Background(), srv.URL+"/a.png")
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "png" {
		t.Fatalf("data = %q", data)
	}
	if hits.Load() != before+1 {
		t.Fatalf("hits = %d, before = %d; canceled fetch was reused", hits.Load(), before)
	}
}

func TestLinkPreviewStartupCleanupDropsExpiredImages(t *testing.T) {
	dir := t.TempDir()
	cacheDir := filepath.Join(dir, linkPreviewImageDirName)
	if err := os.MkdirAll(cacheDir, 0o700); err != nil {
		t.Fatal(err)
	}
	oldKey := previewImageKey("https://cdn.example/old.png")
	freshKey := previewImageKey("https://cdn.example/fresh.png")
	past := time.Now().Add(-8 * 24 * time.Hour)
	if err := writePreviewImageFile(filepath.Join(cacheDir, oldKey), "image/webp", []byte("old"), past, past.Add(linkPreviewSuccessTTL)); err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	if err := writePreviewImageFile(filepath.Join(cacheDir, freshKey), "image/webp", []byte("fresh"), now, now.Add(linkPreviewSuccessTTL)); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(cacheDir, "not-a-key"), []byte("junk"), 0o600); err != nil {
		t.Fatal(err)
	}

	svc := NewLinkPreviewService(zerolog.Nop())
	svc.SetDataDir(dir)
	if _, err := os.Stat(filepath.Join(cacheDir, oldKey)); !os.IsNotExist(err) {
		t.Fatalf("expired image remains: %v", err)
	}
	if _, err := os.Stat(filepath.Join(cacheDir, freshKey)); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(cacheDir, "not-a-key")); !os.IsNotExist(err) {
		t.Fatalf("junk file remains: %v", err)
	}
	svc.images.mu.Lock()
	defer svc.images.mu.Unlock()
	if _, ok := svc.images.disk[freshKey]; !ok {
		t.Fatal("fresh image was not indexed")
	}
	if _, ok := svc.images.disk[oldKey]; ok {
		t.Fatal("expired image was indexed")
	}
}

func TestLinkPreviewMaintenanceSweepDropsExpired(t *testing.T) {
	svc := NewLinkPreviewService(zerolog.Nop())
	svc.cleanupEvery = 20 * time.Millisecond
	dir := t.TempDir()
	svc.SetDataDir(dir)
	svc.StartMaintenance()
	defer svc.StopMaintenance()

	svc.mu.Lock()
	svc.cache["http://example.com/old"] = linkPreviewCacheEntry{
		preview:   &LinkPreview{Title: "old"},
		expiresAt: time.Now().Add(-time.Second),
		touchedAt: time.Now(),
	}
	svc.cache["http://example.com/fresh"] = linkPreviewCacheEntry{
		preview:   &LinkPreview{Title: "fresh"},
		expiresAt: time.Now().Add(linkPreviewSuccessTTL),
		touchedAt: time.Now(),
	}
	svc.mu.Unlock()

	past := time.Now().Add(-8 * 24 * time.Hour)
	oldKey := previewImageKey("https://cdn.example/old.png")
	freshKey := previewImageKey("https://cdn.example/fresh.png")
	if err := svc.images.put(oldKey, "image/png", []byte("old"), past, linkPreviewSuccessTTL); err != nil {
		t.Fatal(err)
	}
	if err := svc.images.put(freshKey, "image/png", []byte("fresh"), time.Now(), linkPreviewSuccessTTL); err != nil {
		t.Fatal(err)
	}

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		svc.mu.Lock()
		_, oldJSON := svc.cache["http://example.com/old"]
		_, freshJSON := svc.cache["http://example.com/fresh"]
		svc.mu.Unlock()
		svc.images.mu.Lock()
		_, oldImg := svc.images.disk[oldKey]
		_, freshImg := svc.images.disk[freshKey]
		svc.images.mu.Unlock()
		if !oldJSON && freshJSON && !oldImg && freshImg {
			if _, err := os.Stat(filepath.Join(dir, linkPreviewImageDirName, oldKey)); !os.IsNotExist(err) {
				t.Fatalf("expired image file remains: %v", err)
			}
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("periodic cleanup left expired preview state behind")
}

func TestLinkPreviewImageDiskEvictsOldest(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/png")
		_, _ = w.Write([]byte("0123456789abcdef"))
	}))
	defer srv.Close()

	dir := t.TempDir()
	base := time.Now()
	cur := base
	svc := NewLinkPreviewService(zerolog.Nop())
	svc.allowPrivateHosts = true
	svc.now = func() time.Time { return cur }
	svc.SetDataDir(dir)

	first := srv.URL + "/a.png"
	second := srv.URL + "/b.png"
	third := srv.URL + "/c.png"
	for _, raw := range []string{first, second} {
		if _, _, err := svc.FetchImage(context.Background(), raw); err != nil {
			t.Fatal(err)
		}
		cur = cur.Add(time.Minute)
	}
	keyA := mustPreviewKey(t, first)
	keyB := mustPreviewKey(t, second)
	svc.images.mu.Lock()
	svc.images.diskCap = svc.images.disk[keyA].size + svc.images.disk[keyB].size
	svc.images.mu.Unlock()

	if _, _, err := svc.FetchImage(context.Background(), third); err != nil {
		t.Fatal(err)
	}
	cacheDir := filepath.Join(dir, linkPreviewImageDirName)
	if _, err := os.Stat(filepath.Join(cacheDir, keyA)); !os.IsNotExist(err) {
		t.Fatalf("oldest image remains: %v", err)
	}
	if _, err := os.Stat(filepath.Join(cacheDir, keyB)); err != nil {
		t.Fatalf("newer image missing: %v", err)
	}
	if _, err := os.Stat(filepath.Join(cacheDir, mustPreviewKey(t, third))); err != nil {
		t.Fatalf("newest image missing: %v", err)
	}
}

func TestLinkPreviewPrewarmsImage(t *testing.T) {
	var hits atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/img.webp" {
			hits.Add(1)
			w.Header().Set("Content-Type", "image/webp")
			_, _ = w.Write([]byte("warm"))
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = fmt.Fprintf(w, `<!doctype html><html><head><meta property="og:title" content="T"><meta property="og:image" content="/img.webp"></head></html>`)
	}))
	defer srv.Close()

	svc := NewLinkPreviewService(zerolog.Nop())
	svc.allowPrivateHosts = true
	preview, err := svc.Fetch(context.Background(), srv.URL+"/post")
	if err != nil {
		t.Fatal(err)
	}
	if preview.ImageURL != srv.URL+"/img.webp" {
		t.Fatalf("image url = %s", preview.ImageURL)
	}
	deadline := time.Now().Add(2 * time.Second)
	for hits.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if hits.Load() != 1 {
		t.Fatalf("prewarm hits = %d, want 1", hits.Load())
	}
	data, contentType, err := svc.FetchImage(context.Background(), preview.ImageURL)
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "warm" || contentType != "image/webp" {
		t.Fatalf("got %q %s", data, contentType)
	}
	if hits.Load() != 1 {
		t.Fatalf("prewarmed image was fetched again, hits = %d", hits.Load())
	}
}

func TestLinkPreviewFailureLogOmitsQuery(t *testing.T) {
	var buf bytes.Buffer
	logger := zerolog.New(&buf)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "nope", http.StatusBadGateway)
	}))
	defer srv.Close()

	svc := NewLinkPreviewService(logger)
	svc.allowPrivateHosts = true
	raw := srv.URL + "/x?secret=topsecret&format=webp"
	if _, err := svc.Fetch(context.Background(), raw); err == nil {
		t.Fatal("expected page error")
	}
	if _, _, err := svc.FetchImage(context.Background(), raw); err == nil {
		t.Fatal("expected image error")
	}
	srv.Close()
	if _, err := svc.Fetch(context.Background(), raw+"&again=1"); err == nil {
		t.Fatal("expected transport error")
	}

	log := buf.String()
	for _, leaked := range []string{"topsecret", "format=webp", "again=1", "?"} {
		if strings.Contains(log, leaked) {
			t.Fatalf("log contains %q: %s", leaked, log)
		}
	}
	if !strings.Contains(log, "127.0.0.1") || !strings.Contains(log, "502") {
		t.Fatalf("log missing host or status: %s", log)
	}

	parsed := &url.Error{
		Op:  "Get",
		URL: "https://pbs.twimg.com/media/HWOLES3XgAAeXpt?format=webp&name=large",
		Err: context.DeadlineExceeded,
	}
	got := sanitizePreviewError(parsed)
	if strings.Contains(got, "format=webp") || strings.Contains(got, "name=large") || strings.Contains(got, "?") {
		t.Fatalf("sanitized error = %q", got)
	}
	if !strings.Contains(got, "deadline") {
		t.Fatalf("sanitized error = %q", got)
	}
}

func mustPreviewKey(t *testing.T, raw string) string {
	t.Helper()
	normalized, _, err := normalizeLinkPreviewURL(raw)
	if err != nil {
		t.Fatal(err)
	}
	return previewImageKey(normalized)
}
