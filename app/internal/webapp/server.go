package webapp

import (
	"context"
	"embed"
	"encoding/json"
	"errors"
	"html/template"
	"io"
	"io/fs"
	"mime"
	"net/http"
	"path"
	"strings"
	"time"

	"github.com/rs/zerolog"
)

//go:embed static
var staticFS embed.FS

// Deps are the pieces of the running OpenMessage server the web app layer needs.
type Deps struct {
	DataDir     string
	SessionPath string
	Inner       http.Handler // OpenMessage's own web/API handler
	InnerHost   string       // loopback host:port the inner handler expects, e.g. 127.0.0.1:7007
	// InnerAuthorize stamps proxied (already logged-in) requests with the
	// inner control credential. Client-sent Authorization is always dropped.
	InnerAuthorize func(*http.Request)
	Reconnect      func() error // reconnect Google Messages after a new session is saved
	GoogleStatus   func() any
	Logger         zerolog.Logger
	// PairingRunner overrides the real Google pairing (tests).
	PairingRunner PairingRunner
	// Transcriber overrides the configured STT provider (tests).
	Transcriber Transcriber
	// Partial overrides the live-typing engine (tests). When Transcriber is
	// set and Partial isn't, Transcriber is used if it implements it.
	Partial PartialTranscriber
	// Car backs the message menu, Start chat and folder endpoints (nil = 503).
	Car CarBackend
	// Typing holds live typing state for GET /api/app/typing (nil = none).
	Typing *TypingTracker
	// Push sends Web Push notifications for incoming messages (nil = off).
	Push *PushHub
	// Profile serves the header's Google account photo (nil = none).
	Profile ProfilePhotoSource
	// NoHealthMonitor skips starting the background health watcher (tests).
	NoHealthMonitor bool
}

type Server struct {
	cfg  Config
	deps Deps
	auth *Auth
	stt  Transcriber
	// partial is the live-typing engine (nil: use stt if it implements
	// PartialTranscriber); liveGate guards /api/transcribe/partial.
	partial  PartialTranscriber
	liveGate *partialGate
	vault    *CookieVault
	pairer   *Pairer
	admin    *template.Template
	static   http.Handler
	themes   *ThemeStore // nil if the data dir couldn't be prepared
	health   *HealthMonitor
	stars    *StarStore // nil if the data dir couldn't be prepared
	version  string     // hash of the static files (version.go)
	index    []byte     // index.html with versioned asset URLs
}

