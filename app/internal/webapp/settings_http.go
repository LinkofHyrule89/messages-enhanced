package webapp

import (
	"errors"
	"io"
	"net/http"
)

func (s *Server) registerSettingsRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/app/settings", s.handleSettings)
}

// GET /api/app/settings -> {"settings": { ... }}
// PUT /api/app/settings with a JSON object (max 16KB) replaces the stored object.
// Writes go through the login gate, which already rejects cross-origin requests.
func (s *Server) handleSettings(w http.ResponseWriter, r *http.Request) {
	st := s.settings
	if st == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "settings storage unavailable"})
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, http.StatusOK, map[string]any{"settings": st.Get()})
	case http.MethodPut:
		r.Body = http.MaxBytesReader(w, r.Body, maxSettingsBytes)
		body, err := io.ReadAll(r.Body)
		if err != nil {
			var tooBig *http.MaxBytesError
			if errors.As(err, &tooBig) {
				writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{"error": errSettingsTooBig.Error()})
				return
			}
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": errSettingsJSON.Error()})
			return
		}
		if err := st.Put(body); err != nil {
			status := http.StatusBadRequest
			if errors.Is(err, errSettingsTooBig) {
				status = http.StatusRequestEntityTooLarge
			} else if !errors.Is(err, errSettingsJSON) {
				status = http.StatusInternalServerError
			}
			writeJSON(w, status, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"settings": st.Get()})
	default:
		w.Header().Set("Allow", "GET, PUT")
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
	}
}
