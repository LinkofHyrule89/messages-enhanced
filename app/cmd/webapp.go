package cmd

import (
	"errors"
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/rs/zerolog"
	"golang.org/x/term"

	"github.com/maxghenis/openmessage/internal/app"
	"github.com/maxghenis/openmessage/internal/webapp"
)

// RunCookieVault stores pasted Google cookies in the encrypted cookie vault
// so the web pairing screen can use them. It prints cookie names only.
func RunCookieVault(logger zerolog.Logger, args ...string) error {
	secret := strings.TrimSpace(webapp.Getenv("MESSAGES_SECRET"))
	if len(secret) < 16 {
		return errors.New("set MESSAGES_SECRET (same value the server uses, >= 16 chars)")
	}
	vault := webapp.NewCookieVault(app.DefaultDataDir(), secret)
	var input []byte
	var err error
	switch {
	case len(args) > 0 && args[0] == "--clear":
		if err := vault.Clear(); err != nil {
			return err
		}
		fmt.Println("Stored Google cookies deleted.")
		return nil
	case len(args) > 0 && args[0] == "--status":
		c, saved, err := vault.Load()
		if err != nil {
			return err
		}
		names := make([]string, 0, len(c))
		for k := range c {
			names = append(names, k)
		}
		fmt.Printf("%d cookies stored %s: %s\n", len(c), saved.Format("2006-01-02 15:04 MST"), strings.Join(names, ", "))
		return nil
	case len(args) > 1 && args[0] == "--file":
		input, err = os.ReadFile(args[1])
	case len(args) == 0:
		if term.IsTerminal(int(os.Stdin.Fd())) {
			fmt.Println("Paste a Google cookie JSON object, a devtools 'Copy as cURL' command, or a Cookie header, then press Ctrl-D:")
		}
		input, err = io.ReadAll(io.LimitReader(os.Stdin, 256<<10))
	default:
		return fmt.Errorf("usage: openmessage google-cookies [--file path|--status|--clear]")
	}
	if err != nil {
		return fmt.Errorf("read cookies: %w", err)
	}
	cookies, missing, err := webapp.ParseGoogleCookies(string(input))
	if err != nil {
		return fmt.Errorf("parse cookies: %w", err)
	}
	if len(missing) > 0 {
		return fmt.Errorf("missing required cookies: %s (nothing saved)", strings.Join(missing, ", "))
	}
	if err := vault.Save(cookies); err != nil {
		return err
	}
	fmt.Printf("Saved %d Google cookies to the encrypted vault in %s\n", len(cookies), app.DefaultDataDir())
	return nil
}
