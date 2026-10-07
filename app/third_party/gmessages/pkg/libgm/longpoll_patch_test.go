package libgm

import (
	"testing"
	"time"

	"github.com/rs/zerolog"
)

func TestPingFailBackoff(t *testing.T) {
	want := map[int]time.Duration{0: 30 * time.Second, 1: 30 * time.Second, 2: time.Minute, 3: 2 * time.Minute,
		5: 8 * time.Minute, 6: 10 * time.Minute, 1000: 10 * time.Minute}
	for fails, w := range want {
		if got := pingFailBackoff(fails); got != w {
			t.Errorf("pingFailBackoff(%d) = %v, want %v", fails, got, w)
		}
	}
}

func TestListenRetryDelay(t *testing.T) {
	want := map[int]time.Duration{2: 10 * time.Second, 3: 20 * time.Second, 4: 40 * time.Second, 7: 5 * time.Minute, 100000: 5 * time.Minute}
	for n, w := range want {
		if got := listenRetryDelay(n); got != w {
			t.Errorf("listenRetryDelay(%d) = %v, want %v", n, got, w)
		}
	}
}

// Disconnect must stop a listen loop that has no open connection (it's
// sleeping between retries because the network is down).
func TestDisconnectStopsRetryingListenLoop(t *testing.T) {
	c := NewClient(NewAuthData(), nil, zerolog.Nop())
	c.listenID = 1
	stopped := make(chan bool, 1)
	go func() { stopped <- c.sleepUnlessStopped(1, time.Minute) }()
	time.Sleep(50 * time.Millisecond)
	c.Disconnect()
	select {
	case ok := <-stopped:
		if ok {
			t.Fatal("sleep reported completion after Disconnect")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("retry sleep didn't wake up after Disconnect")
	}
	if c.listenID == 1 {
		t.Fatal("Disconnect with no connection didn't end the listen loop")
	}
}

func TestPingSkippedDuringFailBackoff(t *testing.T) {
	c := NewClient(NewAuthData(), nil, zerolog.Nop())
	log := zerolog.Nop()
	dp := &dittoPinger{client: c, log: &log, pingFails: 4, lastPingTime: time.Now().Add(-time.Minute)}
	dp.Ping(1, time.Second, 0, newResetter())
	if dp.pingFails != 4 {
		t.Fatalf("ping attempted during backoff (fails=%d)", dp.pingFails)
	}
}
