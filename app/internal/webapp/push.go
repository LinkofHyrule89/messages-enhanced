package webapp

// Web Push: VAPID keys + per-device subscriptions live in the data dir; a
// push goes out for each new incoming message (never your own) so phones and
// tablets get notified with the page closed. The car browser has no Push
// API, so the car page just reports "unsupported" there.

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	webpush "github.com/SherClockHolmes/webpush-go"
	"github.com/rs/zerolog"
)

const (
	pushVAPIDFile = "push-vapid.json"
	pushSubsFile  = "push-subscriptions.json"
	// pushMaxAge: messages older than this when we see them (backfill, a
	// reconnect replaying history) never notify.
	pushMaxAge      = 2 * time.Minute
	pushDebounce    = 600 * time.Millisecond
	pushMaxSubs     = 50
	pushSnippetMax  = 180
	pushSeenMax     = 4000
	pushDefaultSubj = "mailto:messages-enhanced@users.noreply.github.com"
)

// PushMessage is the slice of a stored message the notifier needs.
type PushMessage struct {
	ID          string
	SenderName  string
	SenderNum   string
	Body        string
	MimeType    string
	HasMedia    bool
	TimestampMS int64
	FromMe      bool
	MentionsMe  bool
}

// PushConversation is the conversation context for a notification.
type PushConversation struct {
	ID               string
	Name             string
	IsGroup          bool
	NotificationMode string // "", all, mentions, muted
}

// PushSource loads the newest messages (newest first) of a conversation.
type PushSource func(convID string, limit int) (PushConversation, []PushMessage, error)

// PushSubscription is one device's browser push subscription.
type PushSubscription struct {
	Endpoint string       `json:"endpoint"`
	Keys     webpush.Keys `json:"keys"`
	HideText bool         `json:"hide_text"`
	Label    string       `json:"label,omitempty"`
	// Origin is the page origin the subscription was made from (e.g.
	// https://messages.example.ts.net), so subscriptions from an old
	// address can be told apart and pruned.
	Origin    string `json:"origin,omitempty"`
	CreatedMS int64  `json:"created_ms"`
	LastOKMS  int64  `json:"last_ok_ms,omitempty"`
}

// pushSender delivers one encrypted payload; returns the push service's
// HTTP status. Swapped out in tests.
type pushSender func(ctx context.Context, sub *PushSubscription, payload []byte) (int, error)

type PushHub struct {
	mu       sync.Mutex
	dir      string
	pub      string
	priv     string
	subject  string
	subs     map[string]*PushSubscription
	source   PushSource
	send     pushSender
	logger   zerolog.Logger
	pending  map[string]*time.Timer
	seen     map[string]struct{}
	seenList []string
	now      func() time.Time
	debounce time.Duration
	// allowHTTP permits http:// endpoints (tests only).
	allowHTTP bool
	wg        sync.WaitGroup
}

type vapidFile struct {
	Public  string `json:"public"`
	Private string `json:"private"`
}

// NewPushHub creates the hub before the data source exists (the app wires
// OnMessagesChange early); call Open once the data dir is known.
func NewPushHub() *PushHub {
	h := &PushHub{
		subs:     map[string]*PushSubscription{},
		pending:  map[string]*time.Timer{},
		seen:     map[string]struct{}{},
		now:      time.Now,
		debounce: pushDebounce,
		subject:  pushDefaultSubj,
		logger:   zerolog.Nop(),
	}
	h.send = h.webpushSend
	return h
}

