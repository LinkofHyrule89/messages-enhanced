package webapp

import (
	"encoding/json"
	"errors"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// CarBackend is the OpenMessage side of the car page's message menu, Start
// chat panel and read-only folders (implemented by internal/app, wired in
// cmd/serve.go). Results are passed straight to JSON. Errors that carry an
// HTTP status (HTTPStatus() int) keep it; others become 500.
type CarBackend interface {
	DeleteMessage(messageID string) (any, error)
	Contacts() (any, error)
	StartConversation(numbers []string, groupName string) (any, error)
	FolderConversations(folder string) (any, error)
	FolderMessages(conversationID string) (any, error)
	// PinConversation pins/unpins a conversation on this server.
	PinConversation(conversationID string, pinned bool) (any, error)
	// ArchiveConversation archives/unarchives on the phone (Google).
	ArchiveConversation(conversationID string, archived bool) (any, error)
	// TrashConversation deletes the conversation on the phone (Google).
	TrashConversation(conversationID string) (any, error)
	// MuteConversation mutes on the phone when possible, always on the server.
	MuteConversation(conversationID string, muted bool) (any, error)
	// MarkConversationRead marks read on the phone and the server.
	MarkConversationRead(conversationID string) (any, error)
	// ConversationMeta: protocol (RCS/SMS), end-to-end encryption, muted.
	ConversationMeta(conversationID string) (any, error)
	// RefreshConversation re-reads one conversation from Google (members,
	// icon, photos, latest messages). retryAfter > 0: rate limited.
	RefreshConversation(conversationID string) (v any, retryAfter int, err error)
}

func (s *Server) registerCarRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/app/messages/delete", s.handleCarDelete)
	mux.HandleFunc("/api/app/contacts", s.handleCarContacts)
	mux.HandleFunc("/api/app/conversations/start", s.handleCarStart)
	mux.HandleFunc("/api/app/folder", s.handleCarFolder)
	mux.HandleFunc("/api/app/folder/messages", s.handleCarFolderMessages)
	mux.HandleFunc("/api/app/typing", s.handleTyping)
	mux.HandleFunc("/api/app/conversations/pin", s.handleCarConversationPin)
	mux.HandleFunc("/api/app/conversations/archive", s.handleCarConversationArchive)
	mux.HandleFunc("/api/app/conversations/trash", s.handleCarConversationTrash)
	mux.HandleFunc("/api/app/conversations/mute", s.handleCarConversationMute)
	mux.HandleFunc("/api/app/conversations/read", s.handleCarConversationRead)
	mux.HandleFunc("/api/app/conversations/meta", s.handleCarConversationMeta)
	mux.HandleFunc("/api/app/refresh", s.handleRefresh)
	mux.HandleFunc("/api/app/conversations/refresh", s.handleCarConversationRefresh)
	mux.HandleFunc("/api/app/grok", s.handleGrok)
	s.registerProfileRoutes(mux)
}

// POST /api/app/conversations/mute {"conversation_id": "...", "muted": true|false}
func (s *Server) handleCarConversationMute(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, http.MethodPost) {
		return
	}
	b := s.carBackend(w)
	if b == nil {
		return
	}
	var req struct {
		ConversationID string `json:"conversation_id"`
		Muted          *bool  `json:"muted"`
	}
	if !decodeJSONBody(w, r, &req) {
		return
	}
	if strings.TrimSpace(req.ConversationID) == "" || req.Muted == nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "conversation_id and muted are required"})
		return
	}
	v, err := b.MuteConversation(req.ConversationID, *req.Muted)
	writeCarResult(w, v, err)
}

// POST /api/app/conversations/refresh {"conversation_id": "..."}: refresh
// one conversation from Google. 429 + Retry-After when rate limited.
func (s *Server) handleCarConversationRefresh(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, http.MethodPost) {
		return
	}
	b := s.carBackend(w)
	if b == nil {
		return
	}
	var req struct {
		ConversationID string `json:"conversation_id"`
	}
	if !decodeJSONBody(w, r, &req) {
		return
	}
	if strings.TrimSpace(req.ConversationID) == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "conversation_id is required"})
		return
	}
	v, retry, err := b.RefreshConversation(req.ConversationID)
	if retry > 0 {
		w.Header().Set("Retry-After", strconv.Itoa(retry))
		writeJSON(w, http.StatusTooManyRequests, map[string]any{"error": "Refreshed recently. Try again in " + strconv.Itoa(retry) + "s", "retry_after_sec": retry})
		return
	}
	writeCarResult(w, v, err)
}

