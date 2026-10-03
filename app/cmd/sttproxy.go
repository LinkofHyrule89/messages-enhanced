package cmd

// stt-proxy: a tiny loopback forwarder for speech-to-text API calls, so a
// server whose own traffic is pinned to a VPN (some STT providers block VPN
// exit IPs) can send just those calls out the host's normal route. Run it
// as a different user than the server (e.g. systemd DynamicUser) and point
// MESSAGES_GROQ_BASE_URL at it. Only the transcription endpoint is
// forwarded; the API key comes from the server's request and is never
// stored or logged here.

import (
	"errors"
	"flag"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"
	"time"

	"github.com/rs/zerolog"
)

const sttProxyMaxBody = 26 << 20 // Groq's limit is 25 MB per file

// sttProxyPaths are the only upstream paths forwarded.
var sttProxyPaths = map[string]bool{
	"/openai/v1/audio/transcriptions": true,
	"/openai/v1/audio/translations":   true,
}

func RunSTTProxy(logger zerolog.Logger, args ...string) error {
	fs := flag.NewFlagSet("stt-proxy", flag.ContinueOnError)
	listen := fs.String("listen", "127.0.0.1:7119", "loopback address to listen on")
	target := fs.String("target", "https://api.groq.com", "upstream base URL")
	if err := fs.Parse(args); err != nil {
		return err
	}
	h, err := NewSTTProxyHandler(*target, logger)
	if err != nil {
		return err
	}
	host, _, err := net.SplitHostPort(*listen)
	if err != nil {
		return err
	}
	if ip := net.ParseIP(host); host != "localhost" && (ip == nil || !ip.IsLoopback()) {
		return errors.New("stt-proxy only listens on loopback")
	}
	srv := &http.Server{Addr: *listen, Handler: h, ReadHeaderTimeout: 10 * time.Second}
	logger.Info().Str("listen", *listen).Str("target", *target).Msg("STT proxy listening")
	return srv.ListenAndServe()
}

func NewSTTProxyHandler(target string, logger zerolog.Logger) (http.Handler, error) {
	u, err := url.Parse(target)
	if err != nil || u.Host == "" || (u.Scheme != "https" && u.Scheme != "http") {
		return nil, errors.New("stt-proxy: invalid --target")
	}
	rp := &httputil.ReverseProxy{
		Rewrite: func(pr *httputil.ProxyRequest) {
			pr.SetURL(u)
			pr.Out.Host = u.Host
			pr.Out.Header.Del("X-Forwarded-For")
		},
		Transport: &http.Transport{
			Proxy:                 nil,
			ForceAttemptHTTP2:     true,
			TLSHandshakeTimeout:   10 * time.Second,
			ResponseHeaderTimeout: 90 * time.Second,
			IdleConnTimeout:       90 * time.Second,
		},
		ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) {
			logger.Warn().Err(err).Msg("STT proxy upstream error")
			http.Error(w, "stt-proxy: upstream unreachable", http.StatusBadGateway)
		},
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || !sttProxyPaths[r.URL.Path] || strings.Contains(r.URL.RawQuery, "@") {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, sttProxyMaxBody)
		rp.ServeHTTP(w, r)
	}), nil
}
