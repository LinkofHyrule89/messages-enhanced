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
	_ "time/tzdata" // GROK_TIMEZONE works even without system zoneinfo

	"github.com/maxghenis/openmessage/internal/db"
)

// @Grok: when a new live message mentions "@Grok", ask xAI's Grok for a short
// reply using the conversation's recent messages and send it into the chat,
// prefixed with GrokReplyPrefix. Off by default (Settings), and only works
// with XAI_API_KEY set in the server's environment. "@Groq" works the same
// way through Groq's API (groq.go, GROQ_API_KEY) with its own switch and
// limits; the "who can trigger" setting is shared.
//
// Safeguards: never answers either bot's own (prefixed) messages; at most one
// reply per triggering message (if both bots are mentioned, the first one
// answers); only live messages from the last two minutes (no history or
// backfill); per-bot limits per chat and per day; short prompt and reply.
const (
	GrokReplyPrefix      = "🤖 From Grok: "
	grokPerChatInterval  = 30 * time.Second
	grokDailyLimit       = 30
	grokMaxMessageAge    = 2 * time.Minute
	grokContextMessages  = 12
	grokContextChars     = 300
	grokReplyMaxChars    = 600
	grokRequestTimeout   = 45 * time.Second // live search can take a while
	grokImageReqTimeout  = 45 * time.Second
	grokDefaultImgModel  = "grok-4.20-non-reasoning" // picture lookups: one Wikimedia search, ~3-8 s, ~$0.02
	grokMaxSearchTurns   = 3                         // bounds search calls (billed per call)
	grokDefaultModel     = "grok-4.7"
	grokDefaultBaseURL   = "https://api.x.ai/v1"
	grokTriggerMe        = "me"
	grokTriggerEveryone  = "everyone"
	grokSettingsFileName = "grok.json"
)

var (
	grokMention = regexp.MustCompile(`(?i)(^|[^\w@])@grok\b`)
	groqMention = regexp.MustCompile(`(?i)(^|[^\w@])@groq\b`)
)

const (
	botGrok = "Grok"
	botGroq = "Groq"
)

// botMentioned: which bot a message calls ("" for none); if both are
// mentioned, the one written first.
func botMentioned(body string) string {
	gk, gq := grokMention.FindStringIndex(body), groqMention.FindStringIndex(body)
	switch {
	case gk == nil && gq == nil:
		return ""
	case gq == nil:
		return botGrok
	case gk == nil:
		return botGroq
	case gq[0] < gk[0]:
		return botGroq
	}
	return botGrok
}

// botOwnMessage: a reply written by one of the bots (never answered).
func botOwnMessage(body string) bool {
	body = strings.TrimSpace(body)
	for _, p := range []string{GrokReplyPrefix, GroqReplyPrefix} {
		if strings.HasPrefix(body, strings.TrimSpace(p)) {
			return true
		}
	}
	return strings.Contains(body, "From Grok:") || strings.Contains(body, "From Groq:")
}

type GrokSettings struct {
	Enabled     bool   `json:"enabled"`
	Trigger     string `json:"trigger"`      // "me" (default) or "everyone"; shared by both bots
	GroqEnabled bool   `json:"groq_enabled"` // @Groq replies
}

// GrokStatus is what Settings shows.
type GrokStatus struct {
	GrokSettings
	KeyConfigured     bool   `json:"key_configured"`
	Model             string `json:"model"`
	RepliesToday      int    `json:"replies_today"`
	DailyLimit        int    `json:"daily_limit"`
	GroqKeyConfigured bool   `json:"groq_key_configured"`
	GroqModel         string `json:"groq_model"`
	GroqSearch        bool   `json:"groq_search"` // the model has Groq's browser search
	GroqRepliesToday  int    `json:"groq_replies_today"`
	GroqDailyLimit    int    `json:"groq_daily_limit"`
}

// botLimits is one bot's rate-limit state.
type botLimits struct {
	lastChat    map[string]time.Time // conversation id -> last reply
	replies     []time.Time          // replies in the last 24 h
	pausedUntil time.Time            // after an HTTP 429 (Groq's free tier)
}

