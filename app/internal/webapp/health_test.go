package webapp

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

type fakeGoogle struct {
	mu        sync.Mutex
	connected bool
}

func (f *fakeGoogle) status() any {
	f.mu.Lock()
	defer f.mu.Unlock()
	return map[string]any{"connected": f.connected, "paired": true}
}

// Alerts only after the outage lasts HealthAfter, once, and once more on recovery.
func TestHealthAlertsAfterThresholdAndOnRecovery(t *testing.T) {
	g := &fakeGoogle{connected: true}
	ipOK := true
	ipSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if ipOK {
			_, _ = w.Write([]byte("198.51.100.7\n"))
		} else {
			_, _ = w.Write([]byte("203.0.113.9"))
		}
	}))
	defer ipSrv.Close()
	cfg := Config{HealthAfter: 3 * time.Minute, HealthExitIP: "198.51.100.7", HealthIPURL: ipSrv.URL}
	h := NewHealthMonitor(cfg, g.status, nil)
	now := time.Unix(1_700_000_000, 0)
	h.now = func() time.Time { return now }
	var alerts []string
	h.alert = func(title, body, tag string) { alerts = append(alerts, tag+":"+title) }
	ctx := context.Background()
	step := func(d time.Duration) { now = now.Add(d); h.CheckOnce(ctx) }

	step(0)
	if len(alerts) != 0 {
		t.Fatalf("healthy: %v", alerts)
	}
	g.mu.Lock()
	g.connected = false
	g.mu.Unlock()
	ipOK = false
	step(30 * time.Second)
	step(2 * time.Minute)
	if len(alerts) != 0 {
		t.Fatalf("alerted before 3 min: %v", alerts)
	}
	step(61 * time.Second) // 3m01s down
	if len(alerts) != 2 || alerts[0] != "health-google:Google Messages is down" || alerts[1] != "health-vpn:VPN is down" {
		t.Fatalf("down alerts: %v", alerts)
	}
	step(time.Minute)
	if len(alerts) != 2 {
		t.Fatalf("repeated alert: %v", alerts)
	}
	snap := h.Snapshot()
	if list := snap["checks"].([]healthCheck); len(list) != 2 || list[0].OK || list[0].DownSince == 0 {
		t.Fatalf("snapshot: %+v", snap)
	}
	g.mu.Lock()
	g.connected = true
	g.mu.Unlock()
	ipOK = true
	step(30 * time.Second)
	if len(alerts) != 4 || alerts[2] != "health-google:Google Messages is back" || alerts[3] != "health-vpn:VPN is back" {
		t.Fatalf("recovery alerts: %v", alerts)
	}
	// A short blip: no alert, no recovery notice.
	g.mu.Lock()
	g.connected = false
	g.mu.Unlock()
	step(30 * time.Second)
	g.mu.Lock()
	g.connected = true
	g.mu.Unlock()
	step(30 * time.Second)
	if len(alerts) != 4 {
		t.Fatalf("blip alerted: %v", alerts)
	}
}

func TestHealthDisabledWithoutChecks(t *testing.T) {
	if NewHealthMonitor(Config{}, nil, nil).Enabled() {
		t.Fatal("nothing to watch should be disabled")
	}
	if NewHealthMonitor(Config{HealthOff: true}, func() any { return nil }, nil).Enabled() {
		t.Fatal("MESSAGES_HEALTH=0 should disable")
	}
}

func TestGroqKeyPicksGroqProvider(t *testing.T) {
	t.Setenv("MESSAGES_SECRET", "0123456789abcdef0123")
	t.Setenv("MESSAGES_STT_PROVIDER", "")
	t.Setenv("GROQ_API_KEY", "gsk_test")
	c, err := ConfigFromEnv()
	if err != nil {
		t.Fatal(err)
	}
	if c.STTProvider != "groq" {
		t.Fatalf("provider %q", c.STTProvider)
	}
	tr, err := NewTranscriber(c)
	if err != nil || tr.Name() != "groq:whisper-large-v3-turbo" || !cloudSTT(tr.Name()) {
		t.Fatalf("transcriber %v %v", tr, err)
	}
}