// Open loads (or creates, 0600) the VAPID keys and the subscription list.
func (h *PushHub) Open(dataDir string, source PushSource, logger zerolog.Logger) error {
	if h == nil {
		return errors.New("nil push hub")
	}
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return err
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	h.dir = dataDir
	h.source = source
	h.logger = logger
	if s := strings.TrimSpace(Getenv("MESSAGES_PUSH_SUBJECT")); s != "" {
		h.subject = s
	}
	vp := filepath.Join(dataDir, pushVAPIDFile)
	var vf vapidFile
	if b, err := os.ReadFile(vp); err == nil {
		if err := json.Unmarshal(b, &vf); err != nil || !validVAPIDPublic(vf.Public) || vf.Private == "" {
			return fmt.Errorf("push: %s is unreadable; delete it to generate new keys", pushVAPIDFile)
		}
	} else if errors.Is(err, os.ErrNotExist) {
		priv, pub, err := webpush.GenerateVAPIDKeys()
		if err != nil {
			return err
		}
		vf = vapidFile{Public: pub, Private: priv}
		b, _ := json.Marshal(vf)
		if err := writeFileAtomic(vp, b); err != nil {
			return err
		}
		logger.Info().Msg("Generated Web Push (VAPID) keys")
	} else {
		return err
	}
	_ = os.Chmod(vp, 0o600)
	h.pub, h.priv = vf.Public, vf.Private
	if b, err := os.ReadFile(filepath.Join(dataDir, pushSubsFile)); err == nil {
		var list []*PushSubscription
		if json.Unmarshal(b, &list) == nil {
			for _, s := range list {
				if s != nil && s.Endpoint != "" {
					h.subs[s.Endpoint] = s
				}
			}
		}
	}
	return nil
}

func validVAPIDPublic(k string) bool {
	b, err := base64.RawURLEncoding.DecodeString(k)
	return err == nil && len(b) == 65 && b[0] == 4
}

