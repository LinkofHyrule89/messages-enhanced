package tesla

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

func partialReq(t *testing.T, h http.Handler, c *http.Cookie, ct, stream string, seq int, pcm []byte) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "http://car.example/api/transcribe/partial", bytes.NewReader(pcm))
	if ct != "" {
		req.Header.Set("Content-Type", ct)
	}
	if stream != "" {
		req.Header.Set("X-STT-Stream", stream)
	}
	req.Header.Set("X-STT-Seq", strconv.Itoa(seq))
	req.Header.Set("Origin", "http://car.example")
	if c != nil {
		req.AddCookie(c)
	}
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	return rr
}

const pcmCT = "audio/L16;rate=16000;channels=1"

func secsPCM(s float64) []byte { return make([]byte, int(s*PartialSampleRate)*2) }

// fixedGateClock lets tests step past the per-stream rate limit.
func stepGate(s *Server) func(time.Duration) {
	now := time.Unix(1000, 0)
	s.liveGate.now = func() time.Time { return now }
	return func(d time.Duration) { now = now.Add(d) }
}

func TestPartialEndpointFakeGrows(t *testing.T) {
	h, s, _ := newTestServer(t, nil)
	step := stepGate(s)
	c := login(t, h)
	var prev int
	for i, secs := range []float64{1, 2, 3.5} {
		step(time.Second)
		rr := partialReq(t, h, c, pcmCT, "stream-abcdef01", i, secsPCM(secs))
		if rr.Code != 200 {
			t.Fatalf("seq %d: %d %s", i, rr.Code, rr.Body.String())
		}
		var out struct {
			Text  string `json:"text"`
			Words []Word `json:"words"`
			Seq   int    `json:"seq"`
		}
		if err := json.Unmarshal(rr.Body.Bytes(), &out); err != nil {
			t.Fatal(err)
		}
		if out.Seq != i || len(out.Words) <= prev || !strings.HasPrefix(out.Text, "on my") {
			t.Fatalf("seq %d: %+v", i, out)
		}
		prev = len(out.Words)
	}
}

func TestPartialEndpointGuards(t *testing.T) {
	h, s, _ := newTestServer(t, nil)
	step := stepGate(s)
	c := login(t, h)
	// auth required
	if rr := partialReq(t, h, nil, pcmCT, "stream-abcdef01", 0, secsPCM(1)); rr.Code != 401 {
		t.Fatalf("unauthenticated: %d", rr.Code)
	}
	cases := []struct {
		name, ct, stream string
		pcm              []byte
		want             int
	}{
		{"webm rejected", "audio/webm", "stream-abcdef01", secsPCM(1), 415},
		{"wrong rate", "audio/L16;rate=48000", "stream-abcdef01", secsPCM(1), 415},
		{"stereo", "audio/L16;rate=16000;channels=2", "stream-abcdef01", secsPCM(1), 415},
		{"no stream id", pcmCT, "", secsPCM(1), 400},
		{"bad stream id", pcmCT, "x y", secsPCM(1), 400},
		{"too short", pcmCT, "stream-abcdef01", secsPCM(0.1), 400},
		{"odd length", pcmCT, "stream-abcdef01", append(secsPCM(1), 0), 400},
		{"too long", pcmCT, "stream-abcdef01", secsPCM(MaxPartialSecs + 1), 413},
	}
	for _, tc := range cases {
		if rr := partialReq(t, h, c, tc.ct, tc.stream, 0, tc.pcm); rr.Code != tc.want {
			t.Errorf("%s: got %d want %d (%s)", tc.name, rr.Code, tc.want, rr.Body.String())
		}
	}
	// stale and rate limited
	step(time.Second)
	if rr := partialReq(t, h, c, pcmCT, "stream-guard001", 5, secsPCM(1)); rr.Code != 200 {
		t.Fatalf("first: %d", rr.Code)
	}
	step(time.Second)
	if rr := partialReq(t, h, c, pcmCT, "stream-guard001", 4, secsPCM(1)); rr.Code != 409 {
		t.Fatalf("stale seq: %d", rr.Code)
	}
	if rr := partialReq(t, h, c, pcmCT, "stream-guard001", 6, secsPCM(1)); rr.Code != 200 {
		t.Fatalf("next seq: %d", rr.Code)
	}
	if rr := partialReq(t, h, c, pcmCT, "stream-guard001", 7, secsPCM(1)); rr.Code != 429 {
		t.Fatalf("rate limit: %d", rr.Code)
	}
}

