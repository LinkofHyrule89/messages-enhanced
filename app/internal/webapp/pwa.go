package webapp

import (
	"net/http"
	"path"
	"strings"
)

// PWA files are public: an install (and the browser's icon/manifest
// fetches) happens before or without the login cookie, and none of these
// contain private data. The service worker never caches /api/ responses.
func (s *Server) registerPWARoutes(root *http.ServeMux) {
	root.HandleFunc("/app/manifest.webmanifest", s.servePWA)
	root.HandleFunc("/app/sw.js", s.servePWA)
	root.HandleFunc("/app/offline.html", s.servePWA)
	root.HandleFunc("/app/pwa.js", s.servePWA)
	root.HandleFunc("/app/icons/", s.servePWA)
	root.HandleFunc("/favicon.ico", s.serveRootIcon)
	root.HandleFunc("/apple-touch-icon.png", s.serveRootIcon)
	root.HandleFunc("/apple-touch-icon-precomposed.png", s.serveRootIcon)
}

func (s *Server) servePWA(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	p := r.URL.Path
	w.Header().Set("X-Content-Type-Options", "nosniff")
	switch {
	case p == "/app/manifest.webmanifest":
		w.Header().Set("Content-Type", "application/manifest+json")
		w.Header().Set("Cache-Control", "no-cache")
	case p == "/app/sw.js":
		w.Header().Set("Content-Type", "text/javascript; charset=utf-8")
		// Browsers re-check the worker on navigation; never let a proxy
		// pin an old one.
		w.Header().Set("Cache-Control", "no-cache")
	case p == "/app/offline.html":
		setPageSecurityHeaders(w)
		w.Header().Set("Cache-Control", "no-cache")
	case p == "/app/pwa.js":
		w.Header().Set("Cache-Control", "no-cache")
	case strings.HasPrefix(p, "/app/icons/"):
		name := strings.TrimPrefix(p, "/app/icons/")
		if name == "" || strings.Contains(name, "/") {
			http.NotFound(w, r)
			return
		}
		switch path.Ext(name) {
		case ".png", ".svg", ".ico":
		default:
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Cache-Control", "public, max-age=86400")
	default:
		http.NotFound(w, r)
		return
	}
	s.static.ServeHTTP(w, r)
}

// serveRootIcon answers the paths browsers probe on their own.
func (s *Server) serveRootIcon(w http.ResponseWriter, r *http.Request) {
	name := "favicon.ico"
	if strings.HasPrefix(r.URL.Path, "/apple-touch-icon") {
		name = "apple-touch-icon.png"
	}
	r2 := r.Clone(r.Context())
	r2.URL.Path = "/app/icons/" + name
	w.Header().Set("Cache-Control", "public, max-age=86400")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	s.static.ServeHTTP(w, r2)
}