// NewHandler builds the public-facing handler: login + auth gate in front of
// the web app UI, the web app APIs, and (proxied in-process) OpenMessage's API.
func NewHandler(cfg Config, d Deps) (http.Handler, *Server, error) {
	stt := d.Transcriber
	partial := d.Partial
	if stt == nil {
		var err error
		stt, err = NewTranscriber(cfg)
		if err != nil {
			return nil, nil, err
		}
		if partial == nil {
			partial = NewPartialTranscriber(cfg)
		}
	}
	vault := NewCookieVault(d.DataDir, cfg.Secret)
	runner := d.PairingRunner
	if runner == nil {
		runner = OpenMessageGooglePairing(d.Logger, d.SessionPath)
	}
	sub, err := fs.Sub(staticFS, "static")
	if err != nil {
		return nil, nil, err
	}
	s := &Server{
		cfg:      cfg,
		deps:     d,
		auth:     NewAuth(cfg.Secret, cfg.SessionTTL, cfg.CookieSecure),
		stt:      stt,
		partial:  partial,
		liveGate: newPartialGate(),
		vault:    vault,
		pairer:   NewPairer(vault, runner, cfg.FakePairing, d.Reconnect, cfg.NtfyURL, d.Logger),
		admin:    template.Must(template.New("admin").Parse(adminCookiesHTML)),
		static:   http.StripPrefix("/app/", http.FileServer(http.FS(sub))),
	}

	s.version = staticVersion(sub)
	s.index = versionedIndex(sub, s.version)

	s.health = NewHealthMonitor(cfg, d.GoogleStatus, d.Push)
	if s.health.Enabled() && !d.NoHealthMonitor {
		go s.health.Run(context.Background())
	}

	if st, err := OpenStarStore(d.DataDir); err == nil {
		s.stars = st
	}
	if ts, err := OpenThemeStore(d.DataDir); err != nil {
		d.Logger.Warn().Err(err).Msg("Chat theme storage unavailable")
	} else {
		s.themes = ts
	}

	protected := http.NewServeMux()
	protected.HandleFunc("/app/", s.serveStatic)
	protected.HandleFunc("/api/transcribe", s.handleTranscribe)
	protected.HandleFunc("/api/transcribe/partial", s.handleTranscribePartial)
	protected.HandleFunc("/api/app/config", s.handleConfig)
	protected.HandleFunc("/api/app/pairing", s.handlePairingStatus)
	protected.HandleFunc("/api/app/pairing/start", s.handlePairingStart)
	protected.HandleFunc("/api/app/pairing/cancel", s.handlePairingCancel)
	protected.HandleFunc("/admin/cookies", s.handleAdminCookies)
	protected.HandleFunc("/admin/cookies/clear", s.handleAdminCookiesClear)
	s.registerCarRoutes(protected)
	s.registerThemeRoutes(protected)
	s.registerPushRoutes(protected)
	protected.HandleFunc("/api/app/health", s.handleHealth)
	protected.HandleFunc("/api/app/stars", s.handleStars)
	protected.HandleFunc("/api/app/version", s.handleVersion)
	protected.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		// The bare domain, and any page address that isn't the web app's, go
		// to the app's start path instead of a blank page. API and asset
		// requests still reach the inner server.
		if r.URL.Path == "/" || isPageNavigation(r) {
			http.Redirect(w, r, "/app/", http.StatusFound)
			return
		}
		s.proxyInner(w, r)
	})

	root := http.NewServeMux()
	root.HandleFunc("/login", s.auth.HandleLogin)
	root.HandleFunc("/logout", s.auth.HandleLogout)
	root.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte("ok")) })
	root.HandleFunc("/app/app.css", s.serveStatic)  // login page styling; no secrets
	root.HandleFunc("/app/login.js", s.serveStatic) // login page keyboard lift; no secrets
	root.HandleFunc("/app/fonts/", s.serveFont)     // bundled Noto Color Emoji (public font files)
	registerLegacyRoutes(root)
	root.HandleFunc("/.well-known/assetlinks.json", handleAssetLinks) // Android app (TWA) link; public
	s.registerPWARoutes(root)                                         // manifest, service worker, icons, offline page (no private data)
	root.Handle("/", s.auth.Require(protected))
	return root, s, nil
}

func (s *Server) Pairer() *Pairer     { return s.pairer }
func (s *Server) Vault() *CookieVault { return s.vault }

// proxyInner hands an already-authenticated request to OpenMessage's handler.
// OpenMessage only accepts loopback Host/Origin (it was built for localhost);
// our gate has already enforced the login cookie and a same-origin check on
// writes, so present the request as local.
func (s *Server) proxyInner(w http.ResponseWriter, r *http.Request) {
	r2 := r.Clone(r.Context())
	if s.deps.InnerHost != "" {
		r2.Host = s.deps.InnerHost
	}
	r2.Header.Del("Origin")
	r2.Header.Del("Referer")
	r2.Header.Del("Sec-Fetch-Site")
	r2.Header.Del("Authorization")
	if s.deps.InnerAuthorize != nil {
		s.deps.InnerAuthorize(r2)
	}
	s.deps.Inner.ServeHTTP(w, r2)
}

func setPageSecurityHeaders(w http.ResponseWriter) {
	w.Header().Set("Content-Security-Policy",
		"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "+
			"img-src 'self' data: blob: https://fonts.gstatic.com; media-src 'self' blob:; "+
			"connect-src 'self' blob: data:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Referrer-Policy", "same-origin")
	w.Header().Set("Permissions-Policy", "microphone=(self), camera=()")
}

