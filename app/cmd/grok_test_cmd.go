package cmd

import (
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/rs/zerolog"

	"github.com/maxghenis/openmessage/internal/app"
)

// RunGrokTest asks @Grok (or, with --groq, @Groq) one question through the
// same request path the live auto-reply uses and prints the reply, plus
// whether a picture would be sent. Nothing is sent to any conversation.
// Needs XAI_API_KEY (or GROQ_API_KEY).
func RunGrokTest(logger zerolog.Logger, args ...string) error {
	bot := "Grok"
	if len(args) > 0 && (args[0] == "--groq" || args[0] == "-groq") {
		bot, args = "Groq", args[1:]
	}
	q := strings.TrimSpace(strings.Join(args, " "))
	if q == "" {
		return errors.New("usage: grok-test [--groq] <question>")
	}
	a := &app.App{Logger: logger}
	start := time.Now()
	reply, searches, err := a.BotTestAnswer(bot, q)
	if err != nil {
		return err
	}
	fmt.Printf("%s\n\n(took %s, %d search calls, %d chars)\n", reply, time.Since(start).Round(100*time.Millisecond), searches, len([]rune(reply)))
	return nil
}