type grokState struct {
	mu         sync.Mutex
	loaded     bool
	settings   GrokSettings
	handled    map[string]time.Time // message id -> when (shared: one reply per message)
	grokLim    botLimits
	groqLim    botLimits
	client     *http.Client
	groqClient *http.Client
}

var grokStates sync.Map // *App -> *grokState

func (a *App) grok() *grokState {
	v, _ := grokStates.LoadOrStore(a, &grokState{
		handled:    map[string]time.Time{},
		grokLim:    botLimits{lastChat: map[string]time.Time{}},
		groqLim:    botLimits{lastChat: map[string]time.Time{}},
		client:     &http.Client{Timeout: grokImageReqTimeout + 5*time.Second},
		groqClient: &http.Client{Timeout: groqRequestTimeout + 5*time.Second},
	})
	return v.(*grokState)
}

func (g *grokState) limits(bot string) *botLimits {
	if bot == botGroq {
		return &g.groqLim
	}
	return &g.grokLim
}

func grokAPIKey() string { return strings.TrimSpace(os.Getenv("XAI_API_KEY")) }

func grokModel() string {
	if m := strings.TrimSpace(os.Getenv("XAI_MODEL")); m != "" {
		return m
	}
	return grokDefaultModel
}

// grokImageModel: a non-reasoning model for picture requests (the
// reasoning model kept re-searching to verify images: ~55 s, ~$0.40).
func grokImageModel() string {
	if m := strings.TrimSpace(os.Getenv("XAI_IMAGE_MODEL")); m != "" {
		return m
	}
	return grokDefaultImgModel
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
	for _, l := range []*botLimits{&g.grokLim, &g.groqLim} {
		keep := l.replies[:0]
		for _, t := range l.replies {
			if now.Sub(t) < 24*time.Hour {
				keep = append(keep, t)
			}
		}
		l.replies = keep
	}
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
	gm := groqModel()
	return GrokStatus{GrokSettings: g.settings, KeyConfigured: grokAPIKey() != "", Model: grokModel(), RepliesToday: len(g.grokLim.replies), DailyLimit: grokDailyLimit,
		GroqKeyConfigured: groqAPIKey() != "", GroqModel: gm, GroqSearch: groqHasSearch(gm), GroqRepliesToday: len(g.groqLim.replies), GroqDailyLimit: groqDailyLimit}
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
	a.Logger.Info().Bool("enabled", s.Enabled).Bool("groq_enabled", s.GroqEnabled).Str("trigger", s.Trigger).Msg("@Grok settings changed")
	return a.GrokStatus(), nil
}

// grokShouldReply decides (and reserves) a reply for a live message and
// says which bot answers.
func (a *App) grokShouldReply(m *db.Message, now time.Time) (string, bool, string) {
	if m == nil || db.IsOutgoingPlaceholderID(m.MessageID) || strings.TrimSpace(m.MessageID) == "" {
		return "", false, "not a real message"
	}
	body := strings.TrimSpace(m.Body)
	bot := botMentioned(body)
	if bot == "" {
		return "", false, "no mention"
	}
	if botOwnMessage(body) {
		return bot, false, "a bot's own message"
	}
	if strings.HasPrefix(strings.ToUpper(m.Status), "TOMBSTONE") {
		return bot, false, "tombstone"
	}
	if ts := time.UnixMilli(m.TimestampMS); m.TimestampMS <= 0 || now.Sub(ts) > grokMaxMessageAge || ts.Sub(now) > grokMaxMessageAge {
		return bot, false, "not a live message"
	}
	g := a.grok()
	g.mu.Lock()
	defer g.mu.Unlock()
	g.loadLocked(a.grokSettingsPath())
	interval, daily := grokPerChatInterval, grokDailyLimit
	if bot == botGroq {
		if !g.settings.GroqEnabled {
			return bot, false, "disabled"
		}
		if groqAPIKey() == "" {
			return bot, false, "no GROQ_API_KEY"
		}
		interval, daily = groqPerChatInterval, groqDailyLimit
	} else {
		if !g.settings.Enabled {
			return bot, false, "disabled"
		}
		if grokAPIKey() == "" {
			return bot, false, "no XAI_API_KEY"
		}
	}
	if g.settings.Trigger != grokTriggerEveryone && !m.IsFromMe {
		return bot, false, "only you can trigger"
	}
	if _, seen := g.handled[m.MessageID]; seen {
		return bot, false, "already handled"
	}
	g.pruneLocked(now)
	lim := g.limits(bot)
	if now.Before(lim.pausedUntil) {
		g.handled[m.MessageID] = now
		return bot, false, "provider rate limit"
	}
	if t, ok := lim.lastChat[m.ConversationID]; ok && now.Sub(t) < interval {
		g.handled[m.MessageID] = now
		return bot, false, "chat rate limit"
	}
	if len(lim.replies) >= daily {
		g.handled[m.MessageID] = now
		return bot, false, "daily limit"
	}
	g.handled[m.MessageID] = now
	lim.lastChat[m.ConversationID] = now
	lim.replies = append(lim.replies, now)
	return bot, true, ""
}

