package webapp

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"hash/crc32"
	"image"
	"image/color"
	"image/gif"
	"image/jpeg"
	"image/png"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// ---------- upload validation / re-encoding ----------

func solidImage(w, h int, c color.Color) *image.RGBA {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, c)
		}
	}
	return img
}

func encodePNG(t *testing.T, img image.Image) []byte {
	t.Helper()
	var b bytes.Buffer
	if err := png.Encode(&b, img); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}

func encodeJPEG(t *testing.T, img image.Image) []byte {
	t.Helper()
	var b bytes.Buffer
	if err := jpeg.Encode(&b, img, &jpeg.Options{Quality: 90}); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}

// withExif inserts an APP1 Exif segment (orientation + a fake GPS-ish ASCII
// tag) right after SOI.
func withExif(t *testing.T, jpg []byte, orientation uint16) []byte {
	t.Helper()
	var tiff bytes.Buffer
	tiff.WriteString("II*\x00")
	_ = binary.Write(&tiff, binary.LittleEndian, uint32(8))
	_ = binary.Write(&tiff, binary.LittleEndian, uint16(2)) // 2 entries
	// Orientation, SHORT, count 1
	_ = binary.Write(&tiff, binary.LittleEndian, []uint16{0x0112, 3})
	_ = binary.Write(&tiff, binary.LittleEndian, uint32(1))
	_ = binary.Write(&tiff, binary.LittleEndian, []uint16{orientation, 0})
	// ImageDescription, ASCII, count 4, inline "GPS"
	_ = binary.Write(&tiff, binary.LittleEndian, []uint16{0x010E, 2})
	_ = binary.Write(&tiff, binary.LittleEndian, uint32(4))
	tiff.WriteString("GPS\x00")
	_ = binary.Write(&tiff, binary.LittleEndian, uint32(0))
	payload := append([]byte("Exif\x00\x00"), tiff.Bytes()...)
	seg := []byte{0xFF, 0xE1, 0, 0}
	binary.BigEndian.PutUint16(seg[2:], uint16(len(payload)+2))
	out := append([]byte{}, jpg[:2]...)
	out = append(out, seg...)
	out = append(out, payload...)
	return append(out, jpg[2:]...)
}

func TestProcessBackgroundRejectsOversize(t *testing.T) {
	big := make([]byte, MaxBackgroundUploadBytes+10)
	copy(big, encodePNG(t, solidImage(4, 4, color.White)))
	if _, _, _, err := ProcessBackgroundUpload(bytes.NewReader(big)); !errors.Is(err, ErrBackgroundTooLarge) {
		t.Fatalf("err = %v", err)
	}
}

func TestProcessBackgroundRejectsNonImages(t *testing.T) {
	for name, body := range map[string][]byte{
		"empty": {},
		"text":  []byte("hello, this is not an image"),
		"svg":   []byte(`<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>`),
		"html":  []byte(`<!doctype html><img src=x onerror=alert(1)>`),
		"pdf":   []byte("%PDF-1.4\n%...."),
		"bmp":   append([]byte("BM"), make([]byte, 64)...),
	} {
		if _, _, _, err := ProcessBackgroundUpload(bytes.NewReader(body)); !errors.Is(err, ErrBackgroundType) {
			t.Errorf("%s: err = %v, want type error", name, err)
		}
	}
	// Truncated/corrupt PNG passes sniffing but must fail decoding.
	p := encodePNG(t, solidImage(50, 50, color.White))
	if _, _, _, err := ProcessBackgroundUpload(bytes.NewReader(p[:40])); !errors.Is(err, ErrBackgroundUndecodable) {
		t.Errorf("truncated png: err = %v", err)
	}
}

