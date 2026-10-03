package app

// The signed-in Google account's current profile photo for the web app's
// header (preferred over the phone's own contact-card photo).
//
// Source: the Google account session the server already holds (the cookies
// from the Google-account pairing). The server asks Google's account
// endpoints for the account avatar URL (an lh3.googleusercontent.com
// photo), downloads the image without cookies and caches it in the
// database. The browser only ever gets the image bytes from our own
// /api/app/profile-photo; cookies never leave the server.
//
// Refresh: lazily, when the cached copy is older than 12 h (2 h after a
// failed check). Google cookie rotations seen in these responses are
// written back into the session (existing cookie names only), exactly as
// libgm does for its own requests.

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"
)

const (
	accountPhotoTTL      = 12 * time.Hour
	accountPhotoRetry    = 2 * time.Hour
	accountPhotoMaxBytes = 2 << 20
)

var (
	accountPhotoMu      sync.Mutex
	accountPhotoRunning bool
	// Account avatar URLs on Google's photo CDN ("/a/…" account photos,
	// "/ogw/…" as served to the Google bar).
	accountPhotoURLRe = regexp.MustCompile(`https://lh3\.googleusercontent\.com/(?:a|ogw)/[A-Za-z0-9_\-./=]+`)
	// Endpoints that list the signed-in account with its photo, tried in order.
	accountPhotoEndpoints = []string{
		"https://accounts.google.com/ListAccounts?gpsia=1&source=ChromiumBrowser&json=standard",
		"https://ogs.google.com/u/0/widget/account?hl=en",
	}
	accountPhotoHTTP = &http.Client{Timeout: 20 * time.Second}
)

// AccountPhoto returns the cached Google account photo (ok=false: none yet),
// starting a background refresh when it is missing or stale.
func (a *App) AccountPhoto() (image []byte, mimeType, hash string, ok bool) {
	if a == nil || a.Store == nil {
		return nil, "", "", false
	}
	p, err := a.Store.GetAccountPhoto()
	now := time.Now()
	stale := err != nil || p == nil ||
		(p.Hash != "" && now.Sub(time.UnixMilli(p.FetchedAtMS)) > accountPhotoTTL && now.Sub(time.UnixMilli(p.CheckedAtMS)) > accountPhotoRetry) ||
		(p.Hash == "" && now.Sub(time.UnixMilli(p.CheckedAtMS)) > accountPhotoRetry)
	if stale {
		a.refreshAccountPhotoAsync()
	}
	if p == nil || p.Hash == "" || len(p.Image) == 0 {
		return nil, "", "", false
	}
	return p.Image, p.MimeType, p.Hash, true
}

func (a *App) refreshAccountPhotoAsync() {
	accountPhotoMu.Lock()
	if accountPhotoRunning {
		accountPhotoMu.Unlock()
		return
	}
	accountPhotoRunning = true
	accountPhotoMu.Unlock()
	go func() {
		defer func() {
			accountPhotoMu.Lock()
			accountPhotoRunning = false
			accountPhotoMu.Unlock()
		}()
		ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
		defer cancel()
		if err := a.refreshAccountPhoto(ctx); err != nil {
			a.Logger.Info().Err(err).Msg("Google account photo not refreshed (header falls back to the contact photo)")
			_ = a.Store.MarkAccountPhotoChecked(time.Now().UnixMilli())
		}
	}()
}

func (a *App) refreshAccountPhoto(ctx context.Context) error {
	cli := a.GetClient()
	if cli == nil || cli.GM == nil || cli.GM.AuthData == nil || !cli.GM.AuthData.IsGoogleAccount() {
		return errors.New("no Google account session")
	}
	ad := cli.GM.AuthData
	ad.CookiesLock.RLock()
	cookies := make(map[string]string, len(ad.Cookies))
	for k, v := range ad.Cookies {
		cookies[k] = v
	}
	ad.CookiesLock.RUnlock()
	if len(cookies) == 0 {
		return errors.New("no Google account cookies")
	}
	var photoURL string
	var lastErr error
	for _, endpoint := range accountPhotoEndpoints {
		u, err := a.accountPhotoURLFrom(ctx, endpoint, cookies)
		if err == nil && u != "" {
			photoURL = u
			break
		}
		lastErr = err
	}
	if photoURL == "" {
		if lastErr == nil {
			lastErr = errors.New("no account photo URL in Google's response")
		}
		return lastErr
	}
	img, mimeType, err := downloadAccountPhoto(ctx, sizedPhotoURL(photoURL, 256))
	if err != nil {
		return err
	}
	sum := sha256.Sum256(img)
	if err := a.Store.SaveAccountPhoto(img, mimeType, hex.EncodeToString(sum[:]), time.Now().UnixMilli()); err != nil {
		return err
	}
	a.Logger.Info().Int("bytes", len(img)).Msg("Google account photo refreshed")
	return nil
}

func (a *App) accountPhotoURLFrom(ctx context.Context, endpoint string, cookies map[string]string) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return "", err
	}
	for name, value := range cookies {
		req.AddCookie(&http.Cookie{Name: name, Value: value})
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
	req.Header.Set("Accept-Language", "en-US,en;q=0.9")
	resp, err := accountPhotoHTTP.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	a.keepRotatedCookies(resp)
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("%s: HTTP %d", strings.SplitN(endpoint, "?", 2)[0], resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	if err != nil {
		return "", err
	}
	return accountPhotoURLIn(string(body)), nil
}

// accountPhotoURLIn finds the first account avatar URL in a response (JSON
// escapes undone).
func accountPhotoURLIn(body string) string {
	body = strings.NewReplacer(`\u003d`, "=", `\u0026`, "&", `\/`, "/", `\x3d`, "=").Replace(body)
	return accountPhotoURLRe.FindString(body)
}

// sizedPhotoURL asks the photo CDN for a size×size square crop.
func sizedPhotoURL(u string, size int) string {
	if i := strings.LastIndex(u, "="); i > strings.LastIndex(u, "/") {
		u = u[:i]
	}
	return fmt.Sprintf("%s=s%d-c", u, size)
}

func downloadAccountPhoto(ctx context.Context, u string) ([]byte, string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, "", err
	}
	resp, err := accountPhotoHTTP.Do(req) // public CDN URL: no cookies
	if err != nil {
		return nil, "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, "", fmt.Errorf("photo download: HTTP %d", resp.StatusCode)
	}
	img, err := io.ReadAll(io.LimitReader(resp.Body, accountPhotoMaxBytes+1))
	if err != nil {
		return nil, "", err
	}
	if len(img) == 0 || len(img) > accountPhotoMaxBytes {
		return nil, "", fmt.Errorf("photo download: %d bytes", len(img))
	}
	mimeType := http.DetectContentType(img)
	if !strings.HasPrefix(mimeType, "image/") {
		return nil, "", fmt.Errorf("photo download: not an image (%s)", mimeType)
	}
	return img, mimeType, nil
}

// keepRotatedCookies writes Google's cookie rotations back into the session
// (only names it already has), like libgm's UpdateCookiesFromResponse.
func (a *App) keepRotatedCookies(resp *http.Response) {
	cli := a.GetClient()
	if cli == nil || cli.GM == nil || cli.GM.AuthData == nil || len(resp.Cookies()) == 0 {
		return
	}
	ad := cli.GM.AuthData
	ad.CookiesLock.Lock()
	defer ad.CookiesLock.Unlock()
	for _, c := range resp.Cookies() {
		if old, ok := ad.Cookies[c.Name]; ok && c.Value != "" && c.Value != old && c.MaxAge >= 0 {
			ad.Cookies[c.Name] = c.Value
		}
	}
}
