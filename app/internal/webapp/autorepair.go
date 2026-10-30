package webapp

// Auto Google re-pair: when Google Messages auth expires and encrypted vault
// cookies are available, start Gaia pairing automatically (user only taps the
// emoji on the phone). No Google password is stored. Cookie values are never
// logged. Attempts are paced so we don't spam Google.

import (
	"encoding/json"
	"errors"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/rs/zerolog"
)

const (
	autoRepairSettingKey = "auto_google_repair"
	autoRepairMinGap     = 15 * time.Minute
	autoRepairPoll       = 20 * time.Second
)

// AutoRepairer watches Google auth-expiry and starts Pairer when allowed.
type AutoRepairer struct {
	mu           sync.Mutex
	pairer       *Pairer
	vault        *CookieVault
	settings     *SettingsStore
	sessionPath  string
	push         *PushHub
	google       func() any
	log          zerolog.Logger
	lastAttempt  time.Time
	signInNeeded bool // vault cookies were rejected; wait for manual reconnect
	lastEmoji    string
	notifiedEmoji string
}

func NewAutoRepairer(pairer *Pairer, vault *CookieVault, settings *SettingsStore, sessionPath string, push *PushHub, google func() any, log zerolog.Logger) *AutoRepairer {
	return &AutoRepairer{
		pairer: pairer, vault: vault, settings: settings, sessionPath: sessionPath,
		push: push, google: google, log: log,
	}
}

