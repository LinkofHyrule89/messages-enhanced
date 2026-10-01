package webapp

// Compatibility with installs made before the project was renamed to
// Messages Enhanced. Everything that still uses the old names lives here.

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

const (
	legacyEnvPrefix = "TESLA_"
	legacyURLPrefix = "/tesla/"
	legacyAPIPrefix = "/api/tesla/"
	legacyVaultFile = "tesla-google-cookies.enc"
	legacyThemesDir = "tesla-themes"
	legacyVaultAAD  = "tesla-messages/cookies/v1"
	newEnvPrefix    = "MESSAGES_"
	newURLPrefix    = "/app/"
	newAPIPrefix    = "/api/app/"
)

// Getenv reads MESSAGES_<NAME>, falling back to the old TESLA_<NAME> so
// existing env files keep working.
func Getenv(key string) string {
	if v, ok := os.LookupEnv(key); ok {
		return v
	}
	if rest, ok := strings.CutPrefix(key, newEnvPrefix); ok {
		return os.Getenv(legacyEnvPrefix + rest)
	}
	return ""
}

// migrateLegacyFile renames dataDir/oldName to dataDir/newName once, if the
// new one doesn't exist yet.
func migrateLegacyFile(dataDir, oldName, newName string) {
	oldPath, newPath := filepath.Join(dataDir, oldName), filepath.Join(dataDir, newName)
	if _, err := os.Stat(newPath); err == nil {
		return
	}
	if _, err := os.Stat(oldPath); err == nil {
		_ = os.Rename(oldPath, newPath)
	}
}

// registerLegacyRoutes permanently redirects old page and API URLs (installed
// apps, bookmarks, the cookie extension) to the new prefixes. 308 keeps the
// method and body for POSTs.
func registerLegacyRoutes(mux *http.ServeMux) {
	redirect := func(oldPrefix, newPrefix string) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			u := newPrefix + strings.TrimPrefix(r.URL.Path, oldPrefix)
			if r.URL.RawQuery != "" {
				u += "?" + r.URL.RawQuery
			}
			http.Redirect(w, r, u, http.StatusPermanentRedirect)
		}
	}
	mux.HandleFunc(legacyURLPrefix, redirect(legacyURLPrefix, newURLPrefix))
	mux.HandleFunc(legacyAPIPrefix, redirect(legacyAPIPrefix, newAPIPrefix))
}
