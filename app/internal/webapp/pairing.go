package webapp

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-gmessages/pkg/libgm"

	"github.com/maxghenis/openmessage/internal/client"
)

// Pairing states reported to the UI.
const (
	PairIdle      = "idle"     // nothing in progress
	PairStarting  = "starting" // contacting Google, waiting for the emoji
	PairShowEmoji = "emoji"    // emoji known: user taps it on the phone
	PairSuccess   = "success"  // session saved
	PairError     = "error"    // failed; Message explains
)

type PairingStatus struct {
	State     string `json:"state"`
	Emoji     string `json:"emoji,omitempty"`
	EmojiSVG  string `json:"emoji_svg,omitempty"`
	Message   string `json:"message,omitempty"`
	StartedAt int64  `json:"started_at,omitempty"`
	UpdatedAt int64  `json:"updated_at"`
	Fake      bool   `json:"fake,omitempty"`
}

// PairingRunner performs Google-account pairing. The real implementation is
// OpenMessage's existing `pair --google` flow (libgm DoGaiaPairing + save
// session.json); tests and the dev flag swap in a fake.
type PairingRunner func(ctx context.Context, cookies map[string]string, onEmoji func(string)) error

// Pairer runs one pairing attempt at a time and exposes its state.
type Pairer struct {
	mu       sync.Mutex
	status   PairingStatus
	cancel   context.CancelFunc
	vault    *CookieVault
	run      PairingRunner
	fake     string
	onPaired func() error
	ntfyURL  string
	logger   zerolog.Logger
	http     *http.Client
}

func NewPairer(vault *CookieVault, run PairingRunner, fakeEmoji string, onPaired func() error, ntfyURL string, logger zerolog.Logger) *Pairer {
	return &Pairer{
		status:   PairingStatus{State: PairIdle, UpdatedAt: time.Now().UnixMilli()},
		vault:    vault,
		run:      run,
		fake:     fakeEmoji,
		onPaired: onPaired,
		ntfyURL:  ntfyURL,
		logger:   logger,
		http:     &http.Client{Timeout: 10 * time.Second},
	}
}

func (p *Pairer) Status() PairingStatus {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.status
}

func (p *Pairer) set(update func(*PairingStatus)) {
	p.mu.Lock()
	update(&p.status)
	p.status.UpdatedAt = time.Now().UnixMilli()
	p.mu.Unlock()
}

