package webapp

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sync"
	"time"
)

// Per-conversation chat themes, stored on the server so the car, a tablet and
// a phone all show the same colors and wallpaper. Small JSON file plus one
// re-encoded JPEG per conversation that uses a custom photo.

// ThemePalettes are the two-tone color choices offered in the UI (the client
// holds the actual colors; the server only validates the id).
var ThemePalettes = []string{"blue", "periwinkle", "sky", "lilac", "sage", "coral", "rose", "gold", "slate"}

type ChatTheme struct {
	Palette   string `json:"palette,omitempty"`   // one of ThemePalettes
	Color     string `json:"color,omitempty"`     // legacy custom accent #rrggbb (from old localStorage themes)
	Wallpaper string `json:"wallpaper,omitempty"` // built-in wallpaper id
	Custom    string `json:"custom,omitempty"`    // version of the uploaded photo (set by the server)
	UpdatedMS int64  `json:"updated_ms"`
}

func (t ChatTheme) empty() bool {
	return t.Palette == "" && t.Color == "" && t.Wallpaper == "" && t.Custom == ""
}

var (
	ErrThemeConversation = errors.New("conversation_id is required")
	ErrThemePalette      = errors.New("unknown palette")
	ErrThemeColor        = errors.New("color must be #rrggbb")
	ErrThemeWallpaper    = errors.New("unknown wallpaper")
	ErrNoBackground      = errors.New("no custom background")

	hexColorRe = regexp.MustCompile(`^#[0-9a-fA-F]{6}$`)
)

const maxConversationIDLen = 256

type ThemeStore struct {
	mu     sync.Mutex
	dir    string
	themes map[string]ChatTheme
	now    func() time.Time
	// wallpaperOK validates built-in wallpaper ids (swappable in tests).
	wallpaperOK func(string) bool
}

// OpenThemeStore loads (or starts) the store under dataDir/chat-themes.
func OpenThemeStore(dataDir string) (*ThemeStore, error) {
	migrateLegacyFile(dataDir, legacyThemesDir, "chat-themes")
	dir := filepath.Join(dataDir, "chat-themes")
	if err := os.MkdirAll(filepath.Join(dir, "backgrounds"), 0o700); err != nil {
		return nil, fmt.Errorf("theme dir: %w", err)
	}
	s := &ThemeStore{dir: dir, themes: map[string]ChatTheme{}, now: time.Now,
		wallpaperOK: func(id string) bool { _, ok := WallpaperByID(id); return ok }}
	raw, err := os.ReadFile(s.file())
	switch {
	case errors.Is(err, os.ErrNotExist):
	case err != nil:
		return nil, fmt.Errorf("read themes: %w", err)
	default:
		if err := json.Unmarshal(raw, &s.themes); err != nil {
			// Don't take the whole server down over a damaged theme file:
			// keep it for inspection and start with default themes.
			_ = os.Rename(s.file(), s.file()+".corrupt-"+s.now().Format("20060102-150405"))
			s.themes = map[string]ChatTheme{}
		}
		if s.themes == nil {
			s.themes = map[string]ChatTheme{}
		}
	}
	return s, nil
}

func (s *ThemeStore) file() string { return filepath.Join(s.dir, "themes.json") }

// BackgroundPath is where a conversation's custom photo lives. The file name
// is a hash so arbitrary conversation ids never touch the path.
func (s *ThemeStore) BackgroundPath(convID string) string {
	sum := sha256.Sum256([]byte(convID))
	return filepath.Join(s.dir, "backgrounds", hex.EncodeToString(sum[:16])+".jpg")
}

func validConvID(id string) bool { return id != "" && len(id) <= maxConversationIDLen }

// All returns a copy of every stored theme.
func (s *ThemeStore) All() map[string]ChatTheme {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make(map[string]ChatTheme, len(s.themes))
	for k, v := range s.themes {
		out[k] = v
	}
	return out
}

func (s *ThemeStore) Get(convID string) (ChatTheme, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	t, ok := s.themes[convID]
	return t, ok
}