// POST /api/app/conversations/read {"conversation_id": "..."}
func (s *Server) handleCarConversationRead(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, http.MethodPost) {
		return
	}
	b := s.carBackend(w)
	if b == nil {
		return
	}
	var req struct {
		ConversationID string `json:"conversation_id"`
	}
	if !decodeJSONBody(w, r, &req) {
		return
	}
	if strings.TrimSpace(req.ConversationID) == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "conversation_id is required"})
		return
	}
	v, err := b.MarkConversationRead(req.ConversationID)
	writeCarResult(w, v, err)
}

// GET /api/app/conversations/meta?conversation_id=...
func (s *Server) handleCarConversationMeta(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, http.MethodGet) {
		return
	}
	b := s.carBackend(w)
	if b == nil {
		return
	}
	id := strings.TrimSpace(r.URL.Query().Get("conversation_id"))
	if id == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "conversation_id is required"})
		return
	}
	v, err := b.ConversationMeta(id)
	writeCarResult(w, v, err)
}

// POST /api/app/conversations/archive {"conversation_id": "...", "archived": true|false}
func (s *Server) handleCarConversationArchive(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, http.MethodPost) {
		return
	}
	b := s.carBackend(w)
	if b == nil {
		return
	}
	var req struct {
		ConversationID string `json:"conversation_id"`
		Archived       *bool  `json:"archived"`
	}
	if !decodeJSONBody(w, r, &req) {
		return
	}
	if strings.TrimSpace(req.ConversationID) == "" || req.Archived == nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "conversation_id and archived are required"})
		return
	}
	v, err := b.ArchiveConversation(req.ConversationID, *req.Archived)
	writeCarResult(w, v, err)
}

// POST /api/app/conversations/trash {"conversation_id": "..."}
func (s *Server) handleCarConversationTrash(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, http.MethodPost) {
		return
	}
	b := s.carBackend(w)
	if b == nil {
		return
	}
	var req struct {
		ConversationID string `json:"conversation_id"`
	}
	if !decodeJSONBody(w, r, &req) {
		return
	}
	if strings.TrimSpace(req.ConversationID) == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "conversation_id is required"})
		return
	}
	v, err := b.TrashConversation(req.ConversationID)
	writeCarResult(w, v, err)
}

// POST /api/app/conversations/pin {"conversation_id": "...", "pinned": true|false}
func (s *Server) handleCarConversationPin(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, http.MethodPost) {
		return
	}
	b := s.carBackend(w)
	if b == nil {
		return
	}
	var req struct {
		ConversationID string `json:"conversation_id"`
		Pinned         *bool  `json:"pinned"`
	}
	if !decodeJSONBody(w, r, &req) {
		return
	}
	if strings.TrimSpace(req.ConversationID) == "" || req.Pinned == nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "conversation_id and pinned are required"})
		return
	}
	v, err := b.PinConversation(req.ConversationID, *req.Pinned)
	writeCarResult(w, v, err)
}

func writeCarResult(w http.ResponseWriter, v any, err error) {
	if err != nil {
		status := http.StatusInternalServerError
		var hs interface{ HTTPStatus() int }
		if errors.As(err, &hs) && hs.HTTPStatus() >= 400 {
			status = hs.HTTPStatus()
		}
		writeJSON(w, status, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, v)
}

func (s *Server) carBackend(w http.ResponseWriter) CarBackend {
	if s.deps.Car == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "not available on this server"})
		return nil
	}
	return s.deps.Car
}

func requireMethod(w http.ResponseWriter, r *http.Request, method string) bool {
	if r.Method != method {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
		return false
	}
	return true
}

func decodeJSONBody(w http.ResponseWriter, r *http.Request, v any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid JSON"})
		return false
	}
	return true
}

// POST /api/app/messages/delete {"message_id": "..."}
func (s *Server) handleCarDelete(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, http.MethodPost) {
		return
	}
	b := s.carBackend(w)
	if b == nil {
		return
	}
	var req struct {
		MessageID string `json:"message_id"`
	}
	if !decodeJSONBody(w, r, &req) {
		return
	}
	v, err := b.DeleteMessage(req.MessageID)
	writeCarResult(w, v, err)
}

