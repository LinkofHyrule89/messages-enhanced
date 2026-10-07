// Package webapp adds the "Messages Enhanced" layer on top of OpenMessage: a
// car-friendly web UI, a shared-secret login, server-side speech-to-text for
// the mic button, an encrypted Google-cookie vault and a minimal web pairing
// screen that shows the Google-account pairing emoji.
package webapp

import (
	"errors"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config is read from the environment. The web app is enabled only when
// MESSAGES_SECRET is set.
type Config struct {
	// MCPToken guards the remote MCP connector at /mcp (MESSAGES_MCP_TOKEN;
	// shorter than 32 characters or unset = /mcp is off).
	MCPToken string
	// PublicURL overrides the public origin used in OAuth metadata
	// (MESSAGES_PUBLIC_URL, e.g. https://messages.example.com; default: the
	// request's Host).
	PublicURL      string
	Secret         string        // MESSAGES_SECRET: login secret (>= 16 chars)
	SessionTTL     time.Duration // MESSAGES_SESSION_DAYS (default 30)
	CookieSecure   string        // MESSAGES_COOKIE_SECURE: "auto" (default), "1", "0"
	STTProvider    string        // MESSAGES_STT_PROVIDER: whisper (local whisper.cpp) | openai | groq | fake | none
	STTMode        string        // MESSAGES_STT_MODE: auto (default: browser speech, then server) | builtin | server
	STTLanguage    string        // MESSAGES_STT_LANGUAGE: optional ISO-639-1 hint, e.g. "en"
	STTPrompt      string        // MESSAGES_STT_PROMPT: optional vocabulary hint
	MaxAudioBytes  int64         // MESSAGES_STT_MAX_BYTES (default 10 MiB)
	MaxRecordSecs  int           // MESSAGES_STT_MAX_SECONDS (default 60), enforced client-side
	OpenAIKey      string        // OPENAI_API_KEY
	OpenAIModel    string        // MESSAGES_OPENAI_MODEL (default gpt-4o-mini-transcribe; or whisper-1)
	OpenAIBaseURL  string        // MESSAGES_OPENAI_BASE_URL (default https://api.openai.com/v1)
	GroqKey        string        // GROQ_API_KEY
	GroqModel      string        // MESSAGES_GROQ_MODEL (default whisper-large-v3-turbo)
	GroqBaseURL    string        // MESSAGES_GROQ_BASE_URL (default https://api.groq.com/openai/v1; e.g. a loopback stt-proxy)
	WhisperURL     string        // MESSAGES_WHISPER_URL: whisper.cpp whisper-server inference URL (default http://127.0.0.1:8178/inference)
	WhisperModel   string        // MESSAGES_WHISPER_MODEL: label only, e.g. base.en-q8_0 (the server picks the model)
	WhisperLiveURL string        // MESSAGES_WHISPER_LIVE_URL: whisper-server for live-typing passes (WAV in, no --convert needed; default MESSAGES_WHISPER_URL)
	LiveDisabled   bool          // MESSAGES_STT_LIVE=0 turns off live typing (/api/transcribe/partial)
	FakeTranscript string        // MESSAGES_FAKE_TRANSCRIPT: canned text for the fake provider
	FakePairing    string        // MESSAGES_DEV_FAKE_PAIRING: dev only; emoji to show instead of contacting Google
	NtfyURL        string        // MESSAGES_NTFY_URL: optional, e.g. https://ntfy.sh/<private-topic>
	// Health alerts (Web Push + in-app banner) when Google or the VPN is down
	// for HealthAfter; see health.go.
	HealthOff      bool          // MESSAGES_HEALTH=0 turns the monitor off
	HealthAfter    time.Duration // MESSAGES_HEALTH_AFTER_SECS (default 180)
	HealthVPNIface string        // MESSAGES_HEALTH_VPN_IFACE: e.g. wg0; down if the interface is gone
	HealthExitIP   string        // MESSAGES_HEALTH_EXIT_IP: expected public IP (VPN exit); down if it differs or can't be fetched
	HealthIPURL    string        // MESSAGES_HEALTH_IP_URL: IP echo service (default https://ifconfig.me/ip)
}

func Enabled() bool { return strings.TrimSpace(Getenv("MESSAGES_SECRET")) != "" }

func ConfigFromEnv() (Config, error) {
	c := Config{
		Secret:         strings.TrimSpace(Getenv("MESSAGES_SECRET")),
		MCPToken:       strings.TrimSpace(Getenv("MESSAGES_MCP_TOKEN")),
		PublicURL:      strings.TrimRight(strings.TrimSpace(Getenv("MESSAGES_PUBLIC_URL")), "/"),
		SessionTTL:     time.Duration(envInt("MESSAGES_SESSION_DAYS", 30)) * 24 * time.Hour,
		CookieSecure:   firstNonEmpty(strings.ToLower(strings.TrimSpace(Getenv("MESSAGES_COOKIE_SECURE"))), "auto"),
		STTProvider:    strings.ToLower(strings.TrimSpace(Getenv("MESSAGES_STT_PROVIDER"))),
		STTMode:        strings.ToLower(strings.TrimSpace(Getenv("MESSAGES_STT_MODE"))),
		STTLanguage:    strings.TrimSpace(Getenv("MESSAGES_STT_LANGUAGE")),
		STTPrompt:      Getenv("MESSAGES_STT_PROMPT"),
		MaxAudioBytes:  int64(envInt("MESSAGES_STT_MAX_BYTES", 10<<20)),
		MaxRecordSecs:  envInt("MESSAGES_STT_MAX_SECONDS", 60),
		OpenAIKey:      strings.TrimSpace(os.Getenv("OPENAI_API_KEY")),
		OpenAIModel:    strings.TrimSpace(Getenv("MESSAGES_OPENAI_MODEL")),
		OpenAIBaseURL:  strings.TrimRight(strings.TrimSpace(Getenv("MESSAGES_OPENAI_BASE_URL")), "/"),
		GroqKey:        strings.TrimSpace(os.Getenv("GROQ_API_KEY")),
		GroqModel:      strings.TrimSpace(Getenv("MESSAGES_GROQ_MODEL")),
		GroqBaseURL:    strings.TrimRight(strings.TrimSpace(Getenv("MESSAGES_GROQ_BASE_URL")), "/"),
		WhisperURL:     strings.TrimSpace(Getenv("MESSAGES_WHISPER_URL")),
		WhisperModel:   strings.TrimSpace(Getenv("MESSAGES_WHISPER_MODEL")),
		WhisperLiveURL: strings.TrimSpace(Getenv("MESSAGES_WHISPER_LIVE_URL")),
		LiveDisabled:   envOff("MESSAGES_STT_LIVE"),
		FakeTranscript: Getenv("MESSAGES_FAKE_TRANSCRIPT"),
		FakePairing:    strings.TrimSpace(Getenv("MESSAGES_DEV_FAKE_PAIRING")),
		NtfyURL:        strings.TrimSpace(Getenv("MESSAGES_NTFY_URL")),
		HealthOff:      envOff("MESSAGES_HEALTH"),
		HealthAfter:    time.Duration(envInt("MESSAGES_HEALTH_AFTER_SECS", 180)) * time.Second,
		HealthVPNIface: strings.TrimSpace(Getenv("MESSAGES_HEALTH_VPN_IFACE")),
		HealthExitIP:   strings.TrimSpace(Getenv("MESSAGES_HEALTH_EXIT_IP")),
		HealthIPURL:    firstNonEmpty(strings.TrimSpace(Getenv("MESSAGES_HEALTH_IP_URL")), "https://ifconfig.me/ip"),
	}
	// A Groq key alone picks Groq Whisper as the server speech-to-text.
	if c.STTProvider == "" && c.GroqKey != "" {
		c.STTProvider = "groq"
	}
	if len(c.Secret) < 16 {
		return c, errors.New("MESSAGES_SECRET must be at least 16 characters (use a long random string)")
	}
	mode, err := NormalizeSTTMode(c.STTMode)
	if err != nil {
		return c, err
	}
	c.STTMode = mode
	if c.SessionTTL <= 0 {
		c.SessionTTL = 30 * 24 * time.Hour
	}
	if c.MaxAudioBytes <= 0 {
		c.MaxAudioBytes = 10 << 20
	}
	if c.HealthAfter <= 0 {
		c.HealthAfter = 3 * time.Minute
	}
	if c.MaxRecordSecs <= 0 {
		c.MaxRecordSecs = 60
	}
	return c, nil
}

// STT modes for the mic button (MESSAGES_STT_MODE).
const (
	STTModeAuto    = "auto"    // try the browser's Web Speech API, fall back to server STT
	STTModeBuiltin = "builtin" // Web Speech API only; /api/transcribe is disabled
	STTModeServer  = "server"  // always record and POST to /api/transcribe
)

// NormalizeSTTMode maps MESSAGES_STT_MODE values (and a few aliases) to one of
// the STTMode constants. Empty means auto.
func NormalizeSTTMode(v string) (string, error) {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "", "auto", "default":
		return STTModeAuto, nil
	case "builtin", "built-in", "browser", "webspeech":
		return STTModeBuiltin, nil
	case "server":
		return STTModeServer, nil
	default:
		return "", errors.New("unknown MESSAGES_STT_MODE " + strconv.Quote(v) + " (want auto, builtin, or server)")
	}
}

// envOff is true when name is set to 0/false/off/no.
func envOff(name string) bool {
	switch strings.ToLower(strings.TrimSpace(Getenv(name))) {
	case "0", "false", "off", "no":
		return true
	}
	return false
}

func envInt(name string, def int) int {
	v := strings.TrimSpace(Getenv(name))
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return def
	}
	return n
}
