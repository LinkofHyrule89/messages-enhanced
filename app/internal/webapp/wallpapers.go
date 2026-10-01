package webapp

import (
	"embed"
	"encoding/json"
	"io/fs"
	"net/http"
	"path"
	"strings"
	"sync"
)

// Built-in chat wallpapers. The images are freely licensed look-alikes of the
// Google Messages categories (see wallpapers/CREDITS.md); every file name
// carries a content hash, so they are served as immutable.
//
//go:embed wallpapers/manifest.json wallpapers/CREDITS.md wallpapers/img
var wallpaperFS embed.FS

const wallpaperURLPrefix = "/wallpapers/"

type WallpaperItem struct {
	ID         string `json:"id"`
	Title      string `json:"title"`
	File       string `json:"file"`
	Thumb      string `json:"thumb"`
	W          int    `json:"w"`
	H          int    `json:"h"`
	Author     string `json:"author"`
	License    string `json:"license"`
	LicenseURL string `json:"license_url,omitempty"`
	Source     string `json:"source"`
	// Filled in for the API response.
	FullURL  string `json:"full_url,omitempty"`
	ThumbURL string `json:"thumb_url,omitempty"`
}

type WallpaperCategory struct {
	ID    string          `json:"id"`
	Name  string          `json:"name"`
	Items []WallpaperItem `json:"items"`
}

type wallpaperManifest struct {
	Categories []WallpaperCategory `json:"categories"`
}

var (
	wpOnce  sync.Once
	wpCats  []WallpaperCategory
	wpByID  map[string]WallpaperItem
	wpFiles map[string]bool
	wpErr   error
)

func loadWallpapers() {
	wpOnce.Do(func() {
		wpByID = map[string]WallpaperItem{}
		wpFiles = map[string]bool{}
		raw, err := wallpaperFS.ReadFile("wallpapers/manifest.json")
		if err != nil {
			wpErr = err
			return
		}
		var m wallpaperManifest
		if err := json.Unmarshal(raw, &m); err != nil {
			wpErr = err
			return
		}
		for ci := range m.Categories {
			c := &m.Categories[ci]
			for ii := range c.Items {
				it := &c.Items[ii]
				it.FullURL = wallpaperURLPrefix + it.File
				it.ThumbURL = wallpaperURLPrefix + it.Thumb
				wpByID[it.ID] = *it
				wpFiles[it.File] = true
				wpFiles[it.Thumb] = true
			}
		}
		wpCats = m.Categories
	})
}

// Wallpapers returns the built-in categories (with URLs filled in).
func Wallpapers() []WallpaperCategory { loadWallpapers(); return wpCats }

// WallpaperByID looks up a built-in wallpaper.
func WallpaperByID(id string) (WallpaperItem, bool) {
	loadWallpapers()
	it, ok := wpByID[id]
	return it, ok
}

func (s *Server) handleWallpapers(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, 405, map[string]string{"error": "method not allowed"})
		return
	}
	loadWallpapers()
	if wpErr != nil {
		writeJSON(w, 500, map[string]string{"error": "wallpapers unavailable"})
		return
	}
	cats := wpCats
	if cats == nil {
		cats = []WallpaperCategory{}
	}
	writeJSON(w, 200, map[string]any{"categories": cats})
}

// handleWallpaperFile serves /wallpapers/<file> (manifest files only).
func (s *Server) handleWallpaperFile(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "method not allowed", 405)
		return
	}
	loadWallpapers()
	name := strings.TrimPrefix(r.URL.Path, wallpaperURLPrefix)
	if name == "CREDITS.md" {
		b, _ := fs.ReadFile(wallpaperFS, "wallpapers/CREDITS.md")
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.Header().Set("Cache-Control", "no-cache")
		_, _ = w.Write(b)
		return
	}
	if !wpFiles[name] || path.Clean(name) != name {
		http.NotFound(w, r)
		return
	}
	b, err := fs.ReadFile(wallpaperFS, "wallpapers/"+name)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "image/webp")
	w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if r.Method == http.MethodHead {
		return
	}
	_, _ = w.Write(b)
}
