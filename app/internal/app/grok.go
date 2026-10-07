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
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/maxghenis/openmessage/internal/db"
)

// @Grok: when a new live message mentions "@Grok", ask xAI's Grok for a short
// reply using the conversation's recent messages and send it into the chat,
// prefixed with GrokReplyPrefix. Off by default (Settings), and only works
// with XAI_API_KEY set in the server's environment.
//
// Safeguards: never answers Grok's own (prefixed) messages; at most one reply
// per triggering message; only live messages from the last two minutes (no
// history or backfill); 1 reply per 30 s per chat and 30 per day overall;
// short prompt and reply.
const (
	GrokReplyPrefix      = "🤖 From Grok: "
	grokPerChatInterval  = 30 * time.Second
	grokDailyLimit       = 30
	grokMaxMessageAge    = 2 * time.Minute
	grokContextMessages  = 12
	grokContextChars     = 300
	grokReplyMaxChars    = 600
	grokRequestTimeout   = 60 * time.Second
	grokDefaultModel     = "grok-4.7"
	grokDefaultBaseURL   = "https://api.x.ai/v1"
	grokTriggerMe        = "me"
	grokTriggerEveryone  = "everyone"
	grokSettingsFileName = "grok.json"
)

var grokMention = regexp.MustCompile(`(?i)(^|[^\w@])@grok\b`)

type GrokSettings struct {
	Enabled bool   `json:"enabled"`
	Trigger string `json:"trigger"` // "me" (default) or "everyone"
}

// GrokStatus is what Settings shows.
type GrokStatus struct {
	GrokSettings
	KeyConfigured bool   `json:"key_configured"`
	Model         string `json:"model"`
	RepliesToday  int    `json:"replies_today"`
	DailyLimit    int    `json:"daily_limit"`
}

type grokState struct {
	mu       sync.Mutex
	loaded   bool
	settings GrokSettings
	handled  map[string]time.Time // message id -> when
	lastChat map[string]time.Time // conversation id -> last reply
	replies  []time.Time          // replies in the last 24 h
	client   *http.Client
}

var grokStates sync.Map // *App -> *grokState

func (a *App) grok() *grokState {
	v, _ := grokStates.LoadOrStore(a, &grokState{handled: map[string]time.Time{}, lastChat: map[string]time.Time{}, client: &http.Client{Timeout: grokRequestTimeout}})
	return v.(*grokState)
}

func grokAPIKey() string { return strings.TrimSpace(os.Getenv("XAI_API_KEY")) }

func grokModel() string {
	if m := strings.TrimSpace(os.Getenv("XAI_MODEL")); m != "" {
		return m
	}
	return grokDefaultModel
}

func grokBaseURL() string {
	if u := strings.TrimRight(strings.TrimSpace(os.Getenv("XAI_BASE_URL")), "/"); u != "" {
		return u
	}
	return grokDefaultBaseURL
}

func (a *App) grokSettingsPath() string {
	if a.DataDir == "" {
		return ""
	}
	return filepath.Join(a.DataDir, grokSettingsFileName)
}

// loadLocked reads the settings file once (defaults: off, only me).
func (g *grokState) loadLocked(path string) {
	if g.loaded {
		return
	}
	g.loaded = true
	g.settings = GrokSettings{Trigger: grokTriggerMe}
	if path == "" {
		return
	}
	if b, err := os.ReadFile(path); err == nil {
		var s GrokSettings
		if json.Unmarshal(b, &s) == nil {
			g.settings = normalizeGrokSettings(s)
		}
	}
}

func normalizeGrokSettings(s GrokSettings) GrokSettings {
	if s.Trigger != grokTriggerEveryone {
		s.Trigger = grokTriggerMe
	}
	return s
}

func (g *grokState) pruneLocked(now time.Time) {
	keep := g.replies[:0]
	for _, t := range g.replies {
		if now.Sub(t) < 24*time.Hour {
			keep = append(keep, t)
		}
	}
	g.replies = keep
	if len(g.handled) > 2000 {
		for id, t := range g.handled {
			if now.Sub(t) > time.Hour {
				delete(g.handled, id)
			}
		}
	}
}

