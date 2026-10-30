package webapp

import (
	"testing"

	"github.com/rs/zerolog"
)

func TestAutoRepairerEnabledDefaultOn(t *testing.T) {
	a := NewAutoRepairer(nil, nil, nil, "", nil, nil, zerolog.Nop())
	if !a.Enabled() {
		t.Fatal("nil settings should default auto-repair on")
	}
	snap := a.Snapshot()
	if snap["auto_repair_enabled"] != true {
		t.Fatalf("snapshot enabled: %v", snap["auto_repair_enabled"])
	}
	if snap["sign_in_needed"] != false {
		t.Fatalf("sign_in_needed: %v", snap["sign_in_needed"])
	}
	a.MarkSignInNeeded()
	if !a.SignInNeeded() {
		t.Fatal("expected sign-in needed after Mark")
	}
	a.ClearSignInNeeded()
	if a.SignInNeeded() {
		t.Fatal("expected cleared")
	}
}

func TestLooksLikeRejectedSession(t *testing.T) {
	if !looksLikeRejectedSession("invalid cookies") {
		t.Fatal("cookies")
	}
	if !looksLikeRejectedSession("401 unauthorized") {
		t.Fatal("401")
	}
	if looksLikeRejectedSession("network timeout") {
		t.Fatal("timeout should not force sign-in")
	}
}