// HandleLiveMessageForGrok is called for every new live message (yours and
// others'). Replies (@Grok or @Groq) run in the background.
func (a *App) HandleLiveMessageForGrok(m *db.Message) {
	if a == nil || m == nil || botMentioned(m.Body) == "" {
		return
	}
	bot, ok, why := a.grokShouldReply(m, time.Now())
	if !ok {
		a.Logger.Debug().Str("conv_id", m.ConversationID).Str("bot", bot).Str("reason", why).Msg("@Grok/@Groq mention ignored")
		return
	}
	msg := *m
	go a.botReply(&msg, a.chatBot(bot))
}

// chatBot is one answering bot: its reply prefix and request function.
type chatBot struct {
	name     string
	prefix   string
	timeout  func(wantImage bool) time.Duration
	complete func(ctx context.Context, conversation string, wantImage bool) (grokAnswer, error)
}

func (a *App) chatBot(name string) chatBot {
	if name == botGroq {
		return chatBot{name: botGroq, prefix: GroqReplyPrefix, timeout: func(bool) time.Duration { return groqRequestTimeout }, complete: a.groqComplete}
	}
	return chatBot{name: botGrok, prefix: GrokReplyPrefix, timeout: grokTimeout, complete: a.grokComplete}
}

func (a *App) botReply(m *db.Message, bot chatBot) {
	log := a.Logger.With().Str("conv_id", m.ConversationID).Str("bot", bot.name).Logger()
	wantImage := grokWantsImage(m.Body)
	ctx, cancel := context.WithTimeout(context.Background(), bot.timeout(wantImage))
	defer cancel()
	prompt := a.grokContext(m)
	started := time.Now()
	ans, err := bot.complete(ctx, prompt, wantImage)
	if err != nil {
		log.Warn().Str("error", grokSafeErr(err)).Dur("took", time.Since(started)).Msg("@" + bot.name + " reply failed")
		return
	}
	reply := grokTrimReply(ans.Text)
	// At most one image: downloaded and validated before anything is sent;
	// if that fails, the text carries the link instead.
	var img []byte
	var imgMime, imgName string
	if ans.ImageURL != "" {
		var ierr error
		img, imgMime, imgName, ierr = fetchGrokImage(context.Background(), ans.ImageURL)
		if ierr != nil {
			log.Warn().Str("error", ierr.Error()).Msg("@" + bot.name + " image rejected; sending text only")
			img = nil
			if errors.Is(ierr, errGrokImageTooLarge) { // real but too big for MMS: the link helps
				reply = grokWithSource(reply, ans.ImageURL)
			}
		}
	}
	if img == nil && reply != "" {
		reply = grokWithSource(reply, ans.Source)
	}
	if reply == "" && img != nil {
		reply = "Here you go."
	}
	if reply == "" {
		log.Warn().Msg("@" + bot.name + " returned an empty reply")
		return
	}
	if _, _, err := a.SendTextToConversation(m.ConversationID, bot.prefix+reply); err != nil {
		log.Warn().Err(err).Msg("@" + bot.name + " reply send failed")
		return
	}
	if img != nil {
		if _, err := a.SendMediaToConversation(m.ConversationID, img, imgName, imgMime, "", ""); err != nil {
			log.Warn().Err(err).Msg("@" + bot.name + " image send failed")
		} else {
			log.Info().Str("mime", imgMime).Int("bytes", len(img)).Msg("@" + bot.name + " sent an image")
		}
	}
	log.Info().Int("reply_chars", len([]rune(reply))).Int("search_calls", ans.Searches).Int("tokens", ans.Tokens).Float64("cost_usd", ans.CostUSD).Dur("took", time.Since(started)).Msg("@" + bot.name + " replied")
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
	for name, p := range map[string]string{botGrok: GrokReplyPrefix, botGroq: GroqReplyPrefix} {
		if strings.HasPrefix(body, strings.TrimSpace(p)) {
			who = name
			body = strings.TrimSpace(strings.TrimPrefix(body, strings.TrimSpace(p)))
		}
	}
	if r := []rune(body); len(r) > grokContextChars {
		body = string(r[:grokContextChars]) + "…"
	}
	return who + ": " + body
}

