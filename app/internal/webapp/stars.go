package webapp

// Starred messages. Google Messages for Web has no star API (libgm has
// none), so stars are kept by this server only (data dir, stars.json):
// shared by every device signed in here, never synced to the phone.

import (
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"
)

const (
	starsFile = "stars.json"
	starsMax  = 5000
)

type starEntry struct {
	ConversationID string `json:"conversation_id"`
	StarredMS      int64  `json:"starred_ms"`
}

type StarStore struct {
	mu    sync.Mutex
	path  string
	stars map[string]starEntry // message id -> entry
}

func OpenStarStore(dataDir string) (*StarStore, error) {
	if dataDir == "" {
		return nil, errors.New("no data dir")
	}
	s := &StarStore{path: filepath.Join(dataDir, starsFile), stars: map[string]starEntry{}}
	if b, err := os.ReadFile(s.path); err == nil {
		_ = json.Unmarshal(b, &s.stars)
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	return s, nil
}

func (s *StarStore) Set(msgID, convID string, starred bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if starred {
		if _, ok := s.stars[msgID]; !ok && len(s.stars) >= starsMax {
			return errors.New("too many starred messages")
		}
		s.stars[msgID] = starEntry{ConversationID: convID, StarredMS: time.Now().UnixMilli()}
	} else {
		delete(s.stars, msgID)
	}
	b, err := json.Marshal(s.stars)
	if err != nil {
		return err
	}
	return writeFileAtomic(s.path, b)
}

func (s *StarStore) List(convID string) map[string]starEntry {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := map[string]starEntry{}
	for id, e := range s.stars {
		if convID == "" || e.ConversationID == convID {
			out[id] = e
		}
	}
	return out
}

// GET /api/app/stars[?conversation_id=] -> {"stars": {id: {...}}}
// POST /api/app/stars {"message_id","conversation_id","starred"}
func (s *Server) handleStars(w http.ResponseWriter, r *http.Request) {
	if s.stars == nil {
		writeJSON(w, 503, map[string]string{"error": "star storage unavailable"})
		return
	}
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, 200, map[string]any{"stars": s.stars.List(r.URL.Query().Get("conversation_id")), "scope": "server"})
	case http.MethodPost:
		r.Body = http.MaxBytesReader(w, r.Body, 4<<10)
		var req struct {
			MessageID      string `json:"message_id"`
			ConversationID string `json:"conversation_id"`
			Starred        bool   `json:"starred"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.MessageID == "" || len(req.MessageID) > 256 || len(req.ConversationID) > 256 {
			writeJSON(w, 400, map[string]string{"error": "message_id required"})
			return
		}
		if err := s.stars.Set(req.MessageID, req.ConversationID, req.Starred); err != nil {
			writeJSON(w, 500, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, 200, map[string]any{"starred": req.Starred})
	default:
		writeJSON(w, 405, map[string]string{"error": "method not allowed"})
	}
}
