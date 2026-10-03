package webapp

// The header's profile photo: the signed-in Google account's photo, cached
// and proxied by the server (Deps.Profile); the browser never sees Google
// cookies. GET /api/app/profile -> {"photo_hash": "..."} ("" = none: the
// page falls back to your contact photo, then your initial); GET
// /api/app/profile-photo[?h=<hash>] -> the image (ETag = hash; the page
// adds ?h= so a new photo is never served from an old cached copy).

import (
	"net/http"
	"strings"
)

// ProfilePhotoSource returns the cached Google account photo (ok=false: none).
type ProfilePhotoSource interface {
	AccountPhoto() (image []byte, mimeType, hash string, ok bool)
}

func (s *Server) registerProfileRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/app/profile", s.handleProfile)
	mux.HandleFunc("/api/app/profile-photo", s.handleProfilePhoto)
}

func (s *Server) handleProfile(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	hash := ""
	if s.deps.Profile != nil {
		if _, _, h, ok := s.deps.Profile.AccountPhoto(); ok {
			hash = h
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"photo_hash": hash, "source": map[bool]string{true: "google_account", false: ""}[hash != ""]})
}

func (s *Server) handleProfilePhoto(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if s.deps.Profile == nil {
		http.NotFound(w, r)
		return
	}
	img, mimeType, hash, ok := s.deps.Profile.AccountPhoto()
	if !ok {
		http.NotFound(w, r)
		return
	}
	etag := `"` + hash + `"`
	w.Header().Set("ETag", etag)
	w.Header().Set("Cache-Control", "private, no-cache")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if match := r.Header.Get("If-None-Match"); match != "" && strings.Contains(match, etag) {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	if !strings.HasPrefix(mimeType, "image/") {
		mimeType = "image/jpeg"
	}
	w.Header().Set("Content-Type", mimeType)
	_, _ = w.Write(img)
}
