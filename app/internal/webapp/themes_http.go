package webapp

import (
	"encoding/json"
	"errors"
	"mime"
	"net/http"
	"strconv"
)

func (s *Server) registerThemeRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/app/wallpapers", s.handleWallpapers)
	mux.HandleFunc(wallpaperURLPrefix, s.handleWallpaperFile)
	mux.HandleFunc("/api/app/themes", s.handleThemes)
	mux.HandleFunc("/api/app/theme", s.handleTheme)
	mux.HandleFunc("/api/app/theme/background", s.handleThemeBackground)
}

func (s *Server) themeStore(w http.ResponseWriter) *ThemeStore {
	if s.themes == nil {
		writeJSON(w, 503, map[string]string{"error": "theme storage unavailable"})
	}
	return s.themes
}

// GET /api/app/themes -> {"themes": {"<conv id>": {...}}, "palettes": [...]}
func (s *Server) handleThemes(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, 405, map[string]string{"error": "method not allowed"})
		return
	}
	st := s.themeStore(w)
	if st == nil {
		return
	}
	writeJSON(w, 200, map[string]any{"themes": st.All(), "palettes": ThemePalettes})
}

type themeRequest struct {
	ConversationID string `json:"conversation_id"`
	Theme          struct {
		Palette   string `json:"palette"`
		Color     string `json:"color"`
		Wallpaper string `json:"wallpaper"`
		Custom    bool   `json:"custom"` // keep the uploaded photo
	} `json:"theme"`
}

// GET/PUT(POST)/DELETE /api/app/theme
func (s *Server) handleTheme(w http.ResponseWriter, r *http.Request) {
	st := s.themeStore(w)
	if st == nil {
		return
	}
	switch r.Method {
	case http.MethodGet:
		id := r.URL.Query().Get("conversation_id")
		if !validConvID(id) {
			writeJSON(w, 400, map[string]string{"error": ErrThemeConversation.Error()})
			return
		}
		t, ok := st.Get(id)
		writeJSON(w, 200, map[string]any{"conversation_id": id, "theme": t, "set": ok})
	case http.MethodPut, http.MethodPost:
		r.Body = http.MaxBytesReader(w, r.Body, 16<<10)
		var req themeRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeJSON(w, 400, map[string]string{"error": "invalid JSON"})
			return
		}
		t, err := st.Set(req.ConversationID, ChatTheme{
			Palette: req.Theme.Palette, Color: req.Theme.Color, Wallpaper: req.Theme.Wallpaper,
		}, req.Theme.Custom)
		if err != nil {
			writeJSON(w, themeErrStatus(err), map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, 200, map[string]any{"conversation_id": req.ConversationID, "theme": t})
	case http.MethodDelete:
		id := r.URL.Query().Get("conversation_id")
		if err := st.Delete(id); err != nil {
			writeJSON(w, themeErrStatus(err), map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, 200, map[string]any{"conversation_id": id, "theme": ChatTheme{}})
	default:
		writeJSON(w, 405, map[string]string{"error": "method not allowed"})
	}
}

// POST (multipart: conversation_id, file) / GET ?conversation_id=&v= / DELETE
func (s *Server) handleThemeBackground(w http.ResponseWriter, r *http.Request) {
	st := s.themeStore(w)
	if st == nil {
		return
	}
	switch r.Method {
	case http.MethodGet, http.MethodHead:
		id := r.URL.Query().Get("conversation_id")
		b, version, err := st.Background(id)
		if err != nil {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "image/jpeg")
		w.Header().Set("Content-Length", strconv.Itoa(len(b)))
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; sandbox")
		if r.URL.Query().Get("v") == version {
			w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
		} else {
			w.Header().Set("Cache-Control", "private, no-cache")
		}
		if r.Method == http.MethodHead {
			return
		}
		_, _ = w.Write(b)
	case http.MethodPost, http.MethodPut:
		r.Body = http.MaxBytesReader(w, r.Body, MaxBackgroundUploadBytes+256<<10)
		if mt, _, _ := mime.ParseMediaType(r.Header.Get("Content-Type")); mt != "multipart/form-data" {
			writeJSON(w, 415, map[string]string{"error": "send multipart/form-data with fields conversation_id and file"})
			return
		}
		mr, err := r.MultipartReader()
		if err != nil {
			writeJSON(w, 400, map[string]string{"error": "invalid upload"})
			return
		}
		var convID string
		var out []byte
		gotFile := false
		for {
			part, err := mr.NextPart()
			if err != nil {
				var tooBig *http.MaxBytesError
				if errors.As(err, &tooBig) {
					writeJSON(w, 413, map[string]string{"error": ErrBackgroundTooLarge.Error()})
					return
				}
				break // io.EOF or a malformed tail
			}
			switch part.FormName() {
			case "conversation_id":
				buf := make([]byte, maxConversationIDLen+1)
				n, _ := readFull(part, buf)
				convID = string(buf[:n])
			case "file":
				if gotFile {
					break
				}
				gotFile = true
				processed, _, _, perr := ProcessBackgroundUpload(part)
				if perr != nil {
					var tooBig *http.MaxBytesError
					if errors.As(perr, &tooBig) {
						perr = ErrBackgroundTooLarge
					}
					writeJSON(w, backgroundErrStatus(perr), map[string]string{"error": perr.Error()})
					return
				}
				out = processed
			}
			part.Close()
		}
		if !validConvID(convID) {
			writeJSON(w, 400, map[string]string{"error": ErrThemeConversation.Error()})
			return
		}
		if !gotFile || out == nil {
			writeJSON(w, 400, map[string]string{"error": "multipart field 'file' is required"})
			return
		}
		t, err := st.SetBackground(convID, out)
		if err != nil {
			s.deps.Logger.Warn().Err(err).Msg("Saving chat background failed")
			writeJSON(w, 500, map[string]string{"error": "could not save background"})
			return
		}
		s.deps.Logger.Info().Int("bytes", len(out)).Msg("Saved custom chat background")
		writeJSON(w, 200, map[string]any{"conversation_id": convID, "theme": t})
	case http.MethodDelete:
		id := r.URL.Query().Get("conversation_id")
		t, err := st.DeleteBackground(id)
		if err != nil {
			writeJSON(w, themeErrStatus(err), map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, 200, map[string]any{"conversation_id": id, "theme": t})
	default:
		writeJSON(w, 405, map[string]string{"error": "method not allowed"})
	}
}

func readFull(r interface{ Read([]byte) (int, error) }, buf []byte) (int, error) {
	n := 0
	for n < len(buf) {
		m, err := r.Read(buf[n:])
		n += m
		if err != nil {
			return n, err
		}
	}
	return n, nil
}

func themeErrStatus(err error) int {
	switch {
	case errors.Is(err, ErrThemeConversation), errors.Is(err, ErrThemePalette),
		errors.Is(err, ErrThemeColor), errors.Is(err, ErrThemeWallpaper):
		return 400
	default:
		return 500
	}
}

func backgroundErrStatus(err error) int {
	switch {
	case errors.Is(err, ErrBackgroundTooLarge):
		return 413
	case errors.Is(err, ErrBackgroundType):
		return 415
	case errors.Is(err, ErrBackgroundDimensions), errors.Is(err, ErrBackgroundUndecodable):
		return 422
	default:
		return 400
	}
}