func (s *Server) serveStatic(w http.ResponseWriter, r *http.Request) {
	setPageSecurityHeaders(w)
	w.Header().Set("Cache-Control", "no-cache")
	if (r.URL.Path == "/app/" || r.URL.Path == "/app/index.html") && len(s.index) > 0 {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("X-App-Version", s.version)
		_, _ = w.Write(s.index)
		return
	}
	s.static.ServeHTTP(w, r)
}

// serveFont serves the bundled emoji font files (public, like any web
// font). The .woff2 chunks never change under the same name, so they get a
// one-year immutable cache; the CSS and license revalidate.
func (s *Server) serveFont(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(r.URL.Path, "/app/fonts/")
	if name == "" || strings.Contains(name, "/") {
		http.NotFound(w, r)
		return
	}
	switch path.Ext(name) {
	case ".woff2":
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		w.Header().Set("Content-Type", "font/woff2")
	case ".css", ".txt":
		w.Header().Set("Cache-Control", "no-cache")
	default:
		http.NotFound(w, r)
		return
	}
	w.Header().Set("X-Content-Type-Options", "nosniff")
	s.static.ServeHTTP(w, r)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// sttMode is the effective MESSAGES_STT_MODE (Config built by hand in tests may
// leave it empty).
func (s *Server) sttMode() string {
	m, err := NormalizeSTTMode(s.cfg.STTMode)
	if err != nil {
		return STTModeAuto
	}
	return m
}

// serverSTTEnabled reports whether /api/transcribe will accept clips.
func (s *Server) serverSTTEnabled() bool {
	return s.stt.Name() != "none" && s.sttMode() != STTModeBuiltin
}

func cloudSTT(name string) bool {
	return strings.HasPrefix(name, "groq:") || strings.HasPrefix(name, "openai:")
}

// sttModel is the configured model name (label only for whisper.cpp).
func (s *Server) sttModel() string {
	if t, ok := s.stt.(*OpenAICompatTranscriber); ok {
		return t.Model
	}
	return ""
}

func (s *Server) handleConfig(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]any{
		"stt_mode":     s.sttMode(),
		"stt_provider": s.stt.Name(),
		"stt_label":    ProviderLabel(s.stt.Name()),
		"stt_model":    s.sttModel(),
		"stt_enabled":  s.serverSTTEnabled(), // server-side STT usable (kept for older clients)
		// Auto mode with a cloud Whisper (Groq/OpenAI): record and send to the
		// server first, the browser's own speech is the fallback.
		"stt_prefer_server": s.sttMode() == STTModeAuto && s.serverSTTEnabled() && cloudSTT(s.stt.Name()),
		"stt_language":      s.cfg.STTLanguage,
		"stt_live":          s.liveSTTEnabled(), // /api/transcribe/partial (live typing) usable
		"stt_live_max_secs": MaxPartialSecs,
		"stt_live_step_ms":  s.liveStepMS(), // how often the page sends a live window
		"max_record_secs":   s.cfg.MaxRecordSecs,
		"max_audio_bytes":   s.cfg.MaxAudioBytes,
		"fake_pairing":      s.cfg.FakePairing != "",
	})
}

