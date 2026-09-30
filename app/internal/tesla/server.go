package tesla

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
	"strings"
	"time"

	"github.com/rs/zerolog"
)

//go:embed static
var staticFS embed.FS

// Deps are the pieces of the running OpenMessage server the Tesla layer needs.
type Deps struct {
	DataDir      string
	SessionPath  string
	Inner        http.Handler // OpenMessage's own web/API handler
	InnerHost    string       // loopback host:port the inner handler expects, e.g. 127.0.0.1:7007
	Reconnect    func() error // reconnect Google Messages after a new session is saved
	GoogleStatus func() any
	Logger       zerolog.Logger
	// PairingRunner overrides the real Google pairing (tests).
	PairingRunner PairingRunner
	// Transcriber overrides the configured STT provider (tests).
	Transcriber Transcriber
	// Car backs the message menu, Start chat and folder endpoints (nil = 503).
	Car CarBackend
	// Typing holds live typing state for GET /api/tesla/typing (nil = none).
	Typing *TypingTracker
}

type Server struct {
	cfg    Config
	deps   Deps
	auth   *Auth
	stt    Transcriber
	vault  *CookieVault
	pairer *Pairer
	admin  *template.Template
	static http.Handler
}

// NewHandler builds the public-facing handler: login + auth gate in front of
// the Tesla UI, the Tesla APIs, and (proxied in-process) OpenMessage's API.
func NewHandler(cfg Config, d Deps) (http.Handler, *Server, error) {
	stt := d.Transcriber
	if stt == nil {
		var err error
		stt, err = NewTranscriber(cfg)
		if err != nil {
			return nil, nil, err
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
		cfg:    cfg,
		deps:   d,
		auth:   NewAuth(cfg.Secret, cfg.SessionTTL, cfg.CookieSecure),
		stt:    stt,
		vault:  vault,
		pairer: NewPairer(vault, runner, cfg.FakePairing, d.Reconnect, cfg.NtfyURL, d.Logger),
		admin:  template.Must(template.New("admin").Parse(adminCookiesHTML)),
		static: http.StripPrefix("/tesla/", http.FileServer(http.FS(sub))),
	}

	protected := http.NewServeMux()
	protected.HandleFunc("/tesla/", s.serveStatic)
	protected.HandleFunc("/api/transcribe", s.handleTranscribe)
	protected.HandleFunc("/api/tesla/config", s.handleConfig)
	protected.HandleFunc("/api/tesla/pairing", s.handlePairingStatus)
	protected.HandleFunc("/api/tesla/pairing/start", s.handlePairingStart)
	protected.HandleFunc("/api/tesla/pairing/cancel", s.handlePairingCancel)
	protected.HandleFunc("/admin/cookies", s.handleAdminCookies)
	protected.HandleFunc("/admin/cookies/clear", s.handleAdminCookiesClear)
	s.registerCarRoutes(protected)
	protected.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/" {
			http.Redirect(w, r, "/tesla/", http.StatusFound)
			return
		}
		s.proxyInner(w, r)
	})

	root := http.NewServeMux()
	root.HandleFunc("/login", s.auth.HandleLogin)
	root.HandleFunc("/logout", s.auth.HandleLogout)
	root.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte("ok")) })
	root.HandleFunc("/tesla/app.css", s.serveStatic) // login page styling; no secrets
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
	s.deps.Inner.ServeHTTP(w, r2)
}

func setPageSecurityHeaders(w http.ResponseWriter) {
	w.Header().Set("Content-Security-Policy",
		"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "+
			"img-src 'self' data: blob: https://fonts.gstatic.com; media-src 'self' blob:; "+
			"connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Referrer-Policy", "same-origin")
	w.Header().Set("Permissions-Policy", "microphone=(self), camera=()")
}

func (s *Server) serveStatic(w http.ResponseWriter, r *http.Request) {
	setPageSecurityHeaders(w)
	w.Header().Set("Cache-Control", "no-cache")
	s.static.ServeHTTP(w, r)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// sttMode is the effective TESLA_STT_MODE (Config built by hand in tests may
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

func (s *Server) handleConfig(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]any{
		"stt_mode":        s.sttMode(),
		"stt_provider":    s.stt.Name(),
		"stt_label":       ProviderLabel(s.stt.Name()),
		"stt_enabled":     s.serverSTTEnabled(), // server-side STT usable (kept for older clients)
		"stt_language":    s.cfg.STTLanguage,
		"max_record_secs": s.cfg.MaxRecordSecs,
		"max_audio_bytes": s.cfg.MaxAudioBytes,
		"fake_pairing":    s.cfg.FakePairing != "",
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
		writeJSON(w, 503, map[string]string{"error": "server speech-to-text is turned off (TESLA_STT_MODE=builtin)"})
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
<title>Tesla Messages · Google cookies</title>
<link rel="stylesheet" href="/tesla/app.css">
</head><body class="admin-body">
<main class="admin-card">
  <h1>Google cookies</h1>
  <p>Paste cookies from a <b>private/incognito</b> desktop window signed in at
  <code>accounts.google.com/AccountChooser?continue=https://messages.google.com/web/config</code>.
  Accepted: a JSON object <code>{"SID":"…",…}</code>, a devtools “Copy as cURL” of the
  <code>/web/config</code> request, or a raw <code>Cookie:</code> header.</p>
  <p>Required: <code>{{.Required}}</code>. Stored encrypted (AES-GCM, key derived from TESLA_SECRET), file mode 0600. Values are never shown or logged.</p>
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
    <a class="btn btn-primary" href="/tesla/#pair">Go to pairing</a>
    <form method="post" action="/admin/cookies/clear"><button class="btn btn-danger" type="submit">Delete stored cookies</button></form>
  </div>
  {{end}}
  <p><a href="/tesla/">← Back to messages</a></p>
</main>
</body></html>`