// Start begins a pairing attempt in the background.
func (p *Pairer) Start() error {
	p.mu.Lock()
	if p.status.State == PairStarting || p.status.State == PairShowEmoji {
		p.mu.Unlock()
		return errors.New("pairing already in progress")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	p.cancel = cancel
	now := time.Now().UnixMilli()
	p.status = PairingStatus{State: PairStarting, StartedAt: now, UpdatedAt: now, Fake: p.fake != ""}
	p.mu.Unlock()

	if p.fake != "" {
		// Dev flag: never touches the network. Shows the configured emoji
		// and stays there until cancelled so the screen can be inspected.
		go func() {
			defer cancel()
			select {
			case <-time.After(700 * time.Millisecond):
				p.showEmoji(p.fake)
			case <-ctx.Done():
			}
		}()
		return nil
	}

	cookies, _, err := p.vault.Load()
	if err != nil {
		cancel()
		p.set(func(s *PairingStatus) { s.State = PairError; s.Message = err.Error() })
		return err
	}
	go func() {
		defer cancel()
		err := p.run(ctx, cookies, p.showEmoji)
		if err != nil {
			p.logger.Warn().Str("error_kind", pairingErrorKind(err)).Msg("Web pairing failed")
			p.set(func(s *PairingStatus) { s.State = PairError; s.Message = friendlyPairingError(err) })
			return
		}
		msg := "Paired. Connecting to your phone…"
		if p.onPaired != nil {
			if err := p.onPaired(); err != nil {
				msg = "Paired, but reconnect failed; restart the server."
			}
		}
		p.set(func(s *PairingStatus) { s.State = PairSuccess; s.Emoji = ""; s.EmojiSVG = ""; s.Message = msg })
	}()
	return nil
}

func (p *Pairer) showEmoji(emoji string) {
	p.set(func(s *PairingStatus) {
		s.State = PairShowEmoji
		s.Emoji = emoji
		s.EmojiSVG = libgm.GetEmojiSVG(emoji)
		s.Message = "Tap this on your phone"
	})
	p.notify(emoji)
}

func (p *Pairer) Cancel() {
	p.mu.Lock()
	if p.cancel != nil {
		p.cancel()
	}
	p.status = PairingStatus{State: PairIdle, UpdatedAt: time.Now().UnixMilli()}
	p.mu.Unlock()
}

// notify optionally pushes the emoji to ntfy (off unless MESSAGES_NTFY_URL set).
func (p *Pairer) notify(emoji string) {
	if p.ntfyURL == "" {
		return
	}
	go func() {
		req, err := http.NewRequest(http.MethodPost, p.ntfyURL, strings.NewReader("Tap "+emoji+" in Google Messages on your phone"))
		if err != nil {
			return
		}
		req.Header.Set("Title", "Messages Enhanced pairing")
		req.Header.Set("Tags", "car")
		req.Header.Set("Priority", "high")
		resp, err := p.http.Do(req)
		if err != nil {
			p.logger.Warn().Err(err).Msg("ntfy push failed")
			return
		}
		resp.Body.Close()
	}()
}

func pairingErrorKind(err error) string {
	switch {
	case errors.Is(err, libgm.ErrIncorrectEmoji):
		return "incorrect_emoji"
	case errors.Is(err, libgm.ErrPairingCancelled):
		return "cancelled"
	case errors.Is(err, libgm.ErrPairingTimeout), errors.Is(err, libgm.ErrPairingInitTimeout), errors.Is(err, context.DeadlineExceeded):
		return "timeout"
	case errors.Is(err, libgm.ErrNoDevicesFound):
		return "no_devices"
	case errors.Is(err, libgm.ErrNoCookies):
		return "no_cookies"
	case errors.Is(err, context.Canceled):
		return "aborted"
	default:
		return "other"
	}
}

func friendlyPairingError(err error) string {
	switch pairingErrorKind(err) {
	case "incorrect_emoji":
		return "Wrong emoji was tapped on the phone. Try again."
	case "cancelled":
		return "Pairing was cancelled on the phone."
	case "timeout":
		return "Pairing timed out. Make sure the phone is online, then try again."
	case "no_devices":
		return "Google found no phone for this account. Is Google Messages set up on it?"
	case "no_cookies":
		return "No Google cookies. Paste them on the cookies page first."
	case "aborted":
		return "Pairing stopped."
	}
	// libgm errors don't contain cookie values, but keep it short.
	msg := err.Error()
	if len(msg) > 200 {
		msg = msg[:200]
	}
	return "Pairing failed: " + msg
}

// OpenMessageGooglePairing is OpenMessage's `pair --google` flow (see
// cmd/pair.go runGoogleAccountPairing), reused as-is: set cookies on a fresh
// libgm client, DoGaiaPairing, then save session.json.
func OpenMessageGooglePairing(logger zerolog.Logger, sessionPath string) PairingRunner {
	return func(ctx context.Context, cookies map[string]string, onEmoji func(string)) error {
		cli := client.NewForPairing(logger)
		defer cli.GM.Disconnect()
		cli.GM.AuthData.Cookies = cookies
		if err := cli.GM.DoGaiaPairing(ctx, onEmoji); err != nil {
			return err
		}
		data, err := cli.SessionData()
		if err != nil {
			return fmt.Errorf("get session data: %w", err)
		}
		return client.SaveSession(sessionPath, data)
	}
}
