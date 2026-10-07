package main

import (
	"encoding/json"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"testing"
	"time"
)

const (
	gmessagesModule            = "go.mau.fi/mautrix-gmessages"
	gmessagesFork              = "github.com/MaxGhenis/gmessages"
	minimumGMessagesFork       = "v0.2602.1-0.20260703132304-0e43542dfa0e"
	minimumGMessagesForkTime   = "20260703132304"
	gmessagesAuthRetryContract = "libgm/longpoll auth-refresh network retry"
	// The fork is vendored (with Messages Enhanced patches) here; its
	// PATCHED.md records the fork pseudo-version it was copied from.
	gmessagesVendoredPath = "./third_party/gmessages"
)

var pseudoVersionSuffix = regexp.MustCompile(`[.-]([0-9]{14})-[0-9a-f]{12}$`)

type moduleVersion struct {
	Path    string
	Version string
}

type goModFile struct {
	Require []moduleVersion
	Replace []struct {
		Old moduleVersion
		New moduleVersion
	}
}

func TestGMessagesForkContract(t *testing.T) {
	cmd := exec.Command("go", "mod", "edit", "-json", "go.mod")
	cmd.Env = envWithGOWorkOff()
	output, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("parse go.mod: %v\n%s", err, output)
	}

	var mod goModFile
	if err := json.Unmarshal(output, &mod); err != nil {
		t.Fatalf("decode go.mod: %v", err)
	}

	required := false
	for _, requirement := range mod.Require {
		if requirement.Path == gmessagesModule {
			required = true
			break
		}
	}
	if !required {
		t.Fatalf("%s must remain required so the fork replacement protects the %s", gmessagesModule, gmessagesAuthRetryContract)
	}

	var replacements []struct {
		Old moduleVersion
		New moduleVersion
	}
	for _, replacement := range mod.Replace {
		if replacement.Old.Path == gmessagesModule {
			replacements = append(replacements, replacement)
		}
	}
	if len(replacements) != 1 {
		t.Fatalf("%s must have exactly one fork replacement; found %d", gmessagesModule, len(replacements))
	}

	replacement := replacements[0]
	if replacement.Old.Version != "" {
		t.Fatalf("replacement for %s must be unversioned so future require versions cannot bypass it", gmessagesModule)
	}
	forkVersion := replacement.New.Version
	switch replacement.New.Path {
	case gmessagesFork:
	case gmessagesVendoredPath:
		// Vendored copy of the fork: PATCHED.md must name the fork version it
		// came from, and the auth-refresh network retry must still be there.
		notes, err := os.ReadFile(gmessagesVendoredPath + "/PATCHED.md")
		if err != nil {
			t.Fatalf("vendored %s has no PATCHED.md recording its fork version: %v", gmessagesModule, err)
		}
		m := regexp.MustCompile(regexp.QuoteMeta(gmessagesFork) + "`? at\\s+`?(v[0-9][^`\\s]*)").FindSubmatch(notes)
		if m == nil {
			t.Fatalf("PATCHED.md must say which %s version was vendored", gmessagesFork)
		}
		forkVersion = string(m[1])
		src, err := os.ReadFile(gmessagesVendoredPath + "/pkg/libgm/longpoll.go")
		if err != nil || !regexp.MustCompile(`(?s)err := c\.refreshAuthToken\(nil\).{0,120}exhttp\.IsNetworkError\(err\)`).Match(src) {
			t.Fatalf("vendored libgm lost the %s", gmessagesAuthRetryContract)
		}
	default:
		t.Fatalf("%s must be replaced by %s (or its vendored copy %s) to preserve the %s; found %s", gmessagesModule, gmessagesFork, gmessagesVendoredPath, gmessagesAuthRetryContract, replacement.New.Path)
	}

	match := pseudoVersionSuffix.FindStringSubmatch(forkVersion)
	if match == nil {
		t.Fatalf("%s must use a canonical pseudo-version that records its commit time and revision; found %q", gmessagesFork, forkVersion)
	}
	pinnedTime, err := time.Parse("20060102150405", match[1])
	if err != nil {
		t.Fatalf("parse %s pseudo-version timestamp %q: %v", gmessagesFork, match[1], err)
	}
	minimumTime, err := time.Parse("20060102150405", minimumGMessagesForkTime)
	if err != nil {
		t.Fatalf("parse test minimum timestamp: %v", err)
	}
	if pinnedTime.Before(minimumTime) {
		t.Fatalf("%s pin %s predates known-good %s and can regress the %s", gmessagesFork, forkVersion, minimumGMessagesFork, gmessagesAuthRetryContract)
	}
}

func envWithGOWorkOff() []string {
	env := os.Environ()
	for i := 0; i < len(env); {
		if strings.HasPrefix(env[i], "GOWORK=") {
			env = append(env[:i], env[i+1:]...)
			continue
		}
		i++
	}
	return append(env, "GOWORK=off")
}