// Ready reports whether keys are loaded.
func (h *PushHub) Ready() bool {
	if h == nil {
		return false
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.pub != ""
}

func (h *PushHub) PublicKey() string {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.pub
}

func (h *PushHub) saveLocked() error {
	list := make([]*PushSubscription, 0, len(h.subs))
	for _, s := range h.subs {
		list = append(list, s)
	}
	sort.Slice(list, func(i, j int) bool { return list[i].CreatedMS < list[j].CreatedMS })
	b, err := json.MarshalIndent(list, "", " ")
	if err != nil {
		return err
	}
	return writeFileAtomic(filepath.Join(h.dir, pushSubsFile), b)
}

// validateSubscription checks what the browser sent: an https push-service
// endpoint (no IP literals / localhost, so this can't be aimed at the LAN)
// and well-formed P-256 / auth keys.
func (h *PushHub) validateSubscription(s *PushSubscription) error {
	if len(s.Endpoint) > 2048 {
		return errors.New("endpoint too long")
	}
	u, err := url.Parse(s.Endpoint)
	if err != nil || u.Host == "" || u.User != nil {
		return errors.New("invalid endpoint")
	}
	if u.Scheme != "https" && !(h.allowHTTP && u.Scheme == "http") {
		return errors.New("endpoint must be https")
	}
	if !h.allowHTTP {
		host := strings.ToLower(u.Hostname())
		if net.ParseIP(host) != nil || host == "localhost" || strings.HasSuffix(host, ".localhost") || strings.HasSuffix(host, ".local") || !strings.Contains(host, ".") {
			return errors.New("endpoint must be a public push service")
		}
	}
	p, err := decodeB64Any(s.Keys.P256dh)
	if err != nil || len(p) != 65 || p[0] != 4 {
		return errors.New("invalid p256dh key")
	}
	a, err := decodeB64Any(s.Keys.Auth)
	if err != nil || len(a) != 16 {
		return errors.New("invalid auth key")
	}
	return nil
}

func decodeB64Any(s string) ([]byte, error) {
	s = strings.TrimRight(strings.TrimSpace(s), "=")
	if b, err := base64.RawURLEncoding.DecodeString(s); err == nil {
		return b, nil
	}
	return base64.RawStdEncoding.DecodeString(s)
}

// Subscribe adds or refreshes a device. hideText nil keeps the stored value.
func (h *PushHub) Subscribe(s PushSubscription, hideText *bool) (*PushSubscription, error) {
	if err := h.validateSubscription(&s); err != nil {
		return nil, err
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.pub == "" {
		return nil, errors.New("push not available")
	}
	cur, ok := h.subs[s.Endpoint]
	if !ok {
		if len(h.subs) >= pushMaxSubs {
			h.evictOldestLocked()
		}
		cur = &PushSubscription{Endpoint: s.Endpoint, CreatedMS: h.now().UnixMilli()}
		h.subs[s.Endpoint] = cur
	}
	cur.Keys = s.Keys
	if s.Label != "" {
		cur.Label = s.Label
	}
	if s.Origin != "" {
		cur.Origin = s.Origin
	}
	if hideText != nil {
		cur.HideText = *hideText
	}
	if err := h.saveLocked(); err != nil {
		return nil, err
	}
	c := *cur
	return &c, nil
}

func (h *PushHub) evictOldestLocked() {
	var oldest *PushSubscription
	for _, s := range h.subs {
		if oldest == nil || s.CreatedMS < oldest.CreatedMS {
			oldest = s
		}
	}
	if oldest != nil {
		delete(h.subs, oldest.Endpoint)
	}
}

func (h *PushHub) Unsubscribe(endpoint string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	if _, ok := h.subs[endpoint]; !ok {
		return false
	}
	delete(h.subs, endpoint)
	_ = h.saveLocked()
	return true
}

func (h *PushHub) Get(endpoint string) (*PushSubscription, bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	s, ok := h.subs[endpoint]
	if !ok {
		return nil, false
	}
	c := *s
	return &c, true
}

func (h *PushHub) SetHideText(endpoint string, hide bool) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	s, ok := h.subs[endpoint]
	if !ok {
		return false
	}
	s.HideText = hide
	_ = h.saveLocked()
	return true
}

func (h *PushHub) Count() int {
	if h == nil {
		return 0
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.subs)
}

// MessagesChanged is chained onto the app's OnMessagesChange. It fires for
// every change (new message, status update, own send, reaction...), so it
// only schedules a debounced look at the conversation.
func (h *PushHub) MessagesChanged(convID string) {
	if h == nil || convID == "" {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.source == nil || len(h.subs) == 0 {
		return
	}
	if t, ok := h.pending[convID]; ok {
		t.Reset(h.debounce)
		return
	}
	h.pending[convID] = time.AfterFunc(h.debounce, func() {
		h.mu.Lock()
		delete(h.pending, convID)
		h.mu.Unlock()
		h.check(convID)
	})
}

// notification is what the service worker receives.
type notification struct {
	Title string `json:"title"`
	Body  string `json:"body"`
	Tag   string `json:"tag"`
	URL   string `json:"url"`
	Conv  string `json:"conv,omitempty"`
	TS    int64  `json:"ts"`
	Test  bool   `json:"test,omitempty"`
}

func (h *PushHub) markSeenLocked(id string) bool {
	if _, ok := h.seen[id]; ok {
		return false
	}
	h.seen[id] = struct{}{}
	h.seenList = append(h.seenList, id)
	if len(h.seenList) > pushSeenMax {
		drop := h.seenList[:len(h.seenList)-pushSeenMax]
		for _, d := range drop {
			delete(h.seen, d)
		}
		h.seenList = append([]string(nil), h.seenList[len(drop):]...)
	}
	return true
}

func (h *PushHub) check(convID string) {
	conv, msgs, err := h.source(convID, 8)
	if err != nil {
		h.logger.Debug().Err(err).Msg("push: load conversation failed")
		return
	}
	cutoff := h.now().Add(-pushMaxAge).UnixMilli()
	var fresh []PushMessage
	h.mu.Lock()
	for _, m := range msgs {
		if m.ID == "" || m.FromMe || m.TimestampMS < cutoff {
			continue
		}
		if conv.NotificationMode == "muted" || (conv.NotificationMode == "mentions" && !m.MentionsMe) {
			h.markSeenLocked(m.ID)
			continue
		}
		if pushSnippet(m) == "" {
			continue // placeholder (body not decoded yet): look again on the next change
		}
		if h.markSeenLocked(m.ID) {
			fresh = append(fresh, m)
		}
	}
	h.mu.Unlock()
	if len(fresh) == 0 {
		return
	}
	latest := fresh[0] // msgs are newest first
	h.broadcast(conv, latest, len(fresh))
}

// pushSnippet is the notification text for a message ("" = nothing to show
// yet).
func pushSnippet(m PushMessage) string {
	body := strings.Join(strings.Fields(m.Body), " ")
	if body == "" && m.HasMedia {
		switch mt := strings.ToLower(m.MimeType); {
		case strings.HasPrefix(mt, "image/"):
			body = "📷 Photo"
		case strings.HasPrefix(mt, "video/"):
			body = "🎬 Video"
		case strings.HasPrefix(mt, "audio/"):
			body = "🎤 Audio"
		default:
			body = "📎 Attachment"
		}
	}
	if utf8.RuneCountInString(body) > pushSnippetMax {
		r := []rune(body)
		body = string(r[:pushSnippetMax-1]) + "…"
	}
	return body
}

func pushTag(convID string) string {
	sum := sha256.Sum256([]byte(convID))
	return "conv-" + hex.EncodeToString(sum[:8])
}

func buildNotification(conv PushConversation, m PushMessage, count int, hide bool, now time.Time) notification {
	sender := strings.TrimSpace(m.SenderName)
	if sender == "" {
		sender = strings.TrimSpace(m.SenderNum)
	}
	if sender == "" {
		sender = strings.TrimSpace(conv.Name)
	}
	if sender == "" {
		sender = "New message"
	}
	title := sender
	if conv.IsGroup && conv.Name != "" && conv.Name != sender {
		title = sender + " · " + conv.Name
	}
	body := pushSnippet(m)
	if hide {
		body = "New message"
		if count > 1 {
			body = fmt.Sprintf("%d new messages", count)
		}
	} else if count > 1 {
		body = fmt.Sprintf("%s (+%d more)", body, count-1)
	}
	return notification{
		Title: title,
		Body:  body,
		Tag:   pushTag(conv.ID),
		URL:   "/app/?c=" + url.QueryEscape(conv.ID),
		Conv:  conv.ID,
		TS:    now.UnixMilli(),
	}
}

func (h *PushHub) broadcast(conv PushConversation, m PushMessage, count int) {
	h.mu.Lock()
	targets := make([]PushSubscription, 0, len(h.subs))
	for _, s := range h.subs {
		targets = append(targets, *s)
	}
	h.mu.Unlock()
	for i := range targets {
		sub := targets[i]
		n := buildNotification(conv, m, count, sub.HideText, h.now())
		h.wg.Add(1)
		go func() {
			defer h.wg.Done()
			_, _ = h.deliver(&sub, n)
		}()
	}
}

// deliver sends one notification and prunes subscriptions the push service
// says are gone (404/410).
func (h *PushHub) deliver(sub *PushSubscription, n notification) (int, error) {
	payload, _ := json.Marshal(n)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	status, err := h.send(ctx, sub, payload)
	host := ""
	if u, perr := url.Parse(sub.Endpoint); perr == nil {
		host = u.Host
	}
	switch {
	case err != nil:
		h.logger.Warn().Str("push_service", host).Err(err).Msg("push: send failed")
	case status == http.StatusNotFound || status == http.StatusGone:
		h.logger.Info().Str("push_service", host).Int("status", status).Msg("push: subscription expired; removed")
		h.Unsubscribe(sub.Endpoint)
	case status >= 200 && status < 300:
		h.mu.Lock()
		if s, ok := h.subs[sub.Endpoint]; ok {
			s.LastOKMS = h.now().UnixMilli()
		}
		h.mu.Unlock()
	default:
		h.logger.Warn().Str("push_service", host).Int("status", status).Msg("push: push service rejected the message")
		if err == nil {
			err = fmt.Errorf("push service returned HTTP %d", status)
		}
	}
	return status, err
}

// SendTest pushes a test notification to one device, synchronously.
func (h *PushHub) SendTest(endpoint string) (int, error) {
	sub, ok := h.Get(endpoint)
	if !ok {
		return 0, errors.New("this device isn't subscribed")
	}
	body := "Notifications are working on this device."
	if sub.HideText {
		body = "Notifications are working (message text hidden)."
	}
	return h.deliver(sub, notification{
		Title: "Messages Enhanced", Body: body, Tag: "test", URL: "/app/", TS: h.now().UnixMilli(), Test: true,
	})
}

func (h *PushHub) webpushSend(ctx context.Context, sub *PushSubscription, payload []byte) (int, error) {
	h.mu.Lock()
	pub, priv, subj := h.pub, h.priv, h.subject
	h.mu.Unlock()
	resp, err := webpush.SendNotificationWithContext(ctx, payload, &webpush.Subscription{Endpoint: sub.Endpoint, Keys: sub.Keys}, &webpush.Options{
		Subscriber:      subj,
		VAPIDPublicKey:  pub,
		VAPIDPrivateKey: priv,
		TTL:             6 * 3600,
		Urgency:         webpush.UrgencyHigh,
		HTTPClient:      &http.Client{Timeout: 20 * time.Second},
	})
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
	return resp.StatusCode, nil
}

// Wait blocks until in-flight sends finish (tests).
func (h *PushHub) Wait() { h.wg.Wait() }

// ---------- HTTP API (behind the login gate + same-origin write check) ----------

func (s *Server) registerPushRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/app/push/config", s.handlePushConfig)
	mux.HandleFunc("/api/app/push/subscribe", s.handlePushSubscribe)
	mux.HandleFunc("/api/app/push/unsubscribe", s.handlePushUnsubscribe)
	mux.HandleFunc("/api/app/push/settings", s.handlePushSettings)
	mux.HandleFunc("/api/app/push/test", s.handlePushTest)
}

func (s *Server) push() *PushHub {
	if s.deps.Push != nil && s.deps.Push.Ready() {
		return s.deps.Push
	}
	return nil
}

func (s *Server) handlePushConfig(w http.ResponseWriter, r *http.Request) {
	h := s.push()
	if h == nil {
		writeJSON(w, 200, map[string]any{"available": false})
		return
	}
	writeJSON(w, 200, map[string]any{"available": true, "public_key": h.PublicKey(), "devices": h.Count()})
}

type pushReq struct {
	Endpoint string       `json:"endpoint"`
	Keys     webpush.Keys `json:"keys"`
	HideText *bool        `json:"hide_text"`
	Label    string       `json:"label"`
}

func readPushReq(w http.ResponseWriter, r *http.Request) (*pushReq, bool) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]string{"error": "method not allowed"})
		return nil, false
	}
	r.Body = http.MaxBytesReader(w, r.Body, 16<<10)
	var req pushReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Endpoint == "" {
		writeJSON(w, 400, map[string]string{"error": "endpoint required"})
		return nil, false
	}
	return &req, true
}

