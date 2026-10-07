package app

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

// @Groq: like @Grok, but answered through Groq's OpenAI-compatible chat API
// with GROQ_API_KEY (the same key as Whisper dictation; works on the free
// tier). gpt-oss models get Groq's built-in browser_search tool for live
// answers; other models (GROQ_CHAT_MODEL) answer without search and say when
// live info is needed. Calls go to MESSAGES_GROQ_BASE_URL like dictation
// (e.g. the loopback stt-proxy). Free-tier limits for gpt-oss-120b are
// 30 req/min, 1000/day, 8K tokens/min and 200K tokens/day; a searched answer
// uses ~4-5K tokens, hence 40 replies a day and a pause after any HTTP 429.
const (
	GroqReplyPrefix     = "🤖 From Groq: "
	groqPerChatInterval = 30 * time.Second
	groqDailyLimit      = 40
	groqRequestTimeout  = 45 * time.Second
	groqDefaultModel    = "openai/gpt-oss-120b"
	groqDefaultBaseURL  = "https://api.groq.com/openai/v1"
	groqDefaultBackoff  = 60 * time.Second
	groqMaxBackoff      = 30 * time.Minute
	groqMaxOutputTokens = 2048             // includes reasoning
	groqMaxRetryWait    = 15 * time.Second // wait out a short 429 once
)

var errGroqHTTP = errors.New("Groq API error")

func groqAPIKey() string { return strings.TrimSpace(os.Getenv("GROQ_API_KEY")) }

func groqModel() string {
	if m := strings.TrimSpace(os.Getenv("GROQ_CHAT_MODEL")); m != "" {
		return m
	}
	return groqDefaultModel
}

// groqHasSearch: Groq's built-in browser_search runs on the gpt-oss models.
func groqHasSearch(model string) bool { return strings.HasPrefix(model, "openai/gpt-oss-") }

func groqBaseURL() string {
	if u := strings.TrimRight(strings.TrimSpace(os.Getenv("MESSAGES_GROQ_BASE_URL")), "/"); u != "" {
		return u
	}
	return groqDefaultBaseURL
}

// groqComplete asks Groq (chat completions). With browser_search the model
// decides when to search ("auto"); picture requests must search.
func (a *App) groqComplete(ctx context.Context, conversation string, wantImage bool) (grokAnswer, error) {
	key := groqAPIKey()
	if key == "" {
		return grokAnswer{}, errors.New("GROQ_API_KEY not set")
	}
	model := groqModel()
	search := groqHasSearch(model)
	how := ""
	if search {
		how = "search the web"
	}
	body := map[string]any{
		"model": model,
		"messages": []map[string]string{
			{"role": "system", "content": botSystemPrompt(botGroq, how, time.Now(), wantImage)},
			{"role": "user", "content": "Recent messages (oldest first):\n" + conversation},
		},
		"max_completion_tokens": groqMaxOutputTokens,
		"stream":                false,
	}
	if strings.HasPrefix(model, "openai/gpt-oss-") {
		body["reasoning_effort"] = "low" // Groq's advice with browser search: fewer tokens
	}
	if search {
		body["tools"] = []map[string]string{{"type": "browser_search"}}
		body["tool_choice"] = "auto"
		if wantImage {
			body["tool_choice"] = "required"
		}
	}
	b, _ := json.Marshal(body)
	var resp *http.Response
	var raw []byte
	for attempt := 0; ; attempt++ {
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, groqBaseURL()+"/chat/completions", bytes.NewReader(b))
		if err != nil {
			return grokAnswer{}, err
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Authorization", "Bearer "+key)
		resp, err = a.grok().groqClient.Do(req)
		if err != nil {
			return grokAnswer{}, err
		}
		raw, _ = io.ReadAll(io.LimitReader(resp.Body, 2<<20))
		resp.Body.Close()
		if resp.StatusCode != http.StatusTooManyRequests {
			break
		}
		// Free-tier limit (usually tokens per minute): wait once if it's
		// short and fits the request's time budget, else pause @Groq.
		d := groqRetryAfter(resp.Header.Get("Retry-After"))
		if dl, ok := ctx.Deadline(); attempt == 0 && d <= groqMaxRetryWait && (!ok || time.Until(dl) > d+10*time.Second) {
			select {
			case <-time.After(d):
				continue
			case <-ctx.Done():
				return grokAnswer{}, ctx.Err()
			}
		}
		g := a.grok()
		g.mu.Lock()
		if until := time.Now().Add(d); until.After(g.groqLim.pausedUntil) {
			g.groqLim.pausedUntil = until
		}
		g.mu.Unlock()
		return grokAnswer{}, fmt.Errorf("%w: HTTP 429 (free-tier limit), pausing @Groq for %s", errGroqHTTP, d)
	}
	if resp.StatusCode != http.StatusOK {
		var e struct {
			Error struct {
				Message string `json:"message"`
			} `json:"error"`
		}
		_ = json.Unmarshal(raw, &e)
		detail := e.Error.Message
		if r := []rune(detail); len(r) > 200 {
			detail = string(r[:200])
		}
		return grokAnswer{}, fmt.Errorf("%w: HTTP %d %s", errGroqHTTP, resp.StatusCode, detail)
	}
	return parseGroqResponse(raw)
}

func groqRetryAfter(h string) time.Duration {
	d := groqDefaultBackoff
	if n, err := strconv.ParseFloat(strings.TrimSpace(h), 64); err == nil && n > 0 {
		d = time.Duration(n * float64(time.Second))
	}
	if d > groqMaxBackoff {
		d = groqMaxBackoff
	}
	return d
}

func parseGroqResponse(raw []byte) (grokAnswer, error) {
	var out struct {
		Choices []struct {
			Message struct {
				Content       string            `json:"content"`
				ExecutedTools []json.RawMessage `json:"executed_tools"`
			} `json:"message"`
		} `json:"choices"`
		Usage struct {
			TotalTokens int `json:"total_tokens"`
		} `json:"usage"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return grokAnswer{}, fmt.Errorf("decode Groq response: %w", err)
	}
	if len(out.Choices) == 0 {
		return grokAnswer{}, errors.New("Groq response had no choices")
	}
	m := out.Choices[0].Message
	ans := grokAnswer{Searches: len(m.ExecutedTools), Tokens: out.Usage.TotalTokens}
	text := grokThink.ReplaceAllString(m.Content, "")
	ans.Text, ans.ImageURL = grokExtractImage(text)
	if strings.TrimSpace(ans.Text) == "" && ans.ImageURL == "" {
		return ans, errors.New("Groq response had no text")
	}
	return ans, nil
}