// A 33-byte PNG header claiming 100000x100000 pixels must be refused before
// any pixel buffer is allocated.
func TestProcessBackgroundRejectsDecompressionBomb(t *testing.T) {
	var b bytes.Buffer
	b.WriteString("\x89PNG\r\n\x1a\n")
	ihdr := make([]byte, 13)
	binary.BigEndian.PutUint32(ihdr[0:], 100000)
	binary.BigEndian.PutUint32(ihdr[4:], 100000)
	ihdr[8], ihdr[9] = 8, 2 // 8-bit RGB
	_ = binary.Write(&b, binary.BigEndian, uint32(len(ihdr)))
	chunk := append([]byte("IHDR"), ihdr...)
	b.Write(chunk)
	_ = binary.Write(&b, binary.BigEndian, crc32.ChecksumIEEE(chunk))
	if _, _, _, err := ProcessBackgroundUpload(&b); !errors.Is(err, ErrBackgroundDimensions) {
		t.Fatalf("err = %v", err)
	}
}

func TestProcessBackgroundDownscalesAndReencodesJPEG(t *testing.T) {
	src := encodePNG(t, solidImage(3000, 1500, color.RGBA{200, 30, 30, 255}))
	out, w, h, err := ProcessBackgroundUpload(bytes.NewReader(src))
	if err != nil {
		t.Fatal(err)
	}
	if w != MaxBackgroundEdge || h != MaxBackgroundEdge/2 {
		t.Fatalf("size %dx%d", w, h)
	}
	cfg, format, err := image.DecodeConfig(bytes.NewReader(out))
	if err != nil || format != "jpeg" || cfg.Width != w || cfg.Height != h {
		t.Fatalf("output %v %s %+v", err, format, cfg)
	}
	// Small images are not upscaled.
	out, w, h, err = ProcessBackgroundUpload(bytes.NewReader(encodePNG(t, solidImage(300, 500, color.White))))
	if err != nil || w != 300 || h != 500 || len(out) == 0 {
		t.Fatalf("small: %v %dx%d", err, w, h)
	}
}

func TestProcessBackgroundAppliesOrientationAndStripsExif(t *testing.T) {
	// 40x20 landscape: left half red, right half blue. Orientation 6 means
	// "rotate 90° clockwise to display", so the result is 20x40 with red on top.
	img := image.NewRGBA(image.Rect(0, 0, 40, 20))
	for y := 0; y < 20; y++ {
		for x := 0; x < 40; x++ {
			if x < 20 {
				img.Set(x, y, color.RGBA{255, 0, 0, 255})
			} else {
				img.Set(x, y, color.RGBA{0, 0, 255, 255})
			}
		}
	}
	src := withExif(t, encodeJPEG(t, img), 6)
	if got := jpegOrientation(src); got != 6 {
		t.Fatalf("parsed orientation %d", got)
	}
	out, w, h, err := ProcessBackgroundUpload(bytes.NewReader(src))
	if err != nil {
		t.Fatal(err)
	}
	if w != 20 || h != 40 {
		t.Fatalf("orientation not applied: %dx%d", w, h)
	}
	dec, err := jpeg.Decode(bytes.NewReader(out))
	if err != nil {
		t.Fatal(err)
	}
	top, bottom := dec.At(10, 5), dec.At(10, 35)
	if r, _, b, _ := top.RGBA(); r < b {
		t.Errorf("top should be red, got %v", top)
	}
	if r, _, b, _ := bottom.RGBA(); b < r {
		t.Errorf("bottom should be blue, got %v", bottom)
	}
	if bytes.Contains(out, []byte("Exif")) || bytes.Contains(out, []byte("GPS")) {
		t.Fatal("EXIF survived re-encoding")
	}
	if jpegOrientation(out) != 1 {
		t.Fatal("output still carries an orientation")
	}
}

