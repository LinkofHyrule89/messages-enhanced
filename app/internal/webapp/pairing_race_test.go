package webapp

import (
	"context"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/rs/zerolog"
)

func TestPairerRejectsConcurrentStart(t *testing.T) {
	dir := t.TempDir()
	vault := NewCookieVault(dir, "test-secret-at-least-16b")
	cookies := map[string]string{}
	for _, n := range RequiredGoogleCookies {
		cookies[n] = "test"
	}
	if err := vault.Save(cookies); err != nil {
		t.Fatalf("vault save: %v", err)
	}

	var runs atomic.Int32
	started := make(chan struct{})
	release := make(chan struct{})
	runner := func(ctx context.Context, cookies map[string]string, onEmoji func(string)) error {
		runs.Add(1)
		close(started)
		select {
		case <-release:
		case <-ctx.Done():
			return ctx.Err()
		}
		return nil
	}
	p := NewPairer(vault, runner, "", nil, "", zerolog.Nop())
	if err := p.Start(); err != nil {
		t.Fatalf("first Start: %v", err)
	}
	select {
	case <-started:
	case <-time.After(2 * time.Second):
		t.Fatal("first run did not start")
	}
	if err := p.Start(); err == nil || err.Error() != "pairing already in progress" {
		t.Fatalf("second Start error = %v, want already in progress", err)
	}
	close(release)
	deadline := time.Now().Add(2 * time.Second)
	for p.Status().State != PairSuccess && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if got := runs.Load(); got != 1 {
		t.Fatalf("runner calls = %d, want 1", got)
	}
}

func TestPairerPreparePairingRunsBeforePair(t *testing.T) {
	dir := t.TempDir()
	vault := NewCookieVault(dir, "test-secret-at-least-16b")
	// Minimal vault bypass: use fake emoji path for prepare ordering instead
	var order []string
	var mu sync.Mutex
	p := NewPairer(vault, nil, "🐬", nil, "", zerolog.Nop())
	p.SetPreparePairing(func() error {
		mu.Lock()
		order = append(order, "prepare")
		mu.Unlock()
		return nil
	})
	// Fake path does not call prepare (no network). Test real runner path:
	p2 := NewPairer(vault, func(ctx context.Context, cookies map[string]string, onEmoji func(string)) error {
		mu.Lock()
		order = append(order, "run")
		mu.Unlock()
		onEmoji("🐬")
		return nil
	}, "", nil, "", zerolog.Nop())
	// Need cookies in vault — if required cookies missing, skip
	cookies := map[string]string{}
	for _, n := range RequiredGoogleCookies {
		cookies[n] = "test"
	}
	if err := vault.Save(cookies); err != nil {
		t.Fatalf("save: %v", err)
	}
	p2.SetPreparePairing(func() error {
		mu.Lock()
		order = append(order, "prepare")
		mu.Unlock()
		return nil
	})
	if err := p2.Start(); err != nil {
		t.Fatalf("Start: %v", err)
	}
	deadline := time.Now().Add(2 * time.Second)
	for p2.Status().State != PairSuccess && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	mu.Lock()
	got := append([]string(nil), order...)
	mu.Unlock()
	if len(got) < 2 || got[0] != "prepare" || got[1] != "run" {
		t.Fatalf("order = %v, want prepare then run", got)
	}
	_ = p
}

func TestAutoRepairerSkipsWhilePairingInProgress(t *testing.T) {
	dir := t.TempDir()
	vault := NewCookieVault(dir, "test-secret-at-least-16b")
	cookies := map[string]string{}
	for _, n := range RequiredGoogleCookies {
		cookies[n] = "test"
	}
	if err := vault.Save(cookies); err != nil {
		t.Fatalf("save: %v", err)
	}
	var starts atomic.Int32
	block := make(chan struct{})
	runner := func(ctx context.Context, cookies map[string]string, onEmoji func(string)) error {
		starts.Add(1)
		onEmoji("⭐")
		<-block
		return nil
	}
	p := NewPairer(vault, runner, "", nil, "", zerolog.Nop())
	authExpired := true
	a := NewAutoRepairer(p, vault, nil, "", nil, func() any {
		return map[string]any{"auth_expired": authExpired, "connected": false}
	}, zerolog.Nop())

	if err := p.Start(); err != nil {
		t.Fatalf("manual Start: %v", err)
	}
	deadline := time.Now().Add(2 * time.Second)
	for p.Status().State != PairShowEmoji && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	a.tick() // must not start a second pair
	a.tick()
	if got := starts.Load(); got != 1 {
		t.Fatalf("pairing runs = %d, want 1 (auto must skip)", got)
	}
	close(block)
}

func TestAutoRepairerSkipsJustAfterSuccess(t *testing.T) {
	dir := t.TempDir()
	vault := NewCookieVault(dir, "test-secret-at-least-16b")
	cookies := map[string]string{}
	for _, n := range RequiredGoogleCookies {
		cookies[n] = "test"
	}
	if err := vault.Save(cookies); err != nil {
		t.Fatalf("save: %v", err)
	}
	var starts atomic.Int32
	p := NewPairer(vault, func(ctx context.Context, cookies map[string]string, onEmoji func(string)) error {
		starts.Add(1)
		return nil
	}, "", nil, "", zerolog.Nop())
	if err := p.Start(); err != nil {
		t.Fatalf("Start: %v", err)
	}
	deadline := time.Now().Add(2 * time.Second)
	for p.Status().State != PairSuccess && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if !p.RecentlyCompleted(PairRecentSuccessSkip) {
		t.Fatal("expected RecentlyCompleted after success")
	}
	a := NewAutoRepairer(p, vault, nil, "", nil, func() any {
		return map[string]any{"auth_expired": true, "connected": false}
	}, zerolog.Nop())
	a.tick()
	if got := starts.Load(); got != 1 {
		t.Fatalf("starts = %d, want 1 (auto must skip just-completed)", got)
	}
}

func TestAutoRepairerRaceWithManualCookieStart(t *testing.T) {
	dir := t.TempDir()
	vault := NewCookieVault(dir, "test-secret-at-least-16b")
	cookies := map[string]string{}
	for _, n := range RequiredGoogleCookies {
		cookies[n] = "test"
	}
	if err := vault.Save(cookies); err != nil {
		t.Fatalf("save: %v", err)
	}
	var starts atomic.Int32
	gate := make(chan struct{})
	runner := func(ctx context.Context, cookies map[string]string, onEmoji func(string)) error {
		starts.Add(1)
		onEmoji("🔒")
		select {
		case <-gate:
		case <-ctx.Done():
			return ctx.Err()
		}
		return nil
	}
	p := NewPairer(vault, runner, "", nil, "", zerolog.Nop())
	a := NewAutoRepairer(p, vault, nil, "", nil, func() any {
		return map[string]any{"auth_expired": true}
	}, zerolog.Nop())

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		_ = p.Start()
	}()
	go func() {
		defer wg.Done()
		a.tick()
	}()
	wg.Wait()
	// Give the admitted run a moment to increment
	time.Sleep(50 * time.Millisecond)
	if got := starts.Load(); got != 1 {
		t.Fatalf("concurrent manual+auto starts = %d, want exactly 1", got)
	}
	close(gate)
}