func (s *Server) handlePushSubscribe(w http.ResponseWriter, r *http.Request) {
	h := s.push()
	if h == nil {
		writeJSON(w, 503, map[string]string{"error": "push notifications are not available on this server"})
		return
	}
	req, ok := readPushReq(w, r)
	if !ok {
		return
	}
	label := req.Label
	if label == "" {
		label = deviceLabel(r.UserAgent())
	}
	if len(label) > 80 {
		label = label[:80]
	}
	sub, err := h.Subscribe(PushSubscription{Endpoint: req.Endpoint, Keys: req.Keys, Label: label, Origin: requestOrigin(r)}, req.HideText)
	if err != nil {
		writeJSON(w, 400, map[string]string{"error": err.Error()})
		return
	}
	s.deps.Logger.Info().Str("device", sub.Label).Msg("Push subscription saved")
	writeJSON(w, 200, map[string]any{"subscribed": true, "hide_text": sub.HideText, "devices": h.Count()})
}

func (s *Server) handlePushUnsubscribe(w http.ResponseWriter, r *http.Request) {
	h := s.push()
	if h == nil {
		writeJSON(w, 503, map[string]string{"error": "push notifications are not available on this server"})
		return
	}
	req, ok := readPushReq(w, r)
	if !ok {
		return
	}
	removed := h.Unsubscribe(req.Endpoint)
	writeJSON(w, 200, map[string]any{"subscribed": false, "removed": removed, "devices": h.Count()})
}