// SignInNeeded reports that saved cookies were rejected and the user must
// sign in again in the app (or paste cookies).
func (a *AutoRepairer) SignInNeeded() bool {
	if a == nil {
		return false
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.signInNeeded
}

// ClearSignInNeeded is called after a successful manual cookie submit.
func (a *AutoRepairer) ClearSignInNeeded() {
	if a == nil {
		return
	}
	a.mu.Lock()
	a.signInNeeded = false
	a.mu.Unlock()
}

func (a *AutoRepairer) MarkSignInNeeded() {
	if a == nil {
		return
	}
	a.mu.Lock()
	a.signInNeeded = true
	a.mu.Unlock()
}

// Enabled reads the shared settings toggle (default on).
func (a *AutoRepairer) Enabled() bool {
	if a == nil || a.settings == nil {
		return true
	}
	raw := a.settings.Get()
	var m map[string]any
	if json.Unmarshal(raw, &m) != nil {
		return true
	}
	v, ok := m[autoRepairSettingKey]
	if !ok {
		return true
	}
	switch t := v.(type) {
	case bool:
		return t
	case string:
		s := strings.ToLower(strings.TrimSpace(t))
		return s != "0" && s != "false" && s != "off" && s != "no"
	default:
		return true
	}
}

// HasSavedSession reports whether the encrypted vault has cookies.
func (a *AutoRepairer) HasSavedSession() bool {
	if a == nil || a.vault == nil {
		return false
	}
	_, _, err := a.vault.Load()
	return err == nil
}

// Snapshot fields for /api/app/pairing.
func (a *AutoRepairer) Snapshot() map[string]any {
	if a == nil {
		return nil
	}
	return map[string]any{
		"auto_repair_enabled": a.Enabled(),
		"session_saved":       a.HasSavedSession(),
		"sign_in_needed":      a.SignInNeeded(),
	}
}

// Run polls Google status until ctx is done.
func (a *AutoRepairer) Run(ctxDone <-chan struct{}) {
	if a == nil {
		return
	}
	t := time.NewTicker(autoRepairPoll)
	defer t.Stop()
	for {
		a.tick()
		select {
		case <-ctxDone:
			return
		case <-t.C:
		}
	}
}

func (a *AutoRepairer) tick() {
	a.maybeRefreshVaultFromSession()
	a.maybeNotifyEmoji()
	if !a.Enabled() {
		return
	}
	a.mu.Lock()
	if a.signInNeeded {
		a.mu.Unlock()
		return
	}
	a.mu.Unlock()

	if !a.authExpired() {
		return
	}
	st := a.pairer.Status()
	if st.State == PairStarting || st.State == PairShowEmoji {
		return
	}
	if !a.HasSavedSession() {
		a.mu.Lock()
		a.signInNeeded = true
		a.mu.Unlock()
		a.log.Warn().Msg("Google auth expired and no saved session; sign-in required")
		a.alert("Google Messages", "Sign in again to reconnect Google Messages.", "google-signin")
		return
	}

	a.mu.Lock()
	if !a.lastAttempt.IsZero() && time.Since(a.lastAttempt) < autoRepairMinGap {
		a.mu.Unlock()
		return
	}
	a.lastAttempt = time.Now()
	a.mu.Unlock()

	a.log.Info().Msg("Google auth expired; starting automatic Gaia re-pair from saved session")
	if err := a.pairer.Start(); err != nil {
		if !strings.Contains(err.Error(), "already in progress") {
			a.log.Warn().Str("error_kind", "start_failed").Msg("Auto re-pair could not start")
		}
		return
	}
	// Watch for terminal failure in the background (pairer owns the work).
	go a.watchAttempt()
}

func (a *AutoRepairer) watchAttempt() {
	deadline := time.Now().Add(6 * time.Minute)
	for time.Now().Before(deadline) {
		time.Sleep(2 * time.Second)
		st := a.pairer.Status()
		switch st.State {
		case PairSuccess, PairIdle:
			a.mu.Lock()
			a.signInNeeded = false
			a.notifiedEmoji = ""
			a.mu.Unlock()
			return
		case PairError:
			kind := st.Message
			a.log.Warn().Str("message", kind).Msg("Auto re-pair failed")
			if looksLikeRejectedSession(kind) {
				a.mu.Lock()
				a.signInNeeded = true
				a.mu.Unlock()
				a.alert("Google Messages", "Saved Google sign-in was rejected. Sign in again in the app.", "google-signin")
			}
			return
		case PairStarting, PairShowEmoji:
			continue
		default:
			continue
		}
	}
}

func looksLikeRejectedSession(msg string) bool {
	m := strings.ToLower(msg)
	return strings.Contains(m, "cookie") ||
		strings.Contains(m, "auth") ||
		strings.Contains(m, "credential") ||
		strings.Contains(m, "401") ||
		strings.Contains(m, "no google cookies") ||
		strings.Contains(m, "pairing failed")
}

func (a *AutoRepairer) authExpired() bool {
	if a.google == nil {
		return false
	}
	b, err := json.Marshal(a.google())
	if err != nil {
		return false
	}
	var s struct {
		AuthExpired  bool   `json:"auth_expired"`
		NeedsRepair  bool   `json:"needs_repair"`
		NeedsPairing bool   `json:"needs_pairing"`
		Connected    bool   `json:"connected"`
		LastError    string `json:"last_error"`
	}
	if json.Unmarshal(b, &s) != nil {
		return false
	}
	if s.AuthExpired {
		return true
	}
	if s.NeedsRepair && !s.Connected {
		return true
	}
	if strings.Contains(strings.ToLower(s.LastError), "auth expired") ||
		strings.Contains(strings.ToLower(s.LastError), "session cookie") {
		return true
	}
	return false
}

func (a *AutoRepairer) maybeNotifyEmoji() {
	st := a.pairer.Status()
	if st.State != PairShowEmoji || st.Emoji == "" {
		return
	}
	a.mu.Lock()
	if a.notifiedEmoji == st.Emoji {
		a.mu.Unlock()
		return
	}
	a.notifiedEmoji = st.Emoji
	a.mu.Unlock()
	body := "Confirm on your phone: tap " + st.Emoji + " in Google Messages."
	a.alert("Confirm on your phone", body, "google-pair-emoji")
}

func (a *AutoRepairer) alert(title, body, tag string) {
	if a.push != nil {
		a.push.SendAlert(title, body, tag)
	}
}

// maybeRefreshVaultFromSession copies rotated Google cookies from session.json
// into the encrypted vault so auto re-pair stays fresh. Never logs values.
func (a *AutoRepairer) maybeRefreshVaultFromSession() {
	if a.sessionPath == "" || a.vault == nil {
		return
	}
	raw, err := os.ReadFile(a.sessionPath)
	if err != nil {
		return
	}
	var data map[string]any
	if json.Unmarshal(raw, &data) != nil {
		return
	}
	auth, _ := data["auth_data"].(map[string]any)
	if auth == nil {
		return
	}
	cRaw, ok := auth["cookies"]
	if !ok {
		return
	}
	b, err := json.Marshal(cRaw)
	if err != nil {
		return
	}
	cookies, missing, err := ParseGoogleCookies(string(b))
	if err != nil || len(missing) > 0 || len(cookies) == 0 {
		return
	}
	// Only rewrite when we already have a vault (or after first manual save).
	if _, _, err := a.vault.Load(); err != nil {
		if !errors.Is(err, ErrNoCookiesStored) {
			return
		}
		// Don't create a vault from session unless one existed — pairing
		// flow creates it. Skip.
		return
	}
	_ = a.vault.Save(cookies)
}