func grokTrimReply(s string) string {
	s = grokPlainText(s)
	s = strings.TrimPrefix(s, strings.TrimSpace(GrokReplyPrefix))
	s = strings.TrimPrefix(s, strings.TrimSpace(GroqReplyPrefix))
	s = strings.TrimSpace(s)
	if r := []rune(s); len(r) > grokReplyMaxChars {
		s = strings.TrimSpace(string(r[:grokReplyMaxChars])) + "…"
	}
	return s
}

var (
	grokMDImage     = regexp.MustCompile(`!\[[^\]]*\]\([^)]*\)`)
	grokMDLink      = regexp.MustCompile(`\[([^\]]+)\]\((https?://[^)\s]+)\)`)
	grokCiteMarker  = regexp.MustCompile(`\s*\[\[?\d+\]?\](\([^)]*\))?`)
	grokMDEmphasis  = regexp.MustCompile(`(\*\*|__|\*|~~|` + "`" + `)`)
	grokMDHeading   = regexp.MustCompile(`(?m)^\s{0,3}#{1,6}\s+`)
	grokMDBullet    = regexp.MustCompile(`(?m)^\s*[-*+]\s+`)
	grokBareURL     = regexp.MustCompile(`\(?https?://[^\s)]*[^\s).,;:!?'"]\)?`)
	grokSpaces      = regexp.MustCompile(`[ \t]+`)
	grokBlankLines  = regexp.MustCompile(`\n{3,}`)
	grokSpaceBefore = regexp.MustCompile(`\s+([.,;:!?])`)
	grokLenticular  = regexp.MustCompile(`\s*【[^】]*】`) // gpt-oss citations like 【2†L6-L10】
	grokThink       = regexp.MustCompile(`(?s)<think>.*?</think>`)
	// SMS-friendly: no-break and narrow spaces, non-breaking hyphens.
	grokUnicodeFix = strings.NewReplacer("\u00a0", " ", "\u202f", " ", "\u2009", " ", "\u2007", " ", "\u2011", "-", "\u2010", "-")
)

// grokPlainText turns a markdown answer into SMS-friendly plain text:
// no emphasis, headings, images, citation markers or inline URLs (links keep
// their text), bullets as "• ".
func grokPlainText(s string) string {
	s = strings.ReplaceAll(s, "\r\n", "\n")
	s = grokThink.ReplaceAllString(s, "")
	s = grokUnicodeFix.Replace(s)
	s = grokLenticular.ReplaceAllString(s, "")
	s = grokMDImage.ReplaceAllString(s, "")
	s = grokCiteMarker.ReplaceAllString(s, "")
	s = grokMDLink.ReplaceAllString(s, "$1")
	s = grokBareURL.ReplaceAllString(s, "")
	s = grokMDHeading.ReplaceAllString(s, "")
	s = grokMDBullet.ReplaceAllString(s, "• ")
	s = grokMDEmphasis.ReplaceAllString(s, "")
	s = grokSpaces.ReplaceAllString(s, " ")
	s = grokSpaceBefore.ReplaceAllString(s, "$1")
	lines := strings.Split(s, "\n")
	for i, l := range lines {
		lines[i] = strings.TrimSpace(l)
	}
	s = grokBlankLines.ReplaceAllString(strings.Join(lines, "\n"), "\n\n")
	return strings.TrimSpace(s)
}