func TestProcessBackgroundAcceptsGIFAndWebP(t *testing.T) {
	var g bytes.Buffer
	pal := image.NewPaletted(image.Rect(0, 0, 30, 20), []color.Color{color.Black, color.White})
	if err := gif.Encode(&g, pal, nil); err != nil {
		t.Fatal(err)
	}
	if _, w, h, err := ProcessBackgroundUpload(&g); err != nil || w != 30 || h != 20 {
		t.Fatalf("gif: %v %dx%d", err, w, h)
	}
	// A real lossless 1x1 WebP.
	webp := []byte{0x52, 0x49, 0x46, 0x46, 0x1a, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x4c,
		0x0d, 0x00, 0x00, 0x00, 0x2f, 0x00, 0x00, 0x00, 0x10, 0x07, 0x10, 0x11, 0x11, 0x88, 0x88, 0xfe, 0x07, 0x00}
	if _, w, h, err := ProcessBackgroundUpload(bytes.NewReader(webp)); err != nil || w != 1 || h != 1 {
		t.Fatalf("webp: %v %dx%d", err, w, h)
	}
}

// ---------- theme store ----------

func testThemeStore(t *testing.T, dir string) *ThemeStore {
	t.Helper()
	s, err := OpenThemeStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	s.wallpaperOK = func(id string) bool { return id == "space-moon" || id == "animals-owl" }
	return s
}

func TestThemeStoreSetGetPersist(t *testing.T) {
	dir := t.TempDir()
	s := testThemeStore(t, dir)
	if _, ok := s.Get("c1"); ok {
		t.Fatal("unexpected theme")
	}
	got, err := s.Set("c1", ChatTheme{Palette: "sage", Wallpaper: "space-moon"}, false)
	if err != nil || got.Palette != "sage" || got.Wallpaper != "space-moon" || got.UpdatedMS == 0 {
		t.Fatalf("set: %v %+v", err, got)
	}
	if _, err := s.Set("c2", ChatTheme{Color: "#12ab9F"}, false); err != nil {
		t.Fatal(err)
	}
	fi, err := os.Stat(filepath.Join(dir, "chat-themes", "themes.json"))
	if err != nil || fi.Mode().Perm() != 0o600 {
		t.Fatalf("themes file: %v %v", err, fi.Mode())
	}
	s2 := testThemeStore(t, dir)
	all := s2.All()
	if len(all) != 2 || all["c1"].Wallpaper != "space-moon" || all["c2"].Color != "#12ab9F" {
		t.Fatalf("reloaded: %+v", all)
	}
	// Palette replaces a legacy color; empty theme removes the entry.
	if got, _ := s2.Set("c2", ChatTheme{Palette: "rose", Color: "#000000"}, false); got.Color != "" {
		t.Fatalf("legacy color kept: %+v", got)
	}
	if _, err := s2.Set("c2", ChatTheme{}, false); err != nil {
		t.Fatal(err)
	}
	if _, ok := s2.Get("c2"); ok {
		t.Fatal("empty theme should be removed")
	}
	if err := s2.Delete("c1"); err != nil {
		t.Fatal(err)
	}
	if len(testThemeStore(t, dir).All()) != 0 {
		t.Fatal("delete not persisted")
	}
}

func TestThemeStoreValidation(t *testing.T) {
	s := testThemeStore(t, t.TempDir())
	cases := []struct {
		id  string
		th  ChatTheme
		err error
	}{
		{"", ChatTheme{Palette: "blue"}, ErrThemeConversation},
		{strings.Repeat("x", maxConversationIDLen+1), ChatTheme{Palette: "blue"}, ErrThemeConversation},
		{"c", ChatTheme{Palette: "neon"}, ErrThemePalette},
		{"c", ChatTheme{Color: "red"}, ErrThemeColor},
		{"c", ChatTheme{Color: "#12345"}, ErrThemeColor},
		{"c", ChatTheme{Color: "#1234567"}, ErrThemeColor},
		{"c", ChatTheme{Wallpaper: "../../etc/passwd"}, ErrThemeWallpaper},
	}
	for _, c := range cases {
		if _, err := s.Set(c.id, c.th, false); !errors.Is(err, c.err) {
			t.Errorf("Set(%.20q, %+v) = %v, want %v", c.id, c.th, err, c.err)
		}
	}
	// Custom is server-controlled: a client can't invent one.
	got, err := s.Set("c", ChatTheme{Palette: "blue", Custom: "abc"}, true)
	if err != nil || got.Custom != "" {
		t.Fatalf("custom injected: %v %+v", err, got)
	}
}

