package webapp

import (
	"net/http"
	"strconv"
)

// RefreshBackend re-reads the conversation list, names and photos from
// Google Messages in the background.
type RefreshBackend interface {
	// StartRefresh starts scope "list" or "all"; retryAfter > 0 means it
	// was asked for too soon (nothing started).
	StartRefresh(scope string) (status any, retryAfter int, err error)
	RefreshStatus() any
}

// GET  /api/app/refresh                 -> current/last refresh status
// POST /api/app/refresh {"scope":"list"|"all"} -> 202 + status (429 if too soon)
func (s *Server) handleRefresh(w http.ResponseWriter, r *http.Request) {
	b := s.deps.Refresh
	if b == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "refresh unavailable"})
		return
	}
	switch r.Method {
	case http.MethodGet:
		w.Header().Set("Cache-Control", "no-store")
		writeJSON(w, http.StatusOK, b.RefreshStatus())
	case http.MethodPost:
		var req struct {
			Scope string `json:"scope"`
		}
		if !decodeJSONBody(w, r, &req) {
			return
		}
		if req.Scope != "list" && req.Scope != "all" {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "scope must be list or all"})
			return
		}
		st, retry, err := b.StartRefresh(req.Scope)
		if err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		if retry > 0 {
			w.Header().Set("Retry-After", strconv.Itoa(retry))
			writeJSON(w, http.StatusTooManyRequests, st)
			return
		}
		writeJSON(w, http.StatusAccepted, st)
	default:
		w.Header().Set("Allow", "GET, POST")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}
