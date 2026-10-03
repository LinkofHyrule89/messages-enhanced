package webapp

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"mime/multipart"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Live typing: while the mic records, the car page posts the utterance so far
// (raw 16 kHz mono s16le PCM, capped to a rolling window) to
// /api/transcribe/partial about once a second and shows the words as they
// firm up. The final full-utterance pass still goes through /api/transcribe.

const (
	PartialSampleRate = 16000
	// MinPartialBytes is 0.25 s of audio; shorter windows aren't worth a pass.
	MinPartialBytes = PartialSampleRate * 2 / 4
	// MaxPartialSecs bounds one partial window. The partial whisper-server
	// runs with -ac 768 (15.36 s of encoder context), so stay under that.
	MaxPartialSecs  = 14
	MaxPartialBytes = MaxPartialSecs * PartialSampleRate * 2
	// MinPartialInterval rate-limits one stream (one recording).
	MinPartialInterval = 250 * time.Millisecond
	// MaxPartialInFlight caps partial passes across all clients.
	MaxPartialInFlight = 2
)

// Word is one recognised word with times in seconds from the window start.
type Word struct {
	Text  string  `json:"w"`
	Start float64 `json:"s"`
	End   float64 `json:"e"`
}

// PartialResult is a hypothesis for one live window.
type PartialResult struct {
	Text  string `json:"text"`
	Words []Word `json:"words"`
}

// PartialTranscriber is implemented by providers that can do fast live
// passes on 16 kHz mono WAV windows.
type PartialTranscriber interface {
	TranscribePartial(ctx context.Context, wav []byte) (PartialResult, error)
}

// WhisperCppPartial asks a whisper.cpp whisper-server for verbose_json with
// token timestamps (no ffmpeg needed: the body is already WAV).
type WhisperCppPartial struct {
	Endpoint   string
	Language   string
	Prompt     string
	HTTPClient *http.Client
}

