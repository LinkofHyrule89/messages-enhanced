package web

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"

	"github.com/rs/zerolog"
)

func TestParsePreviewProxyAddr(t *testing.T) {
	addr, err := parsePreviewProxyAddr("  127.0.0.1:1080 ")
	if err != nil || addr != "127.0.0.1:1080" {
		t.Fatalf("got %q %v", addr, err)
	}
	addr, err = parsePreviewProxyAddr("socks5://127.0.0.1:1080")
	if err != nil || addr != "127.0.0.1:1080" {
		t.Fatalf("scheme got %q %v", addr, err)
	}
	if _, err := parsePreviewProxyAddr("http://127.0.0.1:1080"); err == nil {
		t.Fatal("http proxy should be rejected")
	}
	if _, err := parsePreviewProxyAddr("socks5://user:pass@127.0.0.1:1080"); err == nil {
		t.Fatal("proxy credentials should be rejected")
	}
	if _, err := parsePreviewProxyAddr("not-a-proxy"); err == nil {
		t.Fatal("missing port should be rejected")
	}
	addr, err = parsePreviewProxyAddr("   ")
	if err != nil || addr != "" {
		t.Fatalf("empty got %q %v", addr, err)
	}
}

func TestLinkPreviewProxyInvalidEnvIsIgnored(t *testing.T) {
	var buf bytes.Buffer
	t.Setenv(linkPreviewProxyEnv, "not-a-proxy")
	svc := NewLinkPreviewService(zerolog.New(&buf))
	if svc.proxyDialer != nil {
		t.Fatal("invalid proxy was installed")
	}
	if !strings.Contains(buf.String(), "MESSAGES_PREVIEW_PROXY") {
		t.Fatalf("log = %s", buf.String())
	}
}

func TestLinkPreviewUsesSOCKS5ForPageAndImage(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/img") {
			w.Header().Set("Content-Type", "image/webp")
			_, _ = w.Write([]byte("via-socks-image"))
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write([]byte(`<!doctype html><html><head><meta property="og:title" content="Via Proxy"></head></html>`))
	}))
	defer srv.Close()

	socks := startFakeSOCKS5(t, true)
	svc := NewLinkPreviewService(zerolog.Nop())
	svc.allowPrivateHosts = true
	if err := svc.setPreviewProxy(socks.addr); err != nil {
		t.Fatal(err)
	}

	preview, err := svc.Fetch(context.Background(), srv.URL+"/story")
	if err != nil {
		t.Fatal(err)
	}
	if preview.Title != "Via Proxy" {
		t.Fatalf("title = %q", preview.Title)
	}
	imageBody, contentType, err := svc.FetchImage(context.Background(), srv.URL+"/img.webp")
	if err != nil {
		t.Fatal(err)
	}
	if string(imageBody) != "via-socks-image" || contentType != "image/webp" {
		t.Fatalf("image = %q %s", imageBody, contentType)
	}

	wantAddr := srv.Listener.Addr().String()
	targets := socks.targets()
	if len(targets) == 0 {
		t.Fatal("preview dial skipped the SOCKS5 proxy")
	}
	for _, target := range targets {
		host, _, err := net.SplitHostPort(target)
		if err != nil {
			t.Fatal(err)
		}
		if net.ParseIP(host) == nil {
			t.Fatalf("proxy was asked to dial hostname %q", target)
		}
		if target != wantAddr {
			t.Fatalf("proxy target = %q, want %q", target, wantAddr)
		}
	}
}

func TestLinkPreviewProxySendsResolvedIP(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()
	_, port, err := net.SplitHostPort(srv.Listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}

	socks := startFakeSOCKS5(t, true)
	svc := NewLinkPreviewService(zerolog.Nop())
	svc.allowPrivateHosts = true
	if err := svc.setPreviewProxy(socks.addr); err != nil {
		t.Fatal(err)
	}
	conn, err := svc.guardedDialContext(context.Background(), "tcp", "localhost:"+port)
	if err != nil {
		t.Fatal(err)
	}
	conn.Close()

	targets := socks.targets()
	if len(targets) == 0 {
		t.Fatal("no SOCKS5 connect")
	}
	for _, target := range targets {
		host, gotPort, err := net.SplitHostPort(target)
		if err != nil {
			t.Fatal(err)
		}
		if gotPort != port {
			t.Fatalf("port = %s, want %s", gotPort, port)
		}
		if net.ParseIP(host) == nil || strings.Contains(target, "localhost") {
			t.Fatalf("proxy target %q was not a resolved IP", target)
		}
	}
}

