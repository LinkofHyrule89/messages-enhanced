package webapp

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// App-wide settings shared by every signed-in client (the web app's
// tm.settings theme/appearance fields, and the same keys from the native
// app). One small JSON object in the data dir. Per-device prefs stay in
// the browser; per-conversation chat themes stay in the theme store.

const (
	settingsFile     = "app-settings.json"
	maxSettingsBytes = 16 << 10 // 16KB
)

var (
	errSettingsJSON   = errors.New("settings must be a JSON object")
	errSettingsTooBig = errors.New("settings object is too large")
)

// SettingsStore persists one JSON object.
type SettingsStore struct {
	mu   sync.Mutex
	path string
	raw  json.RawMessage
	now  func() time.Time
}

// OpenSettingsStore loads (or starts) dataDir/app-settings.json.
func OpenSettingsStore(dataDir string) (*SettingsStore, error) {
	if dataDir == "" {
		return nil, errors.New("no data dir")
	}
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, err
	}
	s := &SettingsStore{path: filepath.Join(dataDir, settingsFile), now: time.Now}
	b, err := os.ReadFile(s.path)
	switch {
	case errors.Is(err, os.ErrNotExist):
		s.raw = json.RawMessage(`{}`)
	case err != nil:
		return nil, err
	default:
		raw, perr := parseSettingsObject(b)
		if perr != nil {
			_ = os.Rename(s.path, s.path+".corrupt-"+s.now().Format("20060102-150405"))
			s.raw = json.RawMessage(`{}`)
		} else {
			s.raw = raw
		}
	}
	return s, nil
}

// Get returns a copy of the stored object (never nil).
func (s *SettingsStore) Get() json.RawMessage {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append(json.RawMessage(nil), s.raw...)
}

// Put replaces the stored object. body must be a JSON object within maxSettingsBytes.
func (s *SettingsStore) Put(body []byte) error {
	if len(body) > maxSettingsBytes {
		return errSettingsTooBig
	}
	raw, err := parseSettingsObject(body)
	if err != nil {
		return err
	}
	if len(raw) > maxSettingsBytes {
		return errSettingsTooBig
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	prev := s.raw
	s.raw = raw
	if err := writeFileAtomic(s.path, raw); err != nil {
		s.raw = prev
		return err
	}
	return nil
}

// parseSettingsObject accepts one JSON object and returns it re-encoded.
// null, arrays, and scalars are rejected. An empty object is valid.
func parseSettingsObject(body []byte) (json.RawMessage, error) {
	var v map[string]json.RawMessage
	if err := json.Unmarshal(body, &v); err != nil || v == nil {
		return nil, errSettingsJSON
	}
	raw, err := json.Marshal(v)
	if err != nil {
		return nil, err
	}
	return raw, nil
}