// handleTranscribe accepts either a raw audio body (Content-Type audio/*) or
// multipart/form-data with the clip in field "file" (or "audio"), and returns
// {"text": "..."} for the client to put in the compose box. Never auto-sends.
func (s *Server) handleTranscribe(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]string{"error": "method not allowed"})
		return
	}
	if s.stt.Name() == "none" {
		writeJSON(w, 503, map[string]string{"error": ErrSTTDisabled.Error()})
		return
	}
	if s.sttMode() == STTModeBuiltin {
		writeJSON(w, 503, map[string]string{"error": "server speech-to-text is turned off (MESSAGES_STT_MODE=builtin)"})
		return
	}
	limit := s.cfg.MaxAudioBytes
	r.Body = http.MaxBytesReader(w, r.Body, limit+64<<10)
	ct := r.Header.Get("Content-Type")
	var audio []byte
	var audioType string
	var err error
	if mt, _, _ := mime.ParseMediaType(ct); mt == "multipart/form-data" {
		if err = r.ParseMultipartForm(limit); err == nil {
			file, hdr, ferr := r.FormFile("file")
			if ferr != nil {
				file, hdr, ferr = r.FormFile("audio")
			}
			if ferr != nil {
				writeJSON(w, 400, map[string]string{"error": "multipart field 'file' is required"})
				return
			}
			defer file.Close()
			audioType = hdr.Header.Get("Content-Type")
			audio, err = io.ReadAll(file)
		}
	} else {
		audioType = ct
		audio, err = io.ReadAll(r.Body)
	}
	if err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) || int64(len(audio)) > limit {
			writeJSON(w, 413, map[string]string{"error": "recording too large"})
			return
		}
		writeJSON(w, 400, map[string]string{"error": "could not read audio"})
		return
	}
	if int64(len(audio)) > limit {
		writeJSON(w, 413, map[string]string{"error": "recording too large"})
		return
	}
	if len(audio) == 0 {
		writeJSON(w, 400, map[string]string{"error": "empty recording"})
		return
	}
	if !allowedAudioMime(audioType) {
		writeJSON(w, 415, map[string]string{"error": "unsupported audio type " + baseMime(audioType)})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 60*time.Second)
	defer cancel()
	start := time.Now()
	text, err := s.stt.Transcribe(ctx, audio, audioType)
	elapsed := time.Since(start)
	if err != nil {
		s.deps.Logger.Warn().Str("provider", s.stt.Name()).Int("bytes", len(audio)).Dur("took", elapsed).Err(err).Msg("Transcription failed")
		writeJSON(w, 502, map[string]string{"error": "transcription failed: " + err.Error()})
		return
	}
	// Log sizes only, never the transcript.
	s.deps.Logger.Info().Str("provider", s.stt.Name()).Int("bytes", len(audio)).Int("chars", len(text)).Dur("took", elapsed).Msg("Transcribed voice clip")
	writeJSON(w, 200, map[string]any{"text": text, "provider": s.stt.Name(), "label": ProviderLabel(s.stt.Name()), "ms": elapsed.Milliseconds()})
}

func (s *Server) pairingPayload() map[string]any {
	st := s.pairer.Status()
	out := map[string]any{"pairing": st}
	_, saved, err := s.vault.Load()
	out["cookies_saved"] = err == nil
	if err == nil {
		out["cookies_saved_at"] = saved.UnixMilli()
	}
	if s.deps.GoogleStatus != nil {
		out["google"] = s.deps.GoogleStatus()
	}
	return out
}

func (s *Server) handlePairingStatus(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, s.pairingPayload())
}

func (s *Server) handlePairingStart(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]string{"error": "method not allowed"})
		return
	}
	if err := s.pairer.Start(); err != nil {
		writeJSON(w, 409, map[string]any{"error": err.Error(), "pairing": s.pairer.Status()})
		return
	}
	writeJSON(w, 202, s.pairingPayload())
}

func (s *Server) handlePairingCancel(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]string{"error": "method not allowed"})
		return
	}
	s.pairer.Cancel()
	writeJSON(w, 200, s.pairingPayload())
}

