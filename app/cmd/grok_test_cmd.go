package cmd

import (
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/rs/zerolog"

	"github.com/maxghenis/openmessage/internal/app"
)

// RunGrokTest asks @Grok one question through the same request path the
// live auto-reply uses (Responses API + web/X search) and prints the reply.
// Nothing is sent to any conversation. Needs XAI_API_KEY.
func RunGrokTest(logger zerolog.Logger, args ...string) error {
	q := strings.TrimSpace(strings.Join(args, " "))
	if q == "" {
		return errors.New("usage: grok-test <question>")
	}
	a := &app.App{Logger: logger}
	start := time.Now()
	reply, searches, err := a.GrokTestAnswer(q)
	if err != nil {
		return err
	}
	fmt.Printf("%s\n\n(took %s, %d search calls, %d chars)\n", reply, time.Since(start).Round(100*time.Millisecond), searches, len([]rune(reply)))
	return nil
}