// GrokStatus reports the @Grok settings and whether the key is set.
func (a *App) GrokStatus() GrokStatus {
	g := a.grok()
	g.mu.Lock()
	defer g.mu.Unlock()
	g.loadLocked(a.grokSettingsPath())
	g.pruneLocked(time.Now())
	return GrokStatus{GrokSettings: g.settings, KeyConfigured: grokAPIKey() != "", Model: grokModel(), RepliesToday: len(g.replies), DailyLimit: grokDailyLimit}
}

// SetGrokSettings saves the @Grok settings (data dir, owner-only file).
func (a *App) SetGrokSettings(s GrokSettings) (GrokStatus, error) {
	s = normalizeGrokSettings(s)
	g := a.grok()
	g.mu.Lock()
	g.loadLocked(a.grokSettingsPath())
	if p := a.grokSettingsPath(); p != "" {
		b, _ := json.Marshal(s)
		tmp := p + ".tmp"
		if err := os.WriteFile(tmp, b, 0o600); err != nil {
			g.mu.Unlock()
			return GrokStatus{}, err
		}
		if err := os.Rename(tmp, p); err != nil {
			g.mu.Unlock()
			return GrokStatus{}, err
		}
	}
	g.settings = s
	g.mu.Unlock()
	a.Logger.Info().Bool("enabled", s.Enabled).Str("trigger", s.Trigger).Msg("@Grok settings changed")
	return a.GrokStatus(), nil
}

// grokShouldReply decides (and reserves) a reply for a live message.
func (a *App) grokShouldReply(m *db.Message, now time.Time) (bool, string) {
	if m == nil || db.IsOutgoingPlaceholderID(m.MessageID) || strings.TrimSpace(m.MessageID) == "" {
		return false, "not a real message"
	}
	body := strings.TrimSpace(m.Body)
	if !grokMention.MatchString(body) {
		return false, "no mention"
	}
	if strings.HasPrefix(body, strings.TrimSpace(GrokReplyPrefix)) || strings.Contains(body, "From Grok:") {
		return false, "Grok's own message"
	}
	if strings.HasPrefix(strings.ToUpper(m.Status), "TOMBSTONE") {
		return false, "tombstone"
	}
	if ts := time.UnixMilli(m.TimestampMS); m.TimestampMS <= 0 || now.Sub(ts) > grokMaxMessageAge || ts.Sub(now) > grokMaxMessageAge {
		return false, "not a live message"
	}
	g := a.grok()
	g.mu.Lock()
	defer g.mu.Unlock()
	g.loadLocked(a.grokSettingsPath())
	if !g.settings.Enabled {
		return false, "disabled"
	}
	if grokAPIKey() == "" {
		return false, "no XAI_API_KEY"
	}
	if g.settings.Trigger != grokTriggerEveryone && !m.IsFromMe {
		return false, "only you can trigger"
	}
	if _, seen := g.handled[m.MessageID]; seen {
		return false, "already handled"
	}
	g.pruneLocked(now)
	if t, ok := g.lastChat[m.ConversationID]; ok && now.Sub(t) < grokPerChatInterval {
		g.handled[m.MessageID] = now
		return false, "chat rate limit"
	}
	if len(g.replies) >= grokDailyLimit {
		g.handled[m.MessageID] = now
		return false, "daily limit"
	}
	g.handled[m.MessageID] = now
	g.lastChat[m.ConversationID] = now
	g.replies = append(g.replies, now)
	return true, ""
}

// HandleLiveMessageForGrok is called for every new live message (yours and
// others'). Replies run in the background.
func (a *App) HandleLiveMessageForGrok(m *db.Message) {
	if a == nil || m == nil || !grokMention.MatchString(m.Body) {
		return
	}
	ok, why := a.grokShouldReply(m, time.Now())
	if !ok {
		a.Logger.Debug().Str("conv_id", m.ConversationID).Str("reason", why).Msg("@Grok mention ignored")
		return
	}
	msg := *m
	go a.grokReply(&msg)
}