// Set validates and stores a theme. keepCustom keeps an already-uploaded
// photo; otherwise any custom photo is removed. Palette/Color/Wallpaper are
// taken from t; Custom is always server-controlled.
func (s *ThemeStore) Set(convID string, t ChatTheme, keepCustom bool) (ChatTheme, error) {
	if !validConvID(convID) {
		return ChatTheme{}, ErrThemeConversation
	}
	if t.Palette != "" && !validPalette(t.Palette) {
		return ChatTheme{}, ErrThemePalette
	}
	if t.Color != "" && !hexColorRe.MatchString(t.Color) {
		return ChatTheme{}, ErrThemeColor
	}
	if t.Wallpaper != "" && !s.wallpaperOK(t.Wallpaper) {
		return ChatTheme{}, ErrThemeWallpaper
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	prev, had := s.themes[convID]
	t.Custom = ""
	if keepCustom && t.Wallpaper == "" {
		t.Custom = prev.Custom
	}
	if t.Palette != "" {
		t.Color = "" // a palette replaces a legacy color
	}
	if t.empty() {
		delete(s.themes, convID)
	} else {
		t.UpdatedMS = s.now().UnixMilli()
		s.themes[convID] = t
	}
	if err := s.saveLocked(); err != nil {
		if had {
			s.themes[convID] = prev
		} else {
			delete(s.themes, convID)
		}
		return ChatTheme{}, err
	}
	if prev.Custom != "" && t.Custom == "" {
		_ = os.Remove(s.BackgroundPath(convID))
	}
	return t, nil
}

// Delete resets a conversation to the default theme (and removes its photo).
func (s *ThemeStore) Delete(convID string) error {
	if !validConvID(convID) {
		return ErrThemeConversation
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	prev, ok := s.themes[convID]
	if ok {
		delete(s.themes, convID)
		if err := s.saveLocked(); err != nil {
			s.themes[convID] = prev
			return err
		}
	}
	if err := os.Remove(s.BackgroundPath(convID)); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}

// SetBackground stores an already-processed JPEG as the conversation's custom
// photo (replacing any built-in wallpaper; the palette is kept).
func (s *ThemeStore) SetBackground(convID string, jpegData []byte) (ChatTheme, error) {
	if !validConvID(convID) {
		return ChatTheme{}, ErrThemeConversation
	}
	sum := sha256.Sum256(jpegData)
	version := hex.EncodeToString(sum[:6])
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := writeFileAtomic(s.BackgroundPath(convID), jpegData); err != nil {
		return ChatTheme{}, err
	}
	prev, had := s.themes[convID]
	t := prev
	t.Wallpaper = ""
	t.Custom = version
	t.UpdatedMS = s.now().UnixMilli()
	s.themes[convID] = t
	if err := s.saveLocked(); err != nil {
		if had {
			s.themes[convID] = prev
		} else {
			delete(s.themes, convID)
		}
		return ChatTheme{}, err
	}
	return t, nil
}

// Background returns the custom photo bytes and version.
func (s *ThemeStore) Background(convID string) ([]byte, string, error) {
	s.mu.Lock()
	t := s.themes[convID]
	s.mu.Unlock()
	if !validConvID(convID) || t.Custom == "" {
		return nil, "", ErrNoBackground
	}
	b, err := os.ReadFile(s.BackgroundPath(convID))
	if errors.Is(err, os.ErrNotExist) {
		return nil, "", ErrNoBackground
	}
	return b, t.Custom, err
}

// DeleteBackground removes only the custom photo (palette is kept).
func (s *ThemeStore) DeleteBackground(convID string) (ChatTheme, error) {
	if !validConvID(convID) {
		return ChatTheme{}, ErrThemeConversation
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	prev, ok := s.themes[convID]
	t := prev
	if ok && prev.Custom != "" {
		t.Custom = ""
		t.UpdatedMS = s.now().UnixMilli()
		if t.empty() {
			delete(s.themes, convID)
		} else {
			s.themes[convID] = t
		}
		if err := s.saveLocked(); err != nil {
			s.themes[convID] = prev
			return ChatTheme{}, err
		}
	}
	if err := os.Remove(s.BackgroundPath(convID)); err != nil && !errors.Is(err, os.ErrNotExist) {
		return ChatTheme{}, err
	}
	return t, nil
}

func validPalette(p string) bool {
	for _, x := range ThemePalettes {
		if x == p {
			return true
		}
	}
	return false
}

func (s *ThemeStore) saveLocked() error {
	raw, err := json.MarshalIndent(s.themes, "", " ")
	if err != nil {
		return err
	}
	return writeFileAtomic(s.file(), raw)
}

func writeFileAtomic(path string, data []byte) error {
	tmp, err := os.CreateTemp(filepath.Dir(path), ".tmp-*")
	if err != nil {
		return err
	}
	name := tmp.Name()
	defer os.Remove(name)
	if err := tmp.Chmod(0o600); err != nil {
		tmp.Close()
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(name, path)
}
