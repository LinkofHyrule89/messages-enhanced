package libgm

import (
	"errors"
	"testing"
	"time"
)

func TestWaitForRPCResponseHardTimeoutNudgesThenStops(t *testing.T) {
	ch := make(chan *IncomingRPCMessage)
	pinged := false
	start := time.Now()
	_, err := waitForRPCResponse(ch, func() { pinged = true }, 15*time.Millisecond, 40*time.Millisecond)
	if !errors.Is(err, ErrResponseTimeout) {
		t.Fatalf("error = %v, want ErrResponseTimeout", err)
	}
	if !pinged {
		t.Fatal("soft wait did not nudge the phone")
	}
	if time.Since(start) > time.Second {
		t.Fatalf("hard wait took %s", time.Since(start))
	}
}

func TestWaitForRPCResponseBeforeSoftWaitDoesNotNudge(t *testing.T) {
	ch := make(chan *IncomingRPCMessage, 1)
	want := &IncomingRPCMessage{}
	ch <- want
	pinged := false
	got, err := waitForRPCResponse(ch, func() { pinged = true }, time.Second, 2*time.Second)
	if err != nil || got != want || pinged {
		t.Fatalf("got %v err %v pinged %v", got, err, pinged)
	}
}

func TestWaitForRPCResponseAfterSoftWaitReturnsReply(t *testing.T) {
	ch := make(chan *IncomingRPCMessage, 1)
	want := &IncomingRPCMessage{}
	go func() {
		time.Sleep(30 * time.Millisecond)
		ch <- want
	}()
	pinged := false
	got, err := waitForRPCResponse(ch, func() { pinged = true }, 10*time.Millisecond, time.Second)
	if err != nil || got != want || !pinged {
		t.Fatalf("got %v err %v pinged %v", got, err, pinged)
	}
}