func (s *Server) handleAdminCookies(w http.ResponseWriter, r *http.Request) {
	data := map[string]any{"Required": strings.Join(RequiredGoogleCookies, ", ")}
	status := 200
	switch r.Method {
	case http.MethodGet:
	case http.MethodPost:
		r.Body = http.MaxBytesReader(w, r.Body, 256<<10)
		cookies, missing, err := ParseGoogleCookies(r.PostFormValue("cookies"))
		switch {
		case err != nil:
			data["Error"] = err.Error()
			status = 400
		case len(missing) > 0:
			data["Error"] = "Missing required cookies: " + strings.Join(missing, ", ") + ". Nothing was saved."
			status = 400
		default:
			if err := s.vault.Save(cookies); err != nil {
				data["Error"] = "Could not save: " + err.Error()
				status = 500
			} else {
				data["Saved"] = strings.Join(cookieNames(cookies), ", ")
				s.deps.Logger.Info().Int("count", len(cookies)).Msg("Google cookies saved to encrypted vault")
			}
		}
	default:
		http.Error(w, "method not allowed", 405)
		return
	}
	if c, saved, err := s.vault.Load(); err == nil {
		data["Stored"] = strings.Join(cookieNames(c), ", ")
		data["StoredAt"] = saved.Format(time.RFC1123)
	} else if !errors.Is(err, ErrNoCookiesStored) {
		data["VaultError"] = err.Error()
	}
	setPageSecurityHeaders(w)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = s.admin.Execute(w, data)
}

func (s *Server) handleAdminCookiesClear(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", 405)
		return
	}
	if err := s.vault.Clear(); err != nil {
		http.Error(w, "clear failed", 500)
		return
	}
	http.Redirect(w, r, "/admin/cookies", http.StatusSeeOther)
}

const adminCookiesHTML = `<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Messages Enhanced · Google cookies</title>
<link rel="stylesheet" href="/app/app.css">
</head><body class="admin-body">
<main class="admin-card">
  <h1>Google cookies</h1>
  <p>Paste cookies from a <b>private/incognito</b> desktop window signed in at
  <code>accounts.google.com/AccountChooser?continue=https://messages.google.com/web/config</code>.
  Accepted: a JSON object <code>{"SID":"…",…}</code>, a devtools “Copy as cURL” of the
  <code>/web/config</code> request, or a raw <code>Cookie:</code> header.</p>
  <p>Required: <code>{{.Required}}</code>. Stored encrypted (AES-GCM, key derived from MESSAGES_SECRET), file mode 0600. Values are never shown or logged.</p>
  {{if .Error}}<p class="login-error" role="alert">{{.Error}}</p>{{end}}
  {{if .Saved}}<p class="admin-ok" role="status">Saved: {{.Saved}}</p>{{end}}
  {{if .VaultError}}<p class="login-error">{{.VaultError}}</p>{{end}}
  <form method="post" action="/admin/cookies" autocomplete="off">
    <textarea name="cookies" rows="8" spellcheck="false" placeholder='{"SID":"…","HSID":"…","SSID":"…","OSID":"…","APISID":"…","SAPISID":"…"}'></textarea>
    <button type="submit" class="btn btn-primary">Save cookies</button>
  </form>
  {{if .Stored}}
  <p>Currently stored ({{.StoredAt}}): <code>{{.Stored}}</code></p>
  <div class="admin-actions">
    <a class="btn btn-primary" href="/app/#pair">Go to pairing</a>
    <form method="post" action="/admin/cookies/clear"><button class="btn btn-danger" type="submit">Delete stored cookies</button></form>
  </div>
  {{end}}
  <p><a href="/app/">← Back to messages</a></p>
</main>
</body></html>`

// isPageNavigation reports a browser loading a page (not fetch/XHR or an
// asset) for a path outside the web app and the inner server's API.
func isPageNavigation(r *http.Request) bool {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		return false
	}
	p := r.URL.Path
	if strings.HasPrefix(p, "/api/") || strings.HasPrefix(p, "/app/") {
		return false
	}
	if mode := r.Header.Get("Sec-Fetch-Mode"); mode != "" {
		return mode == "navigate"
	}
	// Older browsers (car): no Fetch Metadata. A page load asks for HTML;
	// extensions mark assets.
	if path.Ext(p) != "" {
		return false
	}
	return strings.Contains(r.Header.Get("Accept"), "text/html")
}