// blockingPartial holds each pass until released, to test concurrency.
type blockingPartial struct {
	started chan struct{}
	release chan struct{}
}

func (b *blockingPartial) TranscribePartial(ctx context.Context, wav []byte) (PartialResult, error) {
	b.started <- struct{}{}
	<-b.release
	return PartialResult{Text: "ok", Words: []Word{{Text: "ok"}}}, nil
}

func TestPartialOneInFlightPerStreamAndGlobalCap(t *testing.T) {
	bp := &blockingPartial{started: make(chan struct{}, 8), release: make(chan struct{})}
	h, s, _ := newTestServer(t, func(_ *Config, d *Deps) { d.Partial = bp })
	step := stepGate(s)
	c := login(t, h)
	var wg sync.WaitGroup
	codes := make(chan int, 4)
	run := func(stream string, seq int) {
		wg.Add(1)
		go func() { defer wg.Done(); codes <- partialReq(t, h, c, pcmCT, stream, seq, secsPCM(1)).Code }()
	}
	run("stream-aaaaaaaa", 1)
	<-bp.started
	step(time.Second)
	// same stream while busy -> 429
	if rr := partialReq(t, h, c, pcmCT, "stream-aaaaaaaa", 2, secsPCM(1)); rr.Code != 429 {
		t.Fatalf("same stream busy: %d", rr.Code)
	}
	run("stream-bbbbbbbb", 1)
	<-bp.started
	// third stream over the global cap -> 429
	if rr := partialReq(t, h, c, pcmCT, "stream-cccccccc", 1, secsPCM(1)); rr.Code != 429 {
		t.Fatalf("global cap: %d", rr.Code)
	}
	close(bp.release)
	wg.Wait()
	close(codes)
	for code := range codes {
		if code != 200 {
			t.Fatalf("blocked pass finished with %d", code)
		}
	}
	step(time.Second)
	if rr := partialReq(t, h, c, pcmCT, "stream-cccccccc", 2, secsPCM(1)); rr.Code != 200 {
		t.Fatalf("after release: %d", rr.Code)
	}
}

func TestPartialDisabled(t *testing.T) {
	for name, mutate := range map[string]func(*Config, *Deps){
		"provider without live": func(_ *Config, d *Deps) { d.Transcriber = errTranscriber{} },
		"TESLA_STT_LIVE=0":      func(c *Config, _ *Deps) { c.LiveDisabled = true },
		"builtin mode":          func(c *Config, _ *Deps) { c.STTMode = STTModeBuiltin },
		"none provider":         func(c *Config, _ *Deps) { c.STTProvider = "none" },
	} {
		h, _, _ := newTestServer(t, mutate)
		c := login(t, h)
		if rr := partialReq(t, h, c, pcmCT, "stream-abcdef01", 0, secsPCM(1)); rr.Code != 503 {
			t.Errorf("%s: got %d", name, rr.Code)
		}
		if cfg := getConfig(t, mutate); cfg["stt_live"] != false {
			t.Errorf("%s: config stt_live=%v", name, cfg["stt_live"])
		}
	}
	if cfg := getConfig(t, nil); cfg["stt_live"] != true || cfg["stt_live_max_secs"] != float64(MaxPartialSecs) {
		t.Errorf("fake provider config: %v", cfg)
	}
}

func TestPCM16ToWAV(t *testing.T) {
	pcm := []byte{1, 0, 2, 0, 3, 0}
	w := PCM16ToWAV(pcm, 16000)
	if len(w) != 50 || string(w[0:4]) != "RIFF" || string(w[8:16]) != "WAVEfmt " || string(w[36:40]) != "data" {
		t.Fatalf("bad header % x", w[:44])
	}
	if binary.LittleEndian.Uint32(w[4:]) != 42 || binary.LittleEndian.Uint32(w[24:]) != 16000 ||
		binary.LittleEndian.Uint32(w[28:]) != 32000 || binary.LittleEndian.Uint16(w[34:]) != 16 ||
		binary.LittleEndian.Uint32(w[40:]) != 6 || !bytes.Equal(w[44:], pcm) {
		t.Fatalf("bad fields % x", w)
	}
}

