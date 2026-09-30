package tesla

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"mime/multipart"
	"net/http"
	"strings"
	"time"
)

// Transcriber turns a short recorded audio clip into text. Implementations
// must not log the audio or the resulting transcript.
type Transcriber interface {
	Name() string
	Transcribe(ctx context.Context, audio []byte, mimeType string) (string, error)
}

// ErrSTTDisabled is returned by the "none" provider.
var ErrSTTDisabled = errors.New("speech-to-text is not configured on the server")

// NewTranscriber builds the provider named by cfg.STTProvider.
func NewTranscriber(cfg Config) (Transcriber, error) {
	switch cfg.STTProvider {
	case "", "none", "off":
		return disabledTranscriber{}, nil
	case "fake":
		return FakeTranscriber{Text: cfg.FakeTranscript}, nil
	case "openai":
		if cfg.OpenAIKey == "" {
			return nil, errors.New("TESLA_STT_PROVIDER=openai requires OPENAI_API_KEY")
		}
		return &OpenAICompatTranscriber{
			ProviderName: "openai",
			Endpoint:     firstNonEmpty(cfg.OpenAIBaseURL, "https://api.openai.com/v1") + "/audio/transcriptions",
			APIKey:       cfg.OpenAIKey,
			Model:        firstNonEmpty(cfg.OpenAIModel, "gpt-4o-mini-transcribe"),
			Language:     cfg.STTLanguage,
			Prompt:       cfg.STTPrompt,
		}, nil
	case "groq":
		if cfg.GroqKey == "" {
			return nil, errors.New("TESLA_STT_PROVIDER=groq requires GROQ_API_KEY")
		}
		return &OpenAICompatTranscriber{
			ProviderName: "groq",
			Endpoint:     "https://api.groq.com/openai/v1/audio/transcriptions",
			APIKey:       cfg.GroqKey,
			Model:        firstNonEmpty(cfg.GroqModel, "whisper-large-v3-turbo"),
			Language:     cfg.STTLanguage,
			Prompt:       cfg.STTPrompt,
		}, nil
	default:
		return nil, fmt.Errorf("unknown TESLA_STT_PROVIDER %q (want openai, groq, fake, or none)", cfg.STTProvider)
	}
}

// ProviderLabel is a short human name for a transcriber's Name(), shown in
// the car UI so you can tell which engine produced a transcript.
func ProviderLabel(name string) string {
	base, _, _ := strings.Cut(name, ":")
	switch base {
	case "openai":
		return "OpenAI"
	case "groq":
		return "Groq"
	case "fake":
		return "Fake STT"
	case "none", "":
		return "none"
	default:
		return name
	}
}

type disabledTranscriber struct{}

func (disabledTranscriber) Name() string { return "none" }
func (disabledTranscriber) Transcribe(context.Context, []byte, string) (string, error) {
	return "", ErrSTTDisabled
}

// FakeTranscriber is for local testing: it never calls a network service and
// returns a canned transcript (mentioning the clip size so tests can see the
// audio actually arrived).
type FakeTranscriber struct {
	Text string
}

func (FakeTranscriber) Name() string { return "fake" }

func (f FakeTranscriber) Transcribe(ctx context.Context, audio []byte, mimeType string) (string, error) {
	if len(audio) == 0 {
		return "", errors.New("empty audio")
	}
	if f.Text != "" {
		return f.Text, nil
	}
	return fmt.Sprintf("On my way, see you in ten minutes (fake transcript of %d bytes %s)", len(audio), baseMime(mimeType)), nil
}

// OpenAICompatTranscriber talks to OpenAI's /v1/audio/transcriptions API or
// any compatible one (Groq exposes the same multipart contract).
type OpenAICompatTranscriber struct {
	ProviderName string
	Endpoint     string
	APIKey       string
	Model        string
	Language     string
	Prompt       string
	HTTPClient   *http.Client
}

func (o *OpenAICompatTranscriber) Name() string { return o.ProviderName + ":" + o.Model }

func (o *OpenAICompatTranscriber) Transcribe(ctx context.Context, audio []byte, mimeType string) (string, error) {
	if len(audio) == 0 {
		return "", errors.New("empty audio")
	}
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	_ = mw.WriteField("model", o.Model)
	_ = mw.WriteField("response_format", "json")
	if o.Language != "" {
		_ = mw.WriteField("language", o.Language)
	}
	if o.Prompt != "" {
		_ = mw.WriteField("prompt", o.Prompt)
	}
	h := make(map[string][]string)
	h["Content-Disposition"] = []string{fmt.Sprintf(`form-data; name="file"; filename="clip%s"`, audioExtension(mimeType))}
	h["Content-Type"] = []string{firstNonEmpty(baseMime(mimeType), "application/octet-stream")}
	part, err := mw.CreatePart(h)
	if err != nil {
		return "", err
	}
	if _, err := part.Write(audio); err != nil {
		return "", err
	}
	if err := mw.Close(); err != nil {
		return "", err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, o.Endpoint, &body)
	if err != nil {
		return "", err
	}
	req.Header.Set("Authorization", "Bearer "+o.APIKey)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	client := o.HTTPClient
	if client == nil {
		client = &http.Client{Timeout: 60 * time.Second}
	}
	resp, err := client.Do(req)
	if err != nil {
		return "", fmt.Errorf("%s request failed: %w", o.ProviderName, err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode/100 != 2 {
		// Error bodies from these APIs describe the problem (bad key, bad
		// format) and never echo the key or the audio.
		var e struct {
			Error struct {
				Message string `json:"message"`
			} `json:"error"`
		}
		msg := strings.TrimSpace(string(raw))
		if json.Unmarshal(raw, &e) == nil && e.Error.Message != "" {
			msg = e.Error.Message
		}
		if len(msg) > 300 {
			msg = msg[:300]
		}
		return "", fmt.Errorf("%s returned HTTP %d: %s", o.ProviderName, resp.StatusCode, msg)
	}
	var out struct {
		Text string `json:"text"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return "", fmt.Errorf("%s returned unparseable JSON: %w", o.ProviderName, err)
	}
	return strings.TrimSpace(out.Text), nil
}

func baseMime(m string) string {
	if m == "" {
		return ""
	}
	mt, _, err := mime.ParseMediaType(m)
	if err != nil {
		return strings.ToLower(strings.TrimSpace(strings.SplitN(m, ";", 2)[0]))
	}
	return mt
}

func audioExtension(m string) string {
	switch baseMime(m) {
	case "audio/webm", "video/webm":
		return ".webm"
	case "audio/ogg", "application/ogg":
		return ".ogg"
	case "audio/mp4", "audio/m4a", "audio/x-m4a", "video/mp4":
		return ".m4a"
	case "audio/mpeg", "audio/mp3":
		return ".mp3"
	case "audio/wav", "audio/x-wav", "audio/wave":
		return ".wav"
	case "audio/flac", "audio/x-flac":
		return ".flac"
	default:
		return ".webm"
	}
}

func allowedAudioMime(m string) bool {
	b := baseMime(m)
	if b == "application/octet-stream" || b == "application/ogg" || b == "video/webm" || b == "video/mp4" {
		return true
	}
	return strings.HasPrefix(b, "audio/")
}

func firstNonEmpty(v ...string) string {
	for _, s := range v {
		if s != "" {
			return s
		}
	}
	return ""
}