func (s *Server) handlePushSettings(w http.ResponseWriter, r *http.Request) {
	h := s.push()
	if h == nil {
		writeJSON(w, 503, map[string]string{"error": "push notifications are not available on this server"})
		return
	}
	req, ok := readPushReq(w, r)
	if !ok {
		return
	}
	if req.HideText == nil {
		writeJSON(w, 400, map[string]string{"error": "hide_text required"})
		return
	}
	if !h.SetHideText(req.Endpoint, *req.HideText) {
		writeJSON(w, 404, map[string]any{"error": "this device isn't subscribed", "subscribed": false})
		return
	}
	writeJSON(w, 200, map[string]any{"subscribed": true, "hide_text": *req.HideText})
}

func (s *Server) handlePushTest(w http.ResponseWriter, r *http.Request) {
	h := s.push()
	if h == nil {
		writeJSON(w, 503, map[string]string{"error": "push notifications are not available on this server"})
		return
	}
	req, ok := readPushReq(w, r)
	if !ok {
		return
	}
	if _, ok := h.Get(req.Endpoint); !ok {
		writeJSON(w, 404, map[string]any{"error": "this device isn't subscribed", "subscribed": false})
		return
	}
	status, err := h.SendTest(req.Endpoint)
	if status == http.StatusNotFound || status == http.StatusGone {
		writeJSON(w, 410, map[string]any{"error": "the browser's push subscription expired; turn notifications on again", "subscribed": false})
		return
	}
	if err != nil {
		writeJSON(w, 502, map[string]string{"error": "push service: " + err.Error()})
		return
	}
	writeJSON(w, 200, map[string]any{"sent": true, "status": status})
}

