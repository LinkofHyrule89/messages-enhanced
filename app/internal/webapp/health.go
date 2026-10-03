package webapp

// Health monitor: watches the Google Messages connection and (when
// configured) the VPN. When one has been down for Config.HealthAfter
// (default 3 min) it sends a Web Push alert to every subscribed device, and
// another when it recovers. GET /api/app/health feeds the in-app banner.
//
// VPN checks (both optional, both run as the app's own user, so they see
// exactly what the app's traffic sees, including a kill switch):
//   - MESSAGES_HEALTH_VPN_IFACE: the interface (e.g. wg0) must exist
//   - MESSAGES_HEALTH_EXIT_IP: the public IP from MESSAGES_HEALTH_IP_URL
//     must equal it (a stale WireGuard handshake behind a kill switch makes
//     this fetch fail, so it also covers the handshake)

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const healthInterval = 30 * time.Second

type healthCheck struct {
	Name      string `json:"name"`
	OK        bool   `json:"ok"`
	Detail    string `json:"detail,omitempty"`
	DownSince int64  `json:"down_since_ms,omitempty"`
	Alerted   bool   `json:"alerted,omitempty"`
	CheckedMS int64  `json:"checked_ms,omitempty"`
}

type HealthMonitor struct {
	mu     sync.Mutex
	cfg    Config
	google func() any
	push   *PushHub
	now    func() time.Time
	client *http.Client
	checks map[string]*healthCheck // "google", "vpn"
	// alert overrides the push sender (tests).
	alert func(title, body, tag string)
}

func NewHealthMonitor(cfg Config, google func() any, push *PushHub) *HealthMonitor {
	h := &HealthMonitor{cfg: cfg, google: google, push: push, now: time.Now,
		client: ipv4Client(), checks: map[string]*healthCheck{}}
	if cfg.HealthAfter <= 0 {
		h.cfg.HealthAfter = 3 * time.Minute
	}
	return h
}

// ipv4Client: the expected exit IP is an IPv4 address, so ask over IPv4
// (an IPv6 answer would never match).
func ipv4Client() *http.Client {
	d := &net.Dialer{Timeout: 8 * time.Second}
	tr := http.DefaultTransport.(*http.Transport).Clone()
	tr.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		return d.DialContext(ctx, "tcp4", addr)
	}
	return &http.Client{Timeout: 10 * time.Second, Transport: tr}
}

func (h *HealthMonitor) vpnConfigured() bool {
	return h.cfg.HealthVPNIface != "" || h.cfg.HealthExitIP != ""
}

// Enabled: something to watch.
func (h *HealthMonitor) Enabled() bool {
	return h != nil && !h.cfg.HealthOff && (h.google != nil || h.vpnConfigured())
}

func (h *HealthMonitor) Run(ctx context.Context) {
	if !h.Enabled() {
		return
	}
	// Give Google a moment to connect after startup before the first look.
	select {
	case <-ctx.Done():
		return
	case <-time.After(20 * time.Second):
	}
	t := time.NewTicker(healthInterval)
	defer t.Stop()
	for {
		h.CheckOnce(ctx)
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// CheckOnce runs every check and sends alerts on transitions.
func (h *HealthMonitor) CheckOnce(ctx context.Context) {
	if h.google != nil {
		ok, detail := googleHealthy(h.google())
		h.record("google", ok, detail)
	}
	if h.vpnConfigured() {
		ok, detail := h.vpnHealthy(ctx)
		h.record("vpn", ok, detail)
	}
}

func googleHealthy(st any) (bool, string) {
	b, err := json.Marshal(st)
	if err != nil {
		return true, ""
	}
	var s struct {
		Connected    bool   `json:"connected"`
		NeedsPairing bool   `json:"needs_pairing"`
		AuthExpired  bool   `json:"auth_expired"`
		LastError    string `json:"last_error"`
	}
	if json.Unmarshal(b, &s) != nil {
		return true, ""
	}
	switch {
	case s.NeedsPairing:
		return false, "needs pairing"
	case s.AuthExpired:
		return false, "Google sign-in expired"
	case !s.Connected:
		return false, "disconnected"
	}
	return true, ""
}

func (h *HealthMonitor) vpnHealthy(ctx context.Context) (bool, string) {
	if ifc := h.cfg.HealthVPNIface; ifc != "" {
		if _, err := os.Stat(filepath.Join("/sys/class/net", filepath.Base(ifc))); err != nil {
			return false, ifc + " is down"
		}
	}
	if want := h.cfg.HealthExitIP; want != "" {
		ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
		defer cancel()
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, h.cfg.HealthIPURL, nil)
		if err != nil {
			return false, "exit IP check misconfigured"
		}
		req.Header.Set("User-Agent", "curl/8")
		resp, err := h.client.Do(req)
		if err != nil {
			return false, "no internet through the VPN"
		}
		defer resp.Body.Close()
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 256))
		got := strings.TrimSpace(string(b))
		if resp.StatusCode != 200 {
			return false, "exit IP check failed"
		}
		if got != want {
			return false, "traffic isn't leaving through the VPN"
		}
	}
	return true, ""
}

func (h *HealthMonitor) record(name string, ok bool, detail string) {
	now := h.now()
	h.mu.Lock()
	c := h.checks[name]
	if c == nil {
		c = &healthCheck{Name: name, OK: true}
		h.checks[name] = c
	}
	c.CheckedMS = now.UnixMilli()
	var title, body string
	switch {
	case !ok:
		if c.OK || c.DownSince == 0 {
			c.DownSince = now.UnixMilli()
		}
		c.OK, c.Detail = false, detail
		if !c.Alerted && now.Sub(time.UnixMilli(c.DownSince)) >= h.cfg.HealthAfter {
			c.Alerted = true
			title, body = healthAlertText(name, false, detail)
		}
	case !c.OK:
		if c.Alerted {
			title, body = healthAlertText(name, true, "")
		}
		c.OK, c.Detail, c.DownSince, c.Alerted = true, "", 0, false
	}
	h.mu.Unlock()
	if title != "" {
		h.send(title, body, "health-"+name)
	}
}

func healthAlertText(name string, recovered bool, detail string) (string, string) {
	what := "Google Messages"
	if name == "vpn" {
		what = "VPN"
	}
	if recovered {
		return what + " is back", "Messages Enhanced: " + what + " recovered."
	}
	body := what + " has been down for a few minutes"
	if detail != "" {
		body += " (" + detail + ")"
	}
	return what + " is down", body + ". New messages may not arrive."
}

func (h *HealthMonitor) send(title, body, tag string) {
	if h.alert != nil {
		h.alert(title, body, tag)
		return
	}
	if h.push != nil && h.push.Ready() {
		h.push.SendAlert(title, body, tag)
	}
}

// Snapshot is the banner state: checks that are currently down.
func (h *HealthMonitor) Snapshot() map[string]any {
	out := map[string]any{"enabled": h.Enabled()}
	if h == nil {
		return out
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	list := []healthCheck{}
	for _, n := range []string{"google", "vpn"} {
		if c := h.checks[n]; c != nil {
			list = append(list, *c)
		}
	}
	out["checks"] = list
	out["alert_after_ms"] = h.cfg.HealthAfter.Milliseconds()
	return out
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, s.health.Snapshot())
}