// GET /api/app/contacts -> {"top": [...], "all": [...]}
func (s *Server) handleCarContacts(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, http.MethodGet) {
		return
	}
	if b := s.carBackend(w); b != nil {
		v, err := b.Contacts()
		writeCarResult(w, v, err)
	}
}

// POST /api/app/conversations/start {"numbers": ["+1..."], "group_name": ""}
// Opens (or gets/creates) the conversation. Never sends a message.
func (s *Server) handleCarStart(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, http.MethodPost) {
		return
	}
	b := s.carBackend(w)
	if b == nil {
		return
	}
	var req struct {
		Numbers   []string `json:"numbers"`
		GroupName string   `json:"group_name"`
	}
	if !decodeJSONBody(w, r, &req) {
		return
	}
	v, err := b.StartConversation(req.Numbers, req.GroupName)
	writeCarResult(w, v, err)
}

// GET /api/app/folder?name=archived|spam|blocked (read-only)
func (s *Server) handleCarFolder(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, http.MethodGet) {
		return
	}
	if b := s.carBackend(w); b != nil {
		v, err := b.FolderConversations(r.URL.Query().Get("name"))
		writeCarResult(w, v, err)
	}
}

// GET /api/app/folder/messages?conversation_id=... (read-only, live)
func (s *Server) handleCarFolderMessages(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, http.MethodGet) {
		return
	}
	if b := s.carBackend(w); b != nil {
		v, err := b.FolderMessages(r.URL.Query().Get("conversation_id"))
		writeCarResult(w, v, err)
	}
}

// ---------- typing ----------

// TypingTTL is how long a "started typing" event counts without a refresh
// or a "stopped typing" event.
const TypingTTL = 15 * time.Second

// TypingTracker keeps who is typing where, in memory, expiring after TypingTTL.
type TypingTracker struct {
	mu    sync.Mutex
	now   func() time.Time
	state map[string]map[string]typingEntry // conv -> sender key -> entry
}

type typingEntry struct {
	name    string
	number  string
	expires time.Time
}

// TypingState is one active typist.
type TypingState struct {
	ConversationID string `json:"conversation_id"`
	SenderName     string `json:"sender_name,omitempty"`
	SenderNumber   string `json:"sender_number,omitempty"`
	ExpiresInMS    int64  `json:"expires_in_ms"`
}

func NewTypingTracker() *TypingTracker {
	return &TypingTracker{now: time.Now, state: map[string]map[string]typingEntry{}}
}

// Set records a typing start/stop (matches app.OnTypingChange's signature).
func (t *TypingTracker) Set(conversationID, senderName, senderNumber string, typing bool) {
	if t == nil || strings.TrimSpace(conversationID) == "" {
		return
	}
	key := strings.TrimSpace(senderNumber)
	if key == "" {
		key = strings.TrimSpace(senderName)
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	m := t.state[conversationID]
	if !typing {
		if m != nil {
			delete(m, key)
			if len(m) == 0 {
				delete(t.state, conversationID)
			}
		}
		return
	}
	if m == nil {
		m = map[string]typingEntry{}
		t.state[conversationID] = m
	}
	m[key] = typingEntry{name: senderName, number: senderNumber, expires: t.now().Add(TypingTTL)}
}

// Active returns unexpired typists (all conversations when id is "").
func (t *TypingTracker) Active(conversationID string) []TypingState {
	out := []TypingState{}
	if t == nil {
		return out
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	now := t.now()
	for conv, m := range t.state {
		for k, e := range m {
			if !now.Before(e.expires) {
				delete(m, k)
				continue
			}
			if conversationID != "" && conv != conversationID {
				continue
			}
			out = append(out, TypingState{ConversationID: conv, SenderName: e.name, SenderNumber: e.number, ExpiresInMS: e.expires.Sub(now).Milliseconds()})
		}
		if len(m) == 0 {
			delete(t.state, conv)
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].ConversationID != out[j].ConversationID {
			return out[i].ConversationID < out[j].ConversationID
		}
		return out[i].SenderNumber < out[j].SenderNumber
	})
	return out
}

// GET /api/app/typing[?conversation_id=] -> {"typing": [...], "ttl_ms": 15000}
func (s *Server) handleTyping(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, http.MethodGet) {
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"typing": s.deps.Typing.Active(strings.TrimSpace(r.URL.Query().Get("conversation_id"))),
		"ttl_ms": TypingTTL.Milliseconds(),
	})
}