func TestThemeStoreBackgroundLifecycle(t *testing.T) {
	dir := t.TempDir()
	s := testThemeStore(t, dir)
	conv := "../../weird/conv id"
	if _, _, err := s.Background(conv); !errors.Is(err, ErrNoBackground) {
		t.Fatalf("no bg: %v", err)
	}
	if _, err := s.Set(conv, ChatTheme{Palette: "gold", Wallpaper: "animals-owl"}, false); err != nil {
		t.Fatal(err)
	}
	th, err := s.SetBackground(conv, []byte("jpeg-bytes-1"))
	if err != nil || th.Custom == "" || th.Wallpaper != "" || th.Palette != "gold" {
		t.Fatalf("set bg: %v %+v", err, th)
	}
	p := s.BackgroundPath(conv)
	if filepath.Dir(p) != filepath.Join(dir, "chat-themes", "backgrounds") {
		t.Fatalf("background escaped its dir: %s", p)
	}
	if fi, err := os.Stat(p); err != nil || fi.Mode().Perm() != 0o600 {
		t.Fatalf("bg file: %v", err)
	}
	b, v, err := s.Background(conv)
	if err != nil || string(b) != "jpeg-bytes-1" || v != th.Custom {
		t.Fatalf("get bg: %v %q %q", err, b, v)
	}
	// Changing the palette while keeping the photo.
	th2, err := s.Set(conv, ChatTheme{Palette: "slate"}, true)
	if err != nil || th2.Custom != th.Custom {
		t.Fatalf("keep custom: %v %+v", err, th2)
	}
	// A new upload gets a new version.
	th3, _ := s.SetBackground(conv, []byte("jpeg-bytes-2"))
	if th3.Custom == th.Custom {
		t.Fatal("version should change with content")
	}
	// Switching to a built-in wallpaper removes the photo.
	if _, err := s.Set(conv, ChatTheme{Palette: "slate", Wallpaper: "space-moon"}, true); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(p); !os.IsNotExist(err) {
		t.Fatal("custom photo should be deleted when switching to a wallpaper")
	}
	// DeleteBackground keeps the palette; Delete removes everything.
	_, _ = s.SetBackground(conv, []byte("x"))
	th4, err := s.DeleteBackground(conv)
	if err != nil || th4.Custom != "" || th4.Palette != "slate" {
		t.Fatalf("delete bg: %v %+v", err, th4)
	}
	_, _ = s.SetBackground(conv, []byte("y"))
	if err := s.Delete(conv); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(p); !os.IsNotExist(err) {
		t.Fatal("reset should delete the photo")
	}
	if _, ok := s.Get(conv); ok {
		t.Fatal("reset should remove the theme")
	}
}

func TestThemeStoreSurvivesCorruptFile(t *testing.T) {
	dir := t.TempDir()
	_ = os.MkdirAll(filepath.Join(dir, "chat-themes"), 0o700)
	_ = os.WriteFile(filepath.Join(dir, "chat-themes", "themes.json"), []byte("{nope"), 0o600)
	s := testThemeStore(t, dir)
	if len(s.All()) != 0 {
		t.Fatal("expected empty store")
	}
	if _, err := s.Set("c", ChatTheme{Palette: "blue"}, false); err != nil {
		t.Fatal(err)
	}
}

// ---------- HTTP ----------

func TestThemeEndpointsRequireLogin(t *testing.T) {
	h, _, inner := newTestServer(t, nil)
	for _, p := range []string{"/api/app/wallpapers", "/api/app/themes", "/api/app/theme?conversation_id=c",
		"/api/app/theme/background?conversation_id=c", "/wallpapers/CREDITS.md"} {
		rr := authedReq(t, h, &http.Cookie{Name: SessionCookieName, Value: "bogus"}, http.MethodGet, p, "", nil)
		gated := rr.Code == http.StatusUnauthorized ||
			(rr.Code == http.StatusFound && strings.HasPrefix(rr.Header().Get("Location"), "/login"))
		if !gated || strings.Contains(rr.Body.String(), "Credits") {
			t.Errorf("%s: %d", p, rr.Code)
		}
	}
	if inner.hits.Load() != 0 {
		t.Fatal("reached inner handler")
	}
}