func (a *App) grokReply(m *db.Message) {
	log := a.Logger.With().Str("conv_id", m.ConversationID).Logger()
	ctx, cancel := context.WithTimeout(context.Background(), grokRequestTimeout)
	defer cancel()
	prompt := a.grokContext(m)
	reply, err := a.grokComplete(ctx, prompt)
	if err != nil {
		log.Warn().Str("error", grokSafeErr(err)).Msg("@Grok reply failed")
		return
	}
	reply = grokTrimReply(reply)
	if reply == "" {
		log.Warn().Msg("@Grok returned an empty reply")
		return
	}
	if _, _, err := a.SendTextToConversation(m.ConversationID, GrokReplyPrefix+reply); err != nil {
		log.Warn().Err(err).Msg("@Grok reply send failed")
		return
	}
	log.Info().Int("reply_chars", len([]rune(reply))).Msg("@Grok replied")
}

// grokContext: the recent messages, oldest first, short.
func (a *App) grokContext(trigger *db.Message) string {
	var lines []string
	msgs, _ := a.Store.GetMessagesByConversation(trigger.ConversationID, grokContextMessages)
	seenTrigger := false
	for i := len(msgs) - 1; i >= 0; i-- {
		m := msgs[i]
		if strings.HasPrefix(strings.ToUpper(m.Status), "TOMBSTONE") || strings.TrimSpace(m.Body) == "" {
			continue
		}
		if m.MessageID == trigger.MessageID {
			seenTrigger = true
		}
		lines = append(lines, grokLine(m))
	}
	if !seenTrigger {
		lines = append(lines, grokLine(trigger))
	}
	return strings.Join(lines, "\n")
}

func grokLine(m *db.Message) string {
	who := "Me"
	if !m.IsFromMe {
		who = strings.TrimSpace(m.SenderName)
		if who == "" {
			who = "Someone"
		}
	}
	body := strings.TrimSpace(strings.ReplaceAll(m.Body, "\n", " "))
	if strings.HasPrefix(body, strings.TrimSpace(GrokReplyPrefix)) {
		who = "Grok"
		body = strings.TrimSpace(strings.TrimPrefix(body, strings.TrimSpace(GrokReplyPrefix)))
	}
	if r := []rune(body); len(r) > grokContextChars {
		body = string(r[:grokContextChars]) + "…"
	}
	return who + ": " + body
}

func grokTrimReply(s string) string {
	s = strings.TrimSpace(s)
	s = strings.TrimPrefix(s, strings.TrimSpace(GrokReplyPrefix))
	s = strings.TrimSpace(s)
	if r := []rune(s); len(r) > grokReplyMaxChars {
		s = strings.TrimSpace(string(r[:grokReplyMaxChars])) + "…"
	}
	return s
}

const grokSystemPrompt = "You are Grok, replying inside a text-message conversation because someone wrote @Grok. " +
	"Answer the latest message that mentions @Grok, using the recent messages for context. " +
	"Reply in plain text like a text message: one to three short sentences, no markdown, no preamble."

var errGrokHTTP = errors.New("xAI API error")

func (a *App) grokComplete(ctx context.Context, conversation string) (string, error) {
	key := grokAPIKey()
	if key == "" {
		return "", errors.New("XAI_API_KEY not set")
	}
	body := map[string]any{
		"model": grokModel(),
		"messages": []map[string]string{
			{"role": "system", "content": grokSystemPrompt},
			{"role": "user", "content": "Recent messages (oldest first):\n" + conversation},
		},
		"stream": false,
	}
	b, _ := json.Marshal(body)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, grokBaseURL()+"/chat/completions", bytes.NewReader(b))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+key)
	resp, err := a.grok().client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 256*1024))
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("%w: HTTP %d", errGrokHTTP, resp.StatusCode)
	}
	var out struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return "", fmt.Errorf("decode xAI response: %w", err)
	}
	if len(out.Choices) == 0 {
		return "", errors.New("xAI response had no choices")
	}
	return out.Choices[0].Message.Content, nil
}

// grokSafeErr describes an error without anything secret (the key is only
// ever in a header, never in the URL or error text).
func grokSafeErr(err error) string {
	s := err.Error()
	if k := grokAPIKey(); k != "" {
		s = strings.ReplaceAll(s, k, "[key]")
	}
	return s
}