// requestOrigin is the page origin (scheme://host) a request came from:
// the Origin header, else the Referer's origin ("" when neither is usable).
func requestOrigin(r *http.Request) string {
	for _, v := range []string{r.Header.Get("Origin"), r.Header.Get("Referer")} {
		u, err := url.Parse(strings.TrimSpace(v))
		if err != nil || u.Host == "" || (u.Scheme != "https" && u.Scheme != "http") || len(u.Host) > 255 {
			continue
		}
		return strings.ToLower(u.Scheme + "://" + u.Host)
	}
	return ""
}

// deviceLabel is a rough "Chrome on Android" from the User-Agent.
func deviceLabel(ua string) string {
	plat := "this device"
	switch {
	case strings.Contains(ua, "Tesla"):
		plat = "Car"
	case strings.Contains(ua, "Android"):
		plat = "Android"
	case strings.Contains(ua, "iPhone"):
		plat = "iPhone"
	case strings.Contains(ua, "iPad"):
		plat = "iPad"
	case strings.Contains(ua, "CrOS"):
		plat = "ChromeOS"
	case strings.Contains(ua, "Windows"):
		plat = "Windows"
	case strings.Contains(ua, "Macintosh"):
		plat = "Mac"
	case strings.Contains(ua, "Linux"):
		plat = "Linux"
	}
	br := "Browser"
	switch {
	case strings.Contains(ua, "Edg/"):
		br = "Edge"
	case strings.Contains(ua, "Firefox/"):
		br = "Firefox"
	case strings.Contains(ua, "SamsungBrowser"):
		br = "Samsung Internet"
	case strings.Contains(ua, "Chrome/"):
		br = "Chrome"
	case strings.Contains(ua, "Safari/"):
		br = "Safari"
	}
	return br + " on " + plat
}