func (p *WhisperCppPartial) TranscribePartial(ctx context.Context, wav []byte) (PartialResult, error) {
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	_ = mw.WriteField("response_format", "verbose_json")
	_ = mw.WriteField("temperature", "0")
	_ = mw.WriteField("temperature_inc", "0") // no fallback re-decodes: keep partials fast
	_ = mw.WriteField("no_timestamps", "false")
	_ = mw.WriteField("token_timestamps", "true")
	if p.Language != "" {
		_ = mw.WriteField("language", p.Language)
	}
	if p.Prompt != "" {
		_ = mw.WriteField("prompt", p.Prompt)
	}
	h := make(map[string][]string)
	h["Content-Disposition"] = []string{`form-data; name="file"; filename="window.wav"`}
	h["Content-Type"] = []string{"audio/wav"}
	part, err := mw.CreatePart(h)
	if err != nil {
		return PartialResult{}, err
	}
	_, _ = part.Write(wav)
	if err := mw.Close(); err != nil {
		return PartialResult{}, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, p.Endpoint, &body)
	if err != nil {
		return PartialResult{}, err
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	client := p.HTTPClient
	if client == nil {
		client = &http.Client{Timeout: 20 * time.Second}
	}
	resp, err := client.Do(req)
	if err != nil {
		return PartialResult{}, fmt.Errorf("whisper partial request failed: %w", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode/100 != 2 {
		msg := strings.TrimSpace(string(raw))
		if len(msg) > 200 {
			msg = msg[:200]
		}
		return PartialResult{}, fmt.Errorf("whisper partial returned HTTP %d: %s", resp.StatusCode, msg)
	}
	return parseWhisperVerbose(raw)
}

type whisperVerbose struct {
	Text     string `json:"text"`
	Segments []struct {
		Text  string `json:"text"`
		Start float64
		End   float64
		Words []struct {
			Word  string   `json:"word"`
			Start *float64 `json:"start"`
			End   *float64 `json:"end"`
		} `json:"words"`
	} `json:"segments"`
}

// nonSpeech matches whisper's markers like [BLANK_AUDIO], (music), [ Silence ].
var nonSpeech = regexp.MustCompile(`^[\[(].*[\])]$`)

// parseWhisperVerbose merges whisper's sub-word tokens into words: a token
// that starts with a space starts a new word; others (", ", "'m", "ing")
// attach to the previous one.
func parseWhisperVerbose(raw []byte) (PartialResult, error) {
	var v whisperVerbose
	if err := json.Unmarshal(raw, &v); err != nil {
		return PartialResult{}, fmt.Errorf("whisper partial returned unparseable JSON: %w", err)
	}
	var words []Word
	for _, seg := range v.Segments {
		if nonSpeech.MatchString(strings.TrimSpace(seg.Text)) {
			continue
		}
		for _, t := range seg.Words {
			if t.Word == "" {
				continue
			}
			var st, en float64
			if t.Start != nil {
				st = *t.Start
			}
			if t.End != nil {
				en = *t.End
			}
			if strings.HasPrefix(t.Word, " ") || len(words) == 0 {
				w := strings.TrimSpace(t.Word)
				if w == "" {
					continue
				}
				words = append(words, Word{Text: w, Start: st, End: en})
				continue
			}
			last := &words[len(words)-1]
			last.Text += t.Word
			if en > last.End {
				last.End = en
			}
		}
	}
	out := words[:0]
	for _, w := range words {
		if nonSpeech.MatchString(w.Text) {
			continue
		}
		out = append(out, w)
	}
	res := PartialResult{Words: out}
	parts := make([]string, len(out))
	for i, w := range out {
		parts[i] = w.Text
	}
	res.Text = strings.Join(parts, " ")
	if res.Words == nil {
		res.Words = []Word{}
	}
	return res, nil
}

// TranscribePartial for the fake provider: a deterministic hypothesis that
// grows with the audio (two words per second), for tests and demos.
func (f FakeTranscriber) TranscribePartial(ctx context.Context, wav []byte) (PartialResult, error) {
	secs := float64(len(wav)-44) / (PartialSampleRate * 2)
	src := strings.Fields(firstNonEmpty(f.Text, "on my way see you in ten minutes at the usual place thanks again for waiting"))
	n := int(secs * 2)
	if n > len(src) {
		n = len(src)
	}
	res := PartialResult{Words: []Word{}}
	for i := 0; i < n; i++ {
		res.Words = append(res.Words, Word{Text: src[i], Start: float64(i) * 0.5, End: float64(i)*0.5 + 0.45})
	}
	parts := make([]string, n)
	for i := range parts {
		parts[i] = res.Words[i].Text
	}
	res.Text = strings.Join(parts, " ")
	return res, nil
}

// PCM16ToWAV wraps raw little-endian 16-bit mono PCM in a RIFF/WAVE header.
func PCM16ToWAV(pcm []byte, rate int) []byte {
	out := make([]byte, 44+len(pcm))
	copy(out[0:], "RIFF")
	binary.LittleEndian.PutUint32(out[4:], uint32(36+len(pcm)))
	copy(out[8:], "WAVEfmt ")
	binary.LittleEndian.PutUint32(out[16:], 16)             // fmt chunk size
	binary.LittleEndian.PutUint16(out[20:], 1)              // PCM
	binary.LittleEndian.PutUint16(out[22:], 1)              // mono
	binary.LittleEndian.PutUint32(out[24:], uint32(rate))   // sample rate
	binary.LittleEndian.PutUint32(out[28:], uint32(rate*2)) // byte rate
	binary.LittleEndian.PutUint16(out[32:], 2)              // block align
	binary.LittleEndian.PutUint16(out[34:], 16)             // bits per sample
	copy(out[36:], "data")
	binary.LittleEndian.PutUint32(out[40:], uint32(len(pcm)))
	copy(out[44:], pcm)
	return out
}

// partialGate enforces one in-flight partial per stream, drops stale
// sequence numbers, rate-limits each stream and caps global concurrency.
type partialGate struct {
	mu       sync.Mutex
	streams  map[string]*partialStream
	inFlight int
	now      func() time.Time
}

type partialStream struct {
	busy    bool
	lastSeq int64
	last    time.Time
}

var errPartialBusy = errors.New("a live pass is already running")
var errPartialStale = errors.New("stale live window")
var errPartialRate = errors.New("too many live windows")

func newPartialGate() *partialGate {
	return &partialGate{streams: map[string]*partialStream{}, now: time.Now}
}

func (g *partialGate) acquire(stream string, seq int64) (func(), error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	now := g.now()
	if len(g.streams) > 256 { // forget idle streams
		for k, st := range g.streams {
			if !st.busy && now.Sub(st.last) > 2*time.Minute {
				delete(g.streams, k)
			}
		}
	}
	st := g.streams[stream]
	if st == nil {
		st = &partialStream{lastSeq: -1}
		g.streams[stream] = st
	}
	if seq <= st.lastSeq {
		return nil, errPartialStale
	}
	if st.busy {
		return nil, errPartialBusy
	}
	if !st.last.IsZero() && now.Sub(st.last) < MinPartialInterval {
		return nil, errPartialRate
	}
	if g.inFlight >= MaxPartialInFlight {
		return nil, errPartialBusy
	}
	st.busy, st.lastSeq, st.last = true, seq, now
	g.inFlight++
	return func() {
		g.mu.Lock()
		st.busy = false
		g.inFlight--
		g.mu.Unlock()
	}, nil
}

// latest reports whether seq is still the newest window seen for stream.
func (g *partialGate) latest(stream string, seq int64) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	st := g.streams[stream]
	return st == nil || st.lastSeq <= seq
}

var streamIDRe = regexp.MustCompile(`^[A-Za-z0-9_-]{8,64}$`)

func (s *Server) partialTranscriber() PartialTranscriber {
	if s.cfg.LiveDisabled || !s.serverSTTEnabled() {
		return nil
	}
	if s.partial != nil {
		return s.partial
	}
	if p, ok := s.stt.(PartialTranscriber); ok {
		return p
	}
	return nil
}

func (s *Server) liveSTTEnabled() bool { return s.partialTranscriber() != nil }

// handleTranscribePartial: POST raw PCM (Content-Type audio/L16;rate=16000)
// with X-STT-Stream (random id per recording) and X-STT-Seq (increasing).
// Returns {"text","words":[{"w","s","e"}],"seq","ms"}.
func (s *Server) handleTranscribePartial(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]string{"error": "method not allowed"})
		return
	}
	pt := s.partialTranscriber()
	if pt == nil {
		writeJSON(w, 503, map[string]string{"error": "live typing isn't available on this server"})
		return
	}
	mt, params, _ := mime.ParseMediaType(r.Header.Get("Content-Type"))
	mt = strings.ToLower(mt)
	if mt != "audio/l16" && mt != "audio/pcm" {
		writeJSON(w, 415, map[string]string{"error": "want audio/L16;rate=16000 (16-bit mono PCM)"})
		return
	}
	if rate := params["rate"]; rate != "" && rate != strconv.Itoa(PartialSampleRate) {
		writeJSON(w, 415, map[string]string{"error": "want rate=16000"})
		return
	}
	if ch := params["channels"]; ch != "" && ch != "1" {
		writeJSON(w, 415, map[string]string{"error": "want mono"})
		return
	}
	stream := r.Header.Get("X-STT-Stream")
	seq, err := strconv.ParseInt(r.Header.Get("X-STT-Seq"), 10, 64)
	if !streamIDRe.MatchString(stream) || err != nil || seq < 0 {
		writeJSON(w, 400, map[string]string{"error": "X-STT-Stream and X-STT-Seq are required"})
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, MaxPartialBytes)
	pcm, err := io.ReadAll(r.Body)
	if err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			writeJSON(w, 413, map[string]string{"error": fmt.Sprintf("live window over %d s", MaxPartialSecs)})
			return
		}
		writeJSON(w, 400, map[string]string{"error": "could not read audio"})
		return
	}
	if len(pcm) < MinPartialBytes || len(pcm)%2 != 0 {
		writeJSON(w, 400, map[string]string{"error": "live window too short or not 16-bit PCM"})
		return
	}
	release, err := s.liveGate.acquire(stream, seq)
	if err != nil {
		status := 429
		if errors.Is(err, errPartialStale) {
			status = 409
		}
		writeJSON(w, status, map[string]any{"error": err.Error(), "seq": seq})
		return
	}
	defer release()
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	start := time.Now()
	res, err := pt.TranscribePartial(ctx, PCM16ToWAV(pcm, PartialSampleRate))
	elapsed := time.Since(start)
	if errors.Is(err, errPartialThrottled) {
		retry := 1000
		if g, ok := pt.(*GroqPartial); ok {
			retry = int(g.PartialRetryAfter() / time.Millisecond)
		}
		writeJSON(w, 429, map[string]any{"error": err.Error(), "seq": seq, "retry_after_ms": retry})
		return
	}
	if err != nil {
		s.deps.Logger.Warn().Int("bytes", len(pcm)).Dur("took", elapsed).Err(err).Msg("Live transcription pass failed")
		writeJSON(w, 502, map[string]string{"error": "live pass failed: " + err.Error()})
		return
	}
	s.deps.Logger.Debug().Int("bytes", len(pcm)).Int("words", len(res.Words)).Dur("took", elapsed).Msg("Live transcription pass")
	writeJSON(w, 200, map[string]any{
		"text": res.Text, "words": res.Words, "seq": seq, "ms": elapsed.Milliseconds(),
		"audio_ms": len(pcm) * 1000 / (PartialSampleRate * 2), "stale": !s.liveGate.latest(stream, seq),
	})
}

// liveStepMS: the page's live-window interval (rate-limited providers ask
// for a slower one).
func (s *Server) liveStepMS() int {
	if p, ok := s.partialTranscriber().(interface{ PartialStepMS() int }); ok {
		return p.PartialStepMS()
	}
	return 700
}