// grokWithSource appends one short source link when it fits the cap.
func grokWithSource(reply, source string) string {
	if source == "" || len(source) > 80 || strings.ContainsAny(source, " \n") {
		return reply
	}
	out := reply + "\n" + source
	if len([]rune(out)) > grokReplyMaxChars+1 {
		return reply
	}
	return out
}

// grokZone: GROK_TIMEZONE (an IANA name such as America/New_York), else the
// server's local zone.
func grokZone() *time.Location {
	if tz := strings.TrimSpace(os.Getenv("GROK_TIMEZONE")); tz != "" {
		if loc, err := time.LoadLocation(tz); err == nil {
			return loc
		}
	}
	return time.Local
}

// grokSystemPrompt carries the current local date and time so "tonight",
// "this month" etc. resolve correctly, plus the optional owner name
// (GROK_USER_NAME) and home area (GROK_USER_LOCATION) for "near me"
// questions. Nothing personal is built in. Picture
// requests get a lean prompt (one Wikimedia search, the URL on a last line);
// mixing them with the "search for anything current" and "no URLs" rules
// made the model loop on searches.
func grokSystemPrompt(now time.Time, wantImage bool) string {
	return botSystemPrompt(botGrok, "search the web or X", now, wantImage)
}

// grokDateHints spells out "tomorrow" and "this weekend" (models slip on
// weekday arithmetic, e.g. "this weekend" for movie openings).
func grokDateHints(lt time.Time) string {
	day := func(t time.Time) string { return t.Format("Monday, January 2") }
	sat := lt.AddDate(0, 0, (int(time.Saturday)-int(lt.Weekday())+7)%7)
	if lt.Weekday() == time.Sunday {
		sat = lt.AddDate(0, 0, -1)
	}
	return "Tomorrow is " + day(lt.AddDate(0, 0, 1)) + "; this weekend is " + day(sat) + " to " + day(sat.AddDate(0, 0, 1)) + ". "
}

// botSystemPrompt: name is the bot ("Grok", "Groq"); search says how it
// looks things up ("" = it can't, so it must say when live info is needed).
func botSystemPrompt(name, search string, now time.Time, wantImage bool) string {
	loc := grokZone()
	lt := now.In(loc)
	owner := strings.TrimSpace(os.Getenv("GROK_USER_NAME"))
	if owner == "" {
		owner = "the phone's owner"
	}
	home := strings.TrimSpace(os.Getenv("GROK_USER_LOCATION"))
	base := "You are " + name + ", replying inside a text-message conversation because someone wrote @" + name + ". " +
		"Right now it is " + lt.Format("Monday, January 2, 2006, 3:04 PM MST") + " (" + loc.String() + " time). " + grokDateHints(lt) +
		"Messages marked \"Me\" are from " + owner
	if home != "" {
		base += ", who lives in the " + home + " area"
	}
	if wantImage {
		const imageLine = "reply with one short plain-text sentence describing it, then put that photo's Commons file page URL (a jpg, png, gif or webp file) alone on the last line as: IMAGE: https://commons.wikimedia.org/wiki/File:<file name>"
		if search == "" {
			return base + ". They want a picture: pick one real, well-known photo on Wikimedia Commons whose exact file name you are sure of (never generate, guess or invent one; if you aren't sure, say so and skip the IMAGE line), " + imageLine
		}
		return base + ". They want a picture: do one web search on Wikimedia Commons, pick a real existing photo from the results (never generate, guess or invent one; use the file name exactly as in the results), " + imageLine
	}
	if home != "" {
		base += "; use that for local questions (weather, showtimes, events, \"near me\") unless another place is named"
	}
	live := "For anything current (news, releases, schedules, prices, scores, weather, events) " + search + " first and give concrete, dated facts. "
	if search == "" {
		live = "You can't look up live information. If a question needs current info (news, weather, scores, schedules, prices, events), say briefly that you can't check live info, then give your best general answer. "
	}
	return base + ". Answer the latest message that mentions @" + name + ", using the recent messages for context. " + live +
		"Reply in plain text like a text message: at most about 500 characters, no markdown, no tables, no headings, no citations or URLs, no preamble."
}

