package tesla

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"image"
	"image/draw"
	"image/jpeg"
	"io"
	"net/http"

	_ "image/gif" // decoders for uploads
	_ "image/png"

	xdraw "golang.org/x/image/draw"
	_ "golang.org/x/image/webp"
)

// Custom chat backgrounds: an uploaded photo is never stored as sent. It is
// type-checked by content (not by the declared type or file name), size- and
// dimension-capped, decoded, rotated upright per its EXIF orientation,
// downscaled, and re-encoded as a fresh JPEG, which drops EXIF/GPS and any
// other metadata or trailing data.

const (
	// MaxBackgroundUploadBytes caps the raw upload.
	MaxBackgroundUploadBytes = 15 << 20
	// MaxBackgroundSourcePixels guards against decompression bombs (a tiny
	// file that claims enormous dimensions).
	MaxBackgroundSourcePixels = 64_000_000
	// MaxBackgroundEdge is the longest edge of the stored image.
	MaxBackgroundEdge     = 1920
	backgroundJPEGQuality = 82
)

var (
	ErrBackgroundTooLarge    = errors.New("image is too large (limit 15 MB)")
	ErrBackgroundType        = errors.New("only JPEG, PNG, GIF or WebP images can be used")
	ErrBackgroundDimensions  = errors.New("image dimensions are too large")
	ErrBackgroundUndecodable = errors.New("couldn't read that image")
)

var allowedBackgroundTypes = map[string]bool{
	"image/jpeg": true, "image/png": true, "image/gif": true, "image/webp": true,
}

// ProcessBackgroundUpload validates and re-encodes an uploaded background.
// It returns JPEG bytes and the final dimensions.
func ProcessBackgroundUpload(r io.Reader) ([]byte, int, int, error) {
	raw, err := io.ReadAll(io.LimitReader(r, MaxBackgroundUploadBytes+1))
	if err != nil {
		return nil, 0, 0, fmt.Errorf("read upload: %w", err)
	}
	if len(raw) > MaxBackgroundUploadBytes {
		return nil, 0, 0, ErrBackgroundTooLarge
	}
	if len(raw) == 0 || !allowedBackgroundTypes[http.DetectContentType(raw)] {
		return nil, 0, 0, ErrBackgroundType
	}
	cfg, format, err := image.DecodeConfig(bytes.NewReader(raw))
	if err != nil {
		return nil, 0, 0, ErrBackgroundUndecodable
	}
	if !allowedBackgroundTypes["image/"+format] {
		return nil, 0, 0, ErrBackgroundType
	}
	if cfg.Width <= 0 || cfg.Height <= 0 || int64(cfg.Width)*int64(cfg.Height) > MaxBackgroundSourcePixels {
		return nil, 0, 0, ErrBackgroundDimensions
	}
	src, _, err := image.Decode(bytes.NewReader(raw))
	if err != nil {
		return nil, 0, 0, ErrBackgroundUndecodable
	}
	var dst image.Image = downscale(src, MaxBackgroundEdge)
	if format == "jpeg" {
		// Rotating after the downscale is equivalent and much cheaper.
		dst = applyOrientation(dst, jpegOrientation(raw))
	}
	var out bytes.Buffer
	if err := jpeg.Encode(&out, dst, &jpeg.Options{Quality: backgroundJPEGQuality}); err != nil {
		return nil, 0, 0, fmt.Errorf("encode background: %w", err)
	}
	b := dst.Bounds()
	return out.Bytes(), b.Dx(), b.Dy(), nil
}

// downscale returns an opaque RGBA copy of src whose longest edge is at most
// maxEdge (never upscales). Transparent areas are flattened onto black.
func downscale(src image.Image, maxEdge int) *image.RGBA {
	b := src.Bounds()
	w, h := b.Dx(), b.Dy()
	if w > maxEdge || h > maxEdge {
		if w >= h {
			h = max(1, h*maxEdge/w)
			w = maxEdge
		} else {
			w = max(1, w*maxEdge/h)
			h = maxEdge
		}
	}
	dst := image.NewRGBA(image.Rect(0, 0, w, h))
	draw.Draw(dst, dst.Bounds(), image.Black, image.Point{}, draw.Src)
	xdraw.CatmullRom.Scale(dst, dst.Bounds(), src, b, xdraw.Over, nil)
	return dst
}

// jpegOrientation returns the EXIF Orientation (1-8) of a JPEG, or 1.
func jpegOrientation(data []byte) int {
	if len(data) < 4 || data[0] != 0xFF || data[1] != 0xD8 {
		return 1
	}
	i := 2
	for i+4 <= len(data) {
		if data[i] != 0xFF {
			return 1
		}
		marker := data[i+1]
		if marker == 0xD9 || marker == 0xDA { // EOI / start of scan: no more metadata
			return 1
		}
		size := int(binary.BigEndian.Uint16(data[i+2 : i+4]))
		if size < 2 || i+2+size > len(data) {
			return 1
		}
		seg := data[i+4 : i+2+size]
		if marker == 0xE1 && len(seg) > 14 && string(seg[:6]) == "Exif\x00\x00" {
			return tiffOrientation(seg[6:])
		}
		i += 2 + size
	}
	return 1
}

func tiffOrientation(t []byte) int {
	if len(t) < 8 {
		return 1
	}
	var bo binary.ByteOrder
	switch string(t[:2]) {
	case "II":
		bo = binary.LittleEndian
	case "MM":
		bo = binary.BigEndian
	default:
		return 1
	}
	off := int(bo.Uint32(t[4:8]))
	if off < 8 || off+2 > len(t) {
		return 1
	}
	n := int(bo.Uint16(t[off : off+2]))
	for k := 0; k < n; k++ {
		e := off + 2 + k*12
		if e+12 > len(t) {
			return 1
		}
		if bo.Uint16(t[e:e+2]) == 0x0112 {
			v := int(bo.Uint16(t[e+8 : e+10]))
			if v >= 1 && v <= 8 {
				return v
			}
			return 1
		}
	}
	return 1
}

// applyOrientation rotates/flips img so it displays upright for EXIF
// orientation o (1 = as stored).
func applyOrientation(img image.Image, o int) image.Image {
	if o <= 1 || o > 8 {
		return img
	}
	b := img.Bounds()
	w, h := b.Dx(), b.Dy()
	dw, dh := w, h
	if o >= 5 {
		dw, dh = h, w
	}
	dst := image.NewRGBA(image.Rect(0, 0, dw, dh))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			var dx, dy int
			switch o {
			case 2:
				dx, dy = w-1-x, y
			case 3:
				dx, dy = w-1-x, h-1-y
			case 4:
				dx, dy = x, h-1-y
			case 5:
				dx, dy = y, x
			case 6:
				dx, dy = h-1-y, x
			case 7:
				dx, dy = h-1-y, w-1-x
			case 8:
				dx, dy = y, w-1-x
			}
			dst.Set(dx, dy, img.At(b.Min.X+x, b.Min.Y+y))
		}
	}
	return dst
}
