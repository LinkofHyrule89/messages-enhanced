package webapp

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	utilcurl "go.mau.fi/util/curl"
	"golang.org/x/crypto/scrypt"
)

// VaultFile is the encrypted Google cookie file, stored in the (non-git)
// OpenMessage data directory.
const VaultFile = "google-cookies.enc"

// RequiredGoogleCookies must all be present before pairing is attempted.
var RequiredGoogleCookies = []string{"SID", "HSID", "SSID", "OSID", "APISID", "SAPISID"}

// OptionalGoogleCookies are kept when pasted because Google sometimes needs them.
var OptionalGoogleCookies = []string{"__Secure-1PSID", "__Secure-3PSID", "__Secure-1PSIDTS", "__Secure-3PSIDTS", "__Secure-1PAPISID", "__Secure-3PAPISID", "NID", "SIDCC"}

// CookieVault stores Google cookies encrypted with AES-256-GCM using a key
// derived (scrypt) from MESSAGES_SECRET, in a 0600 file inside a 0700 dir.
// Copying the data dir alone does not reveal the cookies. Values are never
// logged or returned by any API; only cookie names are.
type CookieVault struct {
	path   string
	secret []byte
	mu     sync.Mutex
}

func NewCookieVault(dataDir, secret string) *CookieVault {
	migrateLegacyFile(dataDir, legacyVaultFile, VaultFile)
	return &CookieVault{path: filepath.Join(dataDir, VaultFile), secret: []byte(secret)}
}

type vaultEnvelope struct {
	V     int    `json:"v"`
	Salt  []byte `json:"salt"`
	Nonce []byte `json:"nonce"`
	Data  []byte `json:"data"`
	Saved int64  `json:"saved_unix"`
}

func (v *CookieVault) key(salt []byte) ([]byte, error) {
	return scrypt.Key(v.secret, salt, 1<<15, 8, 1, 32)
}

func (v *CookieVault) Save(cookies map[string]string) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	plain, err := json.Marshal(cookies)
	if err != nil {
		return err
	}
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return err
	}
	k, err := v.key(salt)
	if err != nil {
		return err
	}
	block, err := aes.NewCipher(k)
	if err != nil {
		return err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return err
	}
	env := vaultEnvelope{V: 1, Salt: salt, Nonce: nonce, Data: gcm.Seal(nil, nonce, plain, []byte("messages-enhanced/cookies/v1")), Saved: time.Now().Unix()}
	out, err := json.Marshal(env)
	if err != nil {
		return err
	}
	dir := filepath.Dir(v.path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".cookies-*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if err := tmp.Chmod(0o600); err != nil {
		tmp.Close()
		return err
	}
	if _, err := tmp.Write(out); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), v.path)
}

var ErrNoCookiesStored = errors.New("no Google cookies stored yet")

func (v *CookieVault) Load() (map[string]string, time.Time, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	raw, err := os.ReadFile(v.path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, time.Time{}, ErrNoCookiesStored
	} else if err != nil {
		return nil, time.Time{}, err
	}
	var env vaultEnvelope
	if err := json.Unmarshal(raw, &env); err != nil || env.V != 1 {
		return nil, time.Time{}, errors.New("cookie vault is corrupt")
	}
	k, err := v.key(env.Salt)
	if err != nil {
		return nil, time.Time{}, err
	}
	block, err := aes.NewCipher(k)
	if err != nil {
		return nil, time.Time{}, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, time.Time{}, err
	}
	plain, err := gcm.Open(nil, env.Nonce, env.Data, []byte("messages-enhanced/cookies/v1"))
	if err != nil {
		plain, err = gcm.Open(nil, env.Nonce, env.Data, []byte(legacyVaultAAD))
	}
	if err != nil {
		return nil, time.Time{}, errors.New("cannot decrypt cookie vault (MESSAGES_SECRET changed?); paste cookies again")
	}
	var cookies map[string]string
	if err := json.Unmarshal(plain, &cookies); err != nil {
		return nil, time.Time{}, err
	}
	return cookies, time.Unix(env.Saved, 0), nil
}

func (v *CookieVault) Clear() error {
	v.mu.Lock()
	defer v.mu.Unlock()
	err := os.Remove(v.path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return err
}

// ParseGoogleCookies accepts the same inputs as `openmessage pair --google`:
// a JSON object {"SID": "..."}, a "Copy as cURL" command, or a raw Cookie
// header. It keeps only the Google auth cookies and reports which required
// ones are missing (names only).
func ParseGoogleCookies(raw string) (map[string]string, []string, error) {
	all, err := parseCookieInput(raw)
	if err != nil {
		return nil, nil, err
	}
	keep := map[string]string{}
	for _, name := range append(append([]string{}, RequiredGoogleCookies...), OptionalGoogleCookies...) {
		if val, ok := all[name]; ok && strings.TrimSpace(val) != "" {
			keep[name] = strings.TrimSpace(val)
		}
	}
	var missing []string
	for _, name := range RequiredGoogleCookies {
		if _, ok := keep[name]; !ok {
			missing = append(missing, name)
		}
	}
	return keep, missing, nil
}

func parseCookieInput(raw string) (map[string]string, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return nil, errors.New("nothing pasted")
	}
	if strings.HasPrefix(trimmed, "{") {
		var m map[string]string
		if err := json.Unmarshal([]byte(trimmed), &m); err == nil && len(m) > 0 {
			return m, nil
		}
		// Also accept {"cookies": {...}} or [{name,value}] exports.
		var arr []struct {
			Name  string `json:"name"`
			Value string `json:"value"`
		}
		if err := json.Unmarshal([]byte(trimmed), &arr); err == nil && len(arr) > 0 {
			m = map[string]string{}
			for _, c := range arr {
				m[c.Name] = c.Value
			}
			return m, nil
		}
		return nil, errors.New("JSON must be an object of cookie name to value")
	}
	if strings.HasPrefix(trimmed, "[") {
		var arr []struct {
			Name  string `json:"name"`
			Value string `json:"value"`
		}
		if err := json.Unmarshal([]byte(trimmed), &arr); err != nil || len(arr) == 0 {
			return nil, errors.New("JSON array must contain {name, value} objects")
		}
		m := map[string]string{}
		for _, c := range arr {
			m[c.Name] = c.Value
		}
		return m, nil
	}
	if strings.HasPrefix(trimmed, "curl ") {
		parsed, err := utilcurl.Parse(trimmed)
		if err != nil {
			return nil, fmt.Errorf("could not parse the cURL command")
		}
		return parseCookieHeader(parsed.Header.Get("Cookie"))
	}
	return parseCookieHeader(trimmed)
}

func parseCookieHeader(header string) (map[string]string, error) {
	header = strings.TrimSpace(header)
	if strings.HasPrefix(strings.ToLower(header), "cookie:") {
		header = strings.TrimSpace(header[len("cookie:"):])
	}
	if header == "" {
		return nil, errors.New("no Cookie header found")
	}
	req := &http.Request{Header: http.Header{"Cookie": {header}}}
	m := map[string]string{}
	for _, c := range req.Cookies() {
		if c.Name != "" {
			m[c.Name] = c.Value
		}
	}
	if len(m) == 0 {
		return nil, errors.New("no cookies found")
	}
	return m, nil
}

func cookieNames(m map[string]string) []string {
	names := make([]string, 0, len(m))
	for k := range m {
		names = append(names, k)
	}
	sort.Strings(names)
	return names
}
