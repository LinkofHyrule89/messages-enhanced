package webapp

import "net/http"

// GrokBackend holds the @Grok auto-reply settings (server-wide).
type GrokBackend interface {
	GrokStatus() any
	// groqEnabled nil keeps the current @Groq setting.
	SetGrokSettings(enabled bool, trigger string, groqEnabled *bool) (any, error)
}

// GET  /api/app/grok -> {enabled, trigger, groq_enabled, key_configured, groq_key_configured, ...}
// POST /api/app/grok {"enabled": bool, "trigger": "me"|"everyone", "groq_enabled": bool (optional)}
func (s *Server) handleGrok(w http.ResponseWriter, r *http.Request) {
	b := s.deps.Grok
	if b == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "@Grok unavailable"})
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, http.StatusOK, b.GrokStatus())
	case http.MethodPost:
		var req struct {
			Enabled     *bool  `json:"enabled"`
			Trigger     string `json:"trigger"`
			GroqEnabled *bool  `json:"groq_enabled"`
		}
		if !decodeJSONBody(w, r, &req) {
			return
		}
		if req.Enabled == nil || (req.Trigger != "me" && req.Trigger != "everyone") {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "enabled and trigger (me or everyone) are required"})
			return
		}
		st, err := b.SetGrokSettings(*req.Enabled, req.Trigger, req.GroqEnabled)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, st)
	default:
		w.Header().Set("Allow", "GET, POST")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}