func uploadBody(t *testing.T, conv string, file []byte) (string, *bytes.Buffer) {
	t.Helper()
	var b bytes.Buffer
	mw := multipart.NewWriter(&b)
	_ = mw.WriteField("conversation_id", conv)
	fw, _ := mw.CreateFormFile("file", "photo.jpg")
	_, _ = fw.Write(file)
	_ = mw.Close()
	return mw.FormDataContentType(), &b
}

func TestThemeHTTPFlow(t *testing.T) {
	h, s, _ := newTestServer(t, nil)
	s.themes.wallpaperOK = func(id string) bool { return id == "space-moon" }
	c := login(t, h)

	rr := authedReq(t, h, c, http.MethodPut, "/api/app/theme", "application/json",
		strings.NewReader(`{"conversation_id":"conv-9","theme":{"palette":"lilac","wallpaper":"space-moon"}}`))
	if rr.Code != 200 {
		t.Fatalf("put: %d %s", rr.Code, rr.Body.String())
	}
	rr = authedReq(t, h, c, http.MethodPut, "/api/app/theme", "application/json",
		strings.NewReader(`{"conversation_id":"conv-9","theme":{"palette":"hot-pink"}}`))
	if rr.Code != 400 {
		t.Fatalf("bad palette: %d", rr.Code)
	}
	rr = authedReq(t, h, c, http.MethodGet, "/api/app/themes", "", nil)
	var all struct {
		Themes   map[string]ChatTheme `json:"themes"`
		Palettes []string             `json:"palettes"`
	}
	_ = json.Unmarshal(rr.Body.Bytes(), &all)
	if all.Themes["conv-9"].Palette != "lilac" || len(all.Palettes) != 9 {
		t.Fatalf("themes: %s", rr.Body.String())
	}

	// Upload: non-image rejected, image accepted and served back as JPEG.
	ct, body := uploadBody(t, "conv-9", []byte("<svg></svg>"))
	if rr = authedReq(t, h, c, http.MethodPost, "/api/app/theme/background", ct, body); rr.Code != 415 {
		t.Fatalf("svg upload: %d %s", rr.Code, rr.Body.String())
	}
	ct, body = uploadBody(t, "conv-9", encodePNG(t, solidImage(64, 96, color.RGBA{0, 128, 0, 255})))
	rr = authedReq(t, h, c, http.MethodPost, "/api/app/theme/background", ct, body)
	if rr.Code != 200 {
		t.Fatalf("upload: %d %s", rr.Code, rr.Body.String())
	}
	var up struct{ Theme ChatTheme }
	_ = json.Unmarshal(rr.Body.Bytes(), &up)
	if up.Theme.Custom == "" || up.Theme.Wallpaper != "" || up.Theme.Palette != "lilac" {
		t.Fatalf("upload theme: %+v", up.Theme)
	}
	rr = authedReq(t, h, c, http.MethodGet, "/api/app/theme/background?conversation_id=conv-9&v="+up.Theme.Custom, "", nil)
	if rr.Code != 200 || rr.Header().Get("Content-Type") != "image/jpeg" ||
		!strings.Contains(rr.Header().Get("Cache-Control"), "immutable") {
		t.Fatalf("fetch bg: %d %v", rr.Code, rr.Header())
	}
	if _, f, err := image.DecodeConfig(bytes.NewReader(rr.Body.Bytes())); err != nil || f != "jpeg" {
		t.Fatalf("served bg not jpeg: %v %s", err, f)
	}
	rr = authedReq(t, h, c, http.MethodGet, "/api/app/theme/background?conversation_id=conv-9&v=old", "", nil)
	if strings.Contains(rr.Header().Get("Cache-Control"), "immutable") {
		t.Fatal("stale version must not be cached as immutable")
	}
	// Oversize upload.
	big := make([]byte, MaxBackgroundUploadBytes+1024)
	copy(big, encodePNG(t, solidImage(4, 4, color.White)))
	ct, body = uploadBody(t, "conv-9", big)
	if rr = authedReq(t, h, c, http.MethodPost, "/api/app/theme/background", ct, body); rr.Code != 413 {
		t.Fatalf("oversize: %d %s", rr.Code, rr.Body.String())
	}
	// Missing conversation id.
	ct, body = uploadBody(t, "", encodePNG(t, solidImage(4, 4, color.White)))
	if rr = authedReq(t, h, c, http.MethodPost, "/api/app/theme/background", ct, body); rr.Code != 400 {
		t.Fatalf("no conv: %d", rr.Code)
	}
	// Delete background, then reset.
	if rr = authedReq(t, h, c, http.MethodDelete, "/api/app/theme/background?conversation_id=conv-9", "", nil); rr.Code != 200 {
		t.Fatalf("delete bg: %d", rr.Code)
	}
	if rr = authedReq(t, h, c, http.MethodGet, "/api/app/theme/background?conversation_id=conv-9", "", nil); rr.Code != 404 {
		t.Fatalf("bg after delete: %d", rr.Code)
	}
	if rr = authedReq(t, h, c, http.MethodDelete, "/api/app/theme?conversation_id=conv-9", "", nil); rr.Code != 200 {
		t.Fatalf("reset: %d", rr.Code)
	}
	if _, ok := s.themes.Get("conv-9"); ok {
		t.Fatal("reset didn't clear")
	}
}