func TestParseWhisperVerboseMergesTokens(t *testing.T) {
	raw := `{"text":" Hey, I'm running.","segments":[
	 {"text":" Hey, I'm","start":0,"end":1,"words":[{"word":" Hey","start":0.06,"end":0.2},{"word":",","start":0.2,"end":0.32},{"word":" I","start":0.32,"end":0.4},{"word":"'m","start":0.4,"end":0.41}]},
	 {"text":" [BLANK_AUDIO]","start":1,"end":2,"words":[{"word":" [","start":1,"end":1.1},{"word":"BLANK_AUDIO]","start":1.1,"end":2}]},
	 {"text":" running.","start":2,"end":3,"words":[{"word":" runn","start":2.1,"end":2.3},{"word":"ing","start":2.3,"end":2.6},{"word":".","start":2.6,"end":2.6}]}]}`
	res, err := parseWhisperVerbose([]byte(raw))
	if err != nil {
		t.Fatal(err)
	}
	if res.Text != "Hey, I'm running." || len(res.Words) != 3 || res.Words[2].Start != 2.1 || res.Words[2].End != 2.6 || res.Words[1].Text != "I'm" {
		t.Fatalf("%+v", res)
	}
	if res, _ := parseWhisperVerbose([]byte(`{"text":"","segments":[]}`)); res.Words == nil || res.Text != "" {
		t.Fatalf("empty: %+v", res)
	}
}

func TestWhisperCppPartialRequestShape(t *testing.T) {
	var got map[string]string
	var wavHead string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseMultipartForm(1 << 20); err != nil {
			t.Error(err)
		}
		got = map[string]string{}
		for k, v := range r.MultipartForm.Value {
			got[k] = v[0]
		}
		f, _, err := r.FormFile("file")
		if err == nil {
			b, _ := io.ReadAll(f)
			wavHead = string(b[:4])
		}
		_, _ = w.Write([]byte(`{"text":" Hi there","segments":[{"text":" Hi there","words":[{"word":" Hi","start":0.1,"end":0.3},{"word":" there","start":0.3,"end":0.6}]}]}`))
	}))
	defer srv.Close()
	p := NewPartialTranscriber(Config{STTProvider: "whisper", WhisperURL: "http://unused", WhisperLiveURL: srv.URL + "/inference", STTLanguage: "en"})
	res, err := p.TranscribePartial(context.Background(), PCM16ToWAV(secsPCM(0.5), 16000))
	if err != nil {
		t.Fatal(err)
	}
	if res.Text != "Hi there" || got["response_format"] != "verbose_json" || got["no_timestamps"] != "false" || got["language"] != "en" || wavHead != "RIFF" {
		t.Fatalf("res=%+v form=%v head=%q", res, got, wavHead)
	}
	if NewPartialTranscriber(Config{STTProvider: "openai"}) != nil || NewPartialTranscriber(Config{STTProvider: "groq"}) != nil {
		t.Fatal("cloud providers must not do live passes")
	}
	if wp := NewPartialTranscriber(Config{STTProvider: "whisper"}).(*WhisperCppPartial); wp.Endpoint != DefaultWhisperURL {
		t.Fatalf("default live endpoint %q", wp.Endpoint)
	}
}

func TestLiveConfigFromEnv(t *testing.T) {
	t.Setenv("TESLA_SECRET", testSecret)
	t.Setenv("TESLA_WHISPER_LIVE_URL", "http://127.0.0.1:8179/inference")
	t.Setenv("TESLA_STT_LIVE", "off")
	c, err := ConfigFromEnv()
	if err != nil || c.WhisperLiveURL != "http://127.0.0.1:8179/inference" || !c.LiveDisabled {
		t.Fatalf("%+v %v", c, err)
	}
	t.Setenv("TESLA_STT_LIVE", "")
	if c, _ := ConfigFromEnv(); c.LiveDisabled {
		t.Fatal("live should default on")
	}
}
