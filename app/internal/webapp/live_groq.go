package webapp

// Live typing through Groq (OpenAI-compatible Whisper API): the browser's
// rolling 16 kHz window is posted every few seconds and transcribed with
// word timestamps. Groq's free tier for whisper-large-v3-turbo allows 20
// requests/min, 2,000/day, 7,200 audio-seconds/hour and 28,800/day, and bills
// at least 10 s of audio per request, so partial passes are throttled
// server-wide to one every GroqPartialInterval (15/min), leaving room for the
// final full transcription, and pause after a 429 for Groq's Retry-After.

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	GroqPartialInterval = 4 * time.Second
	groqDefaultBackoff  = 30 * time.Second
)

// errPartialThrottled: too soon for another partial pass (rate limit).
var errPartialThrottled = errors.New("live typing is throttled (provider rate limit); retrying shortly")

type GroqPartial struct {
	Endpoint   string // .../audio/transcriptions
	APIKey     string
	Model      string
	Language   string
	Prompt     string
	Interval   time.Duration
	HTTPClient *http.Client

	mu        sync.Mutex
	last      time.Time
	blockedTo time.Time
	now       func() time.Time
}

func (g *GroqPartial) clock() time.Time {
	if g.now != nil {
		return g.now()
	}
	return time.Now()
}

// take reserves a request slot, or reports how long to wait.
func (g *GroqPartial) take() (time.Duration, bool) {
	g.mu.Lock()
	defer g.mu.Unlock()
	now := g.clock()
	if now.Before(g.blockedTo) {
		return g.blockedTo.Sub(now), false
	}
	iv := g.Interval
	if iv <= 0 {
		iv = GroqPartialInterval
	}
	if !g.last.IsZero() && now.Sub(g.last) < iv {
		return iv - now.Sub(g.last), false
	}
	g.last = now
	return 0, true
}

func (g *GroqPartial) backoff(d time.Duration) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if t := g.clock().Add(d); t.After(g.blockedTo) {
		g.blockedTo = t
	}
}

// PartialRetryAfter (for the handler): how long until the next slot.
func (g *GroqPartial) PartialRetryAfter() time.Duration {
	g.mu.Lock()
	defer g.mu.Unlock()
	now := g.clock()
	if now.Before(g.blockedTo) {
		return g.blockedTo.Sub(now)
	}
	iv := g.Interval
	if iv <= 0 {
		iv = GroqPartialInterval
	}
	if d := iv - now.Sub(g.last); d > 0 {
		return d
	}
	return 0
}

// PartialStepMS tells the page how often to send a window.
func (g *GroqPartial) PartialStepMS() int {
	if g.Interval > 0 {
		return int(g.Interval / time.Millisecond)
	}
	return int(GroqPartialInterval / time.Millisecond)
}

func (g *GroqPartial) TranscribePartial(ctx context.Context, wav []byte) (PartialResult, error) {
	if _, ok := g.take(); !ok {
		return PartialResult{}, errPartialThrottled
	}
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	_ = mw.WriteField("model", g.Model)
	_ = mw.WriteField("response_format", "verbose_json")
	_ = mw.WriteField("timestamp_granularities[]", "word")
	_ = mw.WriteField("temperature", "0")
	if g.Language != "" {
		_ = mw.WriteField("language", g.Language)
	}
	if g.Prompt != "" {
		_ = mw.WriteField("prompt", g.Prompt)
	}
	fw, err := mw.CreateFormFile("file", "window.wav")
	if err != nil {
		return PartialResult{}, err
	}
	_, _ = fw.Write(wav)
	if err := mw.Close(); err != nil {
		return PartialResult{}, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, g.Endpoint, &body)
	if err != nil {
		return PartialResult{}, err
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Authorization", "Bearer "+g.APIKey)
	client := g.HTTPClient
	if client == nil {
		client = &http.Client{Timeout: 15 * time.Second}
	}
	resp, err := client.Do(req)
	if err != nil {
		return PartialResult{}, fmt.Errorf("groq partial request failed: %w", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode == http.StatusTooManyRequests {
		d := groqDefaultBackoff
		if s, err := strconv.Atoi(strings.TrimSpace(resp.Header.Get("Retry-After"))); err == nil && s > 0 {
			d = time.Duration(s) * time.Second
		}
		g.backoff(d)
		return PartialResult{}, errPartialThrottled
	}
	if resp.StatusCode/100 != 2 {
		msg := strings.TrimSpace(string(raw))
		if len(msg) > 200 {
			msg = msg[:200]
		}
		return PartialResult{}, fmt.Errorf("groq partial returned HTTP %d: %s", resp.StatusCode, msg)
	}
	return parseOpenAIWords(raw)
}

// parseOpenAIWords reads verbose_json with top-level "words" (OpenAI/Groq
// word granularity); falls back to whisper.cpp-style segment words.
func parseOpenAIWords(raw []byte) (PartialResult, error) {
	var v struct {
		Text  string `json:"text"`
		Words []struct {
			Word  string  `json:"word"`
			Start float64 `json:"start"`
			End   float64 `json:"end"`
		} `json:"words"`
	}
	if err := json.Unmarshal(raw, &v); err != nil {
		return PartialResult{}, fmt.Errorf("groq partial returned unparseable JSON: %w", err)
	}
	if len(v.Words) == 0 {
		if r, err := parseWhisperVerbose(raw); err == nil && len(r.Words) > 0 {
			return r, nil
		}
	}
	res := PartialResult{Words: []Word{}}
	var parts []string
	for _, w := range v.Words {
		t := strings.TrimSpace(w.Word)
		if t == "" || nonSpeech.MatchString(t) {
			continue
		}
		res.Words = append(res.Words, Word{Text: t, Start: w.Start, End: w.End})
		parts = append(parts, t)
	}
	res.Text = strings.Join(parts, " ")
	return res, nil
}
