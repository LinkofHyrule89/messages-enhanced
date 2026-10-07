package webapp

import (
	"compress/gzip"
	"net/http"
	"strings"
	"sync"
)

// gzipMinBytes: smaller bodies aren't worth compressing (decided from
// Content-Length when the handler sets it).
const gzipMinBytes = 1024

var gzipPool = sync.Pool{New: func() any {
	w, _ := gzip.NewWriterLevel(nil, 5)
	return w
}}

// compressible reports whether a response of this type should be gzipped:
// text, JSON, JavaScript, SVG, web manifest. Never event streams (SSE needs
// unbuffered writes) or already-compressed media.
func compressible(ct string) bool {
	ct = strings.ToLower(ct)
	if strings.HasPrefix(ct, "text/event-stream") {
		return false
	}
	return strings.HasPrefix(ct, "text/") ||
		strings.Contains(ct, "json") ||
		strings.Contains(ct, "javascript") ||
		strings.Contains(ct, "svg") ||
		strings.Contains(ct, "manifest")
}

// withGzip gzips compressible responses for clients that accept it.
func withGzip(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodHead || r.Header.Get("Range") != "" ||
			!strings.Contains(r.Header.Get("Accept-Encoding"), "gzip") ||
			strings.Contains(r.Header.Get("Accept"), "text/event-stream") {
			next.ServeHTTP(w, r)
			return
		}
		gw := &gzipResponseWriter{ResponseWriter: w}
		defer gw.close()
		next.ServeHTTP(gw, r)
	})
}

type gzipResponseWriter struct {
	http.ResponseWriter
	gz          *gzip.Writer
	decided     bool
	compressing bool
}

func (g *gzipResponseWriter) decide(status int) {
	if g.decided {
		return
	}
	g.decided = true
	h := g.Header()
	if status < 200 || status == http.StatusNoContent || status == http.StatusNotModified || status == http.StatusPartialContent ||
		h.Get("Content-Encoding") != "" || !compressible(h.Get("Content-Type")) {
		return
	}
	if cl := h.Get("Content-Length"); cl != "" && len(cl) < 4 { // < 1000 bytes
		return
	}
	g.compressing = true
	h.Del("Content-Length")
	h.Set("Content-Encoding", "gzip")
	h.Add("Vary", "Accept-Encoding")
	if et := h.Get("ETag"); et != "" && !strings.HasPrefix(et, "W/") {
		h.Set("ETag", "W/"+et)
	}
	g.gz = gzipPool.Get().(*gzip.Writer)
	g.gz.Reset(g.ResponseWriter)
}

func (g *gzipResponseWriter) WriteHeader(status int) {
	g.decide(status)
	g.ResponseWriter.WriteHeader(status)
}

func (g *gzipResponseWriter) Write(b []byte) (int, error) {
	if !g.decided {
		if g.Header().Get("Content-Type") == "" {
			g.Header().Set("Content-Type", http.DetectContentType(b))
		}
		g.WriteHeader(http.StatusOK)
	}
	if g.compressing {
		return g.gz.Write(b)
	}
	return g.ResponseWriter.Write(b)
}

func (g *gzipResponseWriter) Flush() {
	if g.compressing {
		_ = g.gz.Flush()
	}
	if f, ok := g.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

func (g *gzipResponseWriter) Unwrap() http.ResponseWriter { return g.ResponseWriter }

func (g *gzipResponseWriter) close() {
	if g.gz != nil {
		_ = g.gz.Close()
		g.gz.Reset(nil)
		gzipPool.Put(g.gz)
		g.gz = nil
	}
}

// withSecurityHeaders sets headers every response should carry (pages also
// get the CSP in setPageSecurityHeaders): no MIME sniffing, no referrer to
// other sites, no framing, and HSTS when served over HTTPS.
func withSecurityHeaders(next http.Handler, publicURL string) http.Handler {
	httpsPublic := strings.HasPrefix(strings.ToLower(publicURL), "https://")
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		if h.Get("Referrer-Policy") == "" {
			h.Set("Referrer-Policy", "same-origin")
		}
		h.Set("X-Frame-Options", "DENY")
		if r.TLS != nil || httpsPublic || strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https") {
			h.Set("Strict-Transport-Security", "max-age=31536000")
		}
		next.ServeHTTP(w, r)
	})
}