func TestLinkPreviewProxySkipsPrivateTargets(t *testing.T) {
	socks := startFakeSOCKS5(t, true)
	svc := NewLinkPreviewService(zerolog.Nop())
	if err := svc.setPreviewProxy(socks.addr); err != nil {
		t.Fatal(err)
	}
	for _, addr := range []string{"127.0.0.1:9", "10.1.2.3:80", "[::1]:9"} {
		_, err := svc.guardedDialContext(context.Background(), "tcp", addr)
		if !errors.Is(err, ErrBlockedLinkPreviewURL) {
			t.Fatalf("%s: got %v, want blocked", addr, err)
		}
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()
	if _, err := svc.Fetch(context.Background(), srv.URL); !errors.Is(err, ErrBlockedLinkPreviewURL) {
		t.Fatalf("Fetch got %v", err)
	}
	if _, _, err := svc.FetchImage(context.Background(), srv.URL+"/a.webp"); !errors.Is(err, ErrBlockedLinkPreviewURL) {
		t.Fatalf("FetchImage got %v", err)
	}
	if got := socks.targets(); len(got) != 0 {
		t.Fatalf("private targets reached the proxy: %v", got)
	}
}

func TestLinkPreviewLoopbackProxyReachesPublicIP(t *testing.T) {
	socks := startFakeSOCKS5(t, false)
	svc := NewLinkPreviewService(zerolog.Nop())
	if err := svc.setPreviewProxy(socks.addr); err != nil {
		t.Fatal(err)
	}
	_, err := svc.guardedDialContext(context.Background(), "tcp", "1.1.1.1:443")
	if err == nil || errors.Is(err, ErrBlockedLinkPreviewURL) {
		t.Fatalf("got %v, want a proxy failure for the public target", err)
	}
	targets := socks.targets()
	if len(targets) != 1 || targets[0] != "1.1.1.1:443" {
		t.Fatalf("proxy targets = %v", targets)
	}
}

func TestLinkPreviewProxyEnvUnsetDialsDirectly(t *testing.T) {
	t.Setenv(linkPreviewProxyEnv, "")
	socks := startFakeSOCKS5(t, true)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/png")
		_, _ = w.Write([]byte("direct"))
	}))
	defer srv.Close()

	svc := NewLinkPreviewService(zerolog.Nop())
	if svc.proxyDialer != nil {
		t.Fatal("unset proxy installed a dialer")
	}
	svc.allowPrivateHosts = true
	data, _, err := svc.FetchImage(context.Background(), srv.URL+"/a.png")
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "direct" {
		t.Fatalf("data = %q", data)
	}
	if got := socks.targets(); len(got) != 0 {
		t.Fatalf("direct dial used the proxy: %v", got)
	}
}

func TestLinkPreviewProxyFromEnv(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html")
		_, _ = w.Write([]byte(`<html><head><meta property="og:title" content="Env"></head></html>`))
	}))
	defer srv.Close()
	socks := startFakeSOCKS5(t, true)
	t.Setenv(linkPreviewProxyEnv, "socks5://"+socks.addr)

	svc := NewLinkPreviewService(zerolog.Nop())
	if svc.proxyDialer == nil {
		t.Fatal("env proxy was not installed")
	}
	svc.allowPrivateHosts = true
	preview, err := svc.Fetch(context.Background(), srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	if preview.Title != "Env" {
		t.Fatalf("title = %q", preview.Title)
	}
	if len(socks.targets()) == 0 {
		t.Fatal("env proxy was not used")
	}
}

type fakeSOCKS5 struct {
	addr string
	ln   net.Listener
	dial bool

	mu  sync.Mutex
	got []string
}

func startFakeSOCKS5(t *testing.T, dial bool) *fakeSOCKS5 {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	s := &fakeSOCKS5{addr: ln.Addr().String(), ln: ln, dial: dial}
	go s.accept()
	t.Cleanup(func() { _ = ln.Close() })
	return s
}

func (s *fakeSOCKS5) targets() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]string, len(s.got))
	copy(out, s.got)
	return out
}

func (s *fakeSOCKS5) accept() {
	for {
		conn, err := s.ln.Accept()
		if err != nil {
			return
		}
		go s.serve(conn)
	}
}

func (s *fakeSOCKS5) serve(conn net.Conn) {
	defer conn.Close()
	header := make([]byte, 2)
	if _, err := io.ReadFull(conn, header); err != nil || header[0] != 5 {
		return
	}
	methods := make([]byte, int(header[1]))
	if _, err := io.ReadFull(conn, methods); err != nil {
		return
	}
	if _, err := conn.Write([]byte{5, 0}); err != nil {
		return
	}
	req := make([]byte, 4)
	if _, err := io.ReadFull(conn, req); err != nil || req[0] != 5 || req[1] != 1 {
		return
	}
	host, err := readSOCKSHost(conn, req[3])
	if err != nil {
		return
	}
	portBytes := make([]byte, 2)
	if _, err := io.ReadFull(conn, portBytes); err != nil {
		return
	}
	port := int(portBytes[0])<<8 | int(portBytes[1])
	target := net.JoinHostPort(host, strconv.Itoa(port))
	s.mu.Lock()
	s.got = append(s.got, target)
	dial := s.dial
	s.mu.Unlock()
	if !dial {
		_, _ = conn.Write([]byte{5, 1, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	dest, err := net.Dial("tcp", target)
	if err != nil {
		_, _ = conn.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer dest.Close()
	if _, err := conn.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}
	go func() {
		_, _ = io.Copy(dest, conn)
		_ = dest.Close()
	}()
	_, _ = io.Copy(conn, dest)
}

func readSOCKSHost(r io.Reader, atyp byte) (string, error) {
	switch atyp {
	case 1:
		buf := make([]byte, 4)
		if _, err := io.ReadFull(r, buf); err != nil {
			return "", err
		}
		return net.IP(buf).String(), nil
	case 4:
		buf := make([]byte, 16)
		if _, err := io.ReadFull(r, buf); err != nil {
			return "", err
		}
		return net.IP(buf).String(), nil
	case 3:
		n := make([]byte, 1)
		if _, err := io.ReadFull(r, n); err != nil {
			return "", err
		}
		buf := make([]byte, int(n[0]))
		if _, err := io.ReadFull(r, buf); err != nil {
			return "", err
		}
		return string(buf), nil
	default:
		return "", errors.New("unsupported socks address type")
	}
}
