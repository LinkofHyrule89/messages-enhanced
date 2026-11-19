package libgm

import (
	"context"
	"io"
	"net"
	"testing"
	"time"
)

func TestDialPreferIPv4ReachesIPv4Listener(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	accepted := make(chan struct{})
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		_, _ = io.WriteString(conn, "ok")
		_ = conn.Close()
		close(accepted)
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	conn, err := dialPreferIPv4(ctx, "tcp", ln.Addr().String())
	if err != nil {
		t.Fatalf("dial = %v", err)
	}
	defer conn.Close()
	buf := make([]byte, 2)
	_ = conn.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := io.ReadFull(conn, buf); err != nil || string(buf) != "ok" {
		t.Fatalf("read = %q, %v", buf, err)
	}
	select {
	case <-accepted:
	case <-time.After(time.Second):
		t.Fatal("listener did not accept")
	}
}