func TestThemeWritesRejectCrossOrigin(t *testing.T) {
	h, _, _ := newTestServer(t, nil)
	c := login(t, h)
	req, _ := http.NewRequest(http.MethodPut, "http://car.example/api/app/theme", strings.NewReader(`{"conversation_id":"c","theme":{"palette":"blue"}}`))
	req.Header.Set("Origin", "https://evil.example")
	req.AddCookie(c)
	rr := newRecorder()
	h.ServeHTTP(rr, req)
	if rr.Code != http.StatusForbidden {
		t.Fatalf("cross-origin theme write: %d", rr.Code)
	}
}

func TestWallpaperManifestAndFiles(t *testing.T) {
	h, _, _ := newTestServer(t, nil)
	c := login(t, h)
	rr := authedReq(t, h, c, http.MethodGet, "/api/app/wallpapers", "", nil)
	if rr.Code != 200 {
		t.Fatalf("wallpapers: %d", rr.Code)
	}
	var out struct{ Categories []WallpaperCategory }
	if err := json.Unmarshal(rr.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	if rr = authedReq(t, h, c, http.MethodGet, "/wallpapers/../server.go", "", nil); rr.Code == 200 {
		t.Fatal("path traversal served a file")
	}
	if rr = authedReq(t, h, c, http.MethodGet, "/wallpapers/manifest.json", "", nil); rr.Code != 404 {
		t.Fatalf("non-image file served: %d", rr.Code)
	}
	for _, cat := range out.Categories {
		if cat.ID == "" || cat.Name == "" || len(cat.Items) == 0 {
			t.Fatalf("bad category %+v", cat)
		}
		for _, it := range cat.Items {
			if it.Author == "" || it.License == "" || it.Source == "" {
				t.Errorf("%s: missing attribution", it.ID)
			}
			if _, ok := WallpaperByID(it.ID); !ok {
				t.Errorf("%s not indexed", it.ID)
			}
			for _, u := range []string{it.FullURL, it.ThumbURL} {
				rr := authedReq(t, h, c, http.MethodGet, u, "", nil)
				if rr.Code != 200 || rr.Header().Get("Content-Type") != "image/webp" ||
					!strings.Contains(rr.Header().Get("Cache-Control"), "immutable") || rr.Body.Len() == 0 {
					t.Fatalf("%s: %d %v", u, rr.Code, rr.Header())
				}
			}
		}
	}
}

func newRecorder() *httptest.ResponseRecorder { return httptest.NewRecorder() }