var errGrokHTTP = errors.New("xAI API error")

// grokAnswer is Grok's reply text plus the first cited URL, if any.
type grokAnswer struct {
	Text     string
	Source   string
	ImageURL string  // a real image found by search ("IMAGE: <url>" line)
	Searches int     // server-side tool calls (web/X search), billed per call
	CostUSD  float64 // xAI's reported cost for the request (tokens + tools)
	Tokens   int     // total tokens (Groq's free tier is limited by tokens)
}

// grokTimeout: picture requests get longer (image search is slower).
func grokTimeout(wantImage bool) time.Duration {
	if wantImage {
		return grokImageReqTimeout
	}
	return grokRequestTimeout
}

// grokComplete asks Grok through the Responses API with xAI's server-side
// web_search and x_search tools (live data; the tool calls run on xAI's
// side and are billed per call).
func (a *App) grokComplete(ctx context.Context, conversation string, wantImage bool) (grokAnswer, error) {
	key := grokAPIKey()
	if key == "" {
		return grokAnswer{}, errors.New("XAI_API_KEY not set")
	}
	// Picture requests: a web search limited to Wikimedia with a fast
	// non-reasoning model. xAI's image search returns no URLs to the model
	// (it guessed dead links), and the reasoning model looped on it (~55 s,
	// ~$0.40); Wikimedia results carry real upload.wikimedia.org file URLs.
	tools := []map[string]any{{"type": "web_search"}, {"type": "x_search"}}
	turns := grokMaxSearchTurns
	model := grokModel()
	if wantImage {
		tools = []map[string]any{{"type": "web_search", "filters": map[string]any{
			"allowed_domains": grokImageDomains}}}
		turns = 2
		model = grokImageModel()
	}
	body := map[string]any{
		"model": model,
		"input": []map[string]string{
			{"role": "system", "content": grokSystemPrompt(time.Now(), wantImage)},
			{"role": "user", "content": "Recent messages (oldest first):\n" + conversation},
		},
		"tools":     tools,
		"max_turns": turns,
		"store":     false,
		"stream":    false,
	}
	if wantImage {
		body["tool_choice"] = "required" // a real image from search, not memory
	} else if !strings.Contains(model, "non-reasoning") {
		body["reasoning"] = map[string]string{"effort": "low"}
	}
	b, _ := json.Marshal(body)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, grokBaseURL()+"/responses", bytes.NewReader(b))
	if err != nil {
		return grokAnswer{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+key)
	resp, err := a.grok().client.Do(req)
	if err != nil {
		return grokAnswer{}, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode != http.StatusOK {
		var e struct {
			Error any    `json:"error"`
			Code  string `json:"code"`
		}
		_ = json.Unmarshal(raw, &e)
		detail := ""
		if m, ok := e.Error.(map[string]any); ok {
			detail, _ = m["message"].(string)
		} else if s, ok := e.Error.(string); ok {
			detail = s
		}
		if r := []rune(detail); len(r) > 200 {
			detail = string(r[:200])
		}
		return grokAnswer{}, fmt.Errorf("%w: HTTP %d %s", errGrokHTTP, resp.StatusCode, detail)
	}
	return parseGrokResponse(raw)
}

func parseGrokResponse(raw []byte) (grokAnswer, error) {
	var out struct {
		Output []struct {
			Type    string `json:"type"`
			Content []struct {
				Type        string `json:"type"`
				Text        string `json:"text"`
				Annotations []struct {
					Type string `json:"type"`
					URL  string `json:"url"`
				} `json:"annotations"`
			} `json:"content"`
		} `json:"output"`
		Citations []string `json:"citations"`
		Usage     struct {
			CostTicks int64 `json:"cost_in_usd_ticks"`
			Tools     struct {
				Web int `json:"web_search_calls"`
				X   int `json:"x_search_calls"`
			} `json:"server_side_tool_usage_details"`
		} `json:"usage"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return grokAnswer{}, fmt.Errorf("decode xAI response: %w", err)
	}
	var ans grokAnswer
	var texts []string
	for _, item := range out.Output {
		if strings.HasSuffix(item.Type, "_call") {
			ans.Searches++
		}
		if item.Type != "message" {
			continue
		}
		for _, c := range item.Content {
			if c.Type != "output_text" {
				continue
			}
			texts = append(texts, c.Text)
			for _, an := range c.Annotations {
				if ans.Source == "" && an.Type == "url_citation" && strings.HasPrefix(an.URL, "https://") {
					ans.Source = an.URL
				}
			}
		}
	}
	if ans.Source == "" {
		for _, u := range out.Citations {
			if strings.HasPrefix(u, "https://") {
				ans.Source = u
				break
			}
		}
	}
	if n := out.Usage.Tools.Web + out.Usage.Tools.X; n > 0 {
		ans.Searches = n
	}
	ans.CostUSD = float64(out.Usage.CostTicks) / 1e10
	ans.Text, ans.ImageURL = grokExtractImage(strings.Join(texts, "\n"))
	if strings.TrimSpace(ans.Text) == "" && ans.ImageURL == "" {
		return ans, errors.New("xAI response had no text")
	}
	return ans, nil
}

// GrokTestAnswer runs the real request path (no message is sent) and
// returns the SMS-ready reply; used by the "grok-test" command.
func (a *App) GrokTestAnswer(question string) (string, int, error) {
	return a.BotTestAnswer(botGrok, question)
}

// BotTestAnswer is GrokTestAnswer for either bot ("Grok" or "Groq").
func (a *App) BotTestAnswer(name, question string) (string, int, error) {
	bot := a.chatBot(name)
	wantImage := grokWantsImage(question)
	ctx, cancel := context.WithTimeout(context.Background(), bot.timeout(wantImage))
	defer cancel()
	ans, err := bot.complete(ctx, "Me: "+question, wantImage)
	if err != nil {
		return "", 0, errors.New(grokSafeErr(err))
	}
	// Same text/link rules as grokReply; nothing is sent.
	reply, note := grokTrimReply(ans.Text), ""
	imgOK := false
	if ans.ImageURL != "" {
		data, mime, name, err := fetchGrokImage(context.Background(), ans.ImageURL)
		if err != nil {
			note = "\n[image " + ans.ImageURL + " rejected: " + err.Error() + "; text only]"
			if errors.Is(err, errGrokImageTooLarge) {
				reply = grokWithSource(reply, ans.ImageURL)
			}
		} else {
			imgOK = true
			note = fmt.Sprintf("\n[image %s OK: %s, %d bytes, would send as MMS %s after the text]", ans.ImageURL, mime, len(data), name)
		}
	}
	if !imgOK && reply != "" {
		reply = grokWithSource(reply, ans.Source)
	}
	usage := fmt.Sprintf("\n[xAI cost $%.3f]", ans.CostUSD)
	if bot.name == botGroq {
		usage = fmt.Sprintf("\n[Groq %s: %d tokens, free tier]", groqModel(), ans.Tokens)
	}
	out := bot.prefix + reply + usage + note
	return out, ans.Searches, nil
}

// grokSafeErr describes an error without anything secret (the key is only
// ever in a header, never in the URL or error text).
func grokSafeErr(err error) string {
	s := err.Error()
	for _, k := range []string{grokAPIKey(), groqAPIKey()} {
		if k != "" {
			s = strings.ReplaceAll(s, k, "[key]")
		}
	}
	return grokKeyLikeRe.ReplaceAllString(s, "[key]") // e.g. a partially echoed key
}

var grokKeyLikeRe = regexp.MustCompile(`(xai|gsk)[-_][A-Za-z0-9*_.-]{4,}`)
