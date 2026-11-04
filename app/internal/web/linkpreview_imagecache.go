package web

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"
	"sync"
	"time"
)

const previewImageMagic = "LPI1"

type fetchedPreviewImage struct {
	data        []byte
	contentType string
}

type previewImageEntry struct {
	data        []byte
	contentType string
	fetchedAt   time.Time
	expiresAt   time.Time
	touchedAt   time.Time
	failed      bool
	err         error
}

type diskPreviewImage struct {
	size        int64
	fetchedAt   time.Time
	expiresAt   time.Time
	contentType string
}

// previewImageCache is an in-memory LRU plus an on-disk cache of preview
// images. Keys are a hash of the normalized URL. Successful entries expire
// at the timestamp stored when they were fetched; failures stay in memory
// only, and only until their short deadline.
type previewImageCache struct {
	memCap  int64
	diskCap int64

	mu        sync.Mutex
	mem       map[string]*previewImageEntry
	memBytes  int64
	dir       string
	disk      map[string]diskPreviewImage
	diskBytes int64
}

func previewImageKey(normalizedURL string) string {
	sum := sha256.Sum256([]byte(normalizedURL))
	return hex.EncodeToString(sum[:])
}

func (c *previewImageCache) open(dataDir string, now time.Time) error {
	dir := filepath.Join(dataDir, linkPreviewImageDirName)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	c.mu.Lock()
	if c.dir != "" {
		c.mu.Unlock()
		return nil
	}
	c.dir = dir
	c.mu.Unlock()
	return c.load(now)
}

func (c *previewImageCache) load(now time.Time) error {
	c.mu.Lock()
	dir := c.dir
	c.mu.Unlock()
	if dir == "" {
		return nil
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return err
	}
	kept := make(map[string]diskPreviewImage, len(entries))
	var total int64
	for _, entry := range entries {
		name := entry.Name()
		path := filepath.Join(dir, name)
		if entry.IsDir() || !isPreviewImageKey(name) {
			if !entry.IsDir() {
				_ = os.Remove(path)
			}
			continue
		}
		meta, err := readPreviewImageMeta(path)
		if err != nil || now.After(meta.expiresAt) {
			_ = os.Remove(path)
			continue
		}
		kept[name] = meta
		total += meta.size
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.disk = kept
	c.diskBytes = total
	c.evictDiskLocked()
	return nil
}

// get reports a hit when the key is cached and not expired. A non-nil error
// is a negative cache hit (failure remembered for a short window).
func (c *previewImageCache) get(key string, now time.Time) ([]byte, string, error, bool) {
	c.mu.Lock()
	if entry, ok := c.mem[key]; ok {
		if now.After(entry.expiresAt) {
			c.dropMemLocked(key)
		} else {
			entry.touchedAt = now
			data, contentType, err, failed := entry.data, entry.contentType, entry.err, entry.failed
			c.mu.Unlock()
			if failed {
				if err == nil {
					err = ErrNoLinkPreview
				}
				return nil, "", err, true
			}
			return data, contentType, nil, true
		}
	}
	meta, onDisk := c.disk[key]
	dir := c.dir
	if onDisk && now.After(meta.expiresAt) {
		c.removeDiskLocked(key)
		onDisk = false
	}
	c.mu.Unlock()
	if !onDisk || dir == "" {
		return nil, "", nil, false
	}

	contentType, data, fetchedAt, expiresAt, err := readPreviewImageFile(filepath.Join(dir, key))
	if err != nil || now.After(expiresAt) {
		c.mu.Lock()
		c.removeDiskLocked(key)
		c.mu.Unlock()
		return nil, "", nil, false
	}
	c.mu.Lock()
	c.rememberLocked(key, &previewImageEntry{
		data:        data,
		contentType: contentType,
		fetchedAt:   fetchedAt,
		expiresAt:   expiresAt,
		touchedAt:   now,
	})
	c.mu.Unlock()
	return data, contentType, nil, true
}

func (c *previewImageCache) put(key, contentType string, data []byte, now time.Time, ttl time.Duration) error {
	if ttl <= 0 {
		ttl = linkPreviewSuccessTTL
	}
	entry := &previewImageEntry{
		data:        data,
		contentType: contentType,
		fetchedAt:   now,
		expiresAt:   now.Add(ttl),
		touchedAt:   now,
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.rememberLocked(key, entry)
	if c.dir == "" {
		return nil
	}
	path := filepath.Join(c.dir, key)
	if err := writePreviewImageFile(path, contentType, data, entry.fetchedAt, entry.expiresAt); err != nil {
		return err
	}
	info, err := os.Stat(path)
	if err != nil {
		return err
	}
	if old, ok := c.disk[key]; ok {
		c.diskBytes -= old.size
		if c.diskBytes < 0 {
			c.diskBytes = 0
		}
	}
	c.disk[key] = diskPreviewImage{
		size:        info.Size(),
		fetchedAt:   entry.fetchedAt,
		expiresAt:   entry.expiresAt,
		contentType: contentType,
	}
	c.diskBytes += info.Size()
	c.evictDiskLocked()
	return nil
}

func (c *previewImageCache) putFailure(key string, err error, now time.Time, ttl time.Duration) {
	if err == nil {
		return
	}
	if ttl <= 0 || ttl > linkPreviewFailureTTL {
		ttl = linkPreviewFailureTTL
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if existing, ok := c.mem[key]; ok && !existing.failed && !now.After(existing.expiresAt) {
		return
	}
	c.rememberLocked(key, &previewImageEntry{
		failed:    true,
		err:       err,
		fetchedAt: now,
		expiresAt: now.Add(ttl),
		touchedAt: now,
	})
}

func (c *previewImageCache) prune(now time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for key, entry := range c.mem {
		if now.After(entry.expiresAt) {
			c.dropMemLocked(key)
		}
	}
	for key, meta := range c.disk {
		if now.After(meta.expiresAt) {
			c.removeDiskLocked(key)
		}
	}
}

func (c *previewImageCache) rememberLocked(key string, entry *previewImageEntry) {
	if _, ok := c.mem[key]; ok {
		c.dropMemLocked(key)
	}
	c.mem[key] = entry
	if !entry.failed {
		c.memBytes += int64(len(entry.data))
	}
	c.evictMemLocked(key)
}

func (c *previewImageCache) dropMemLocked(key string) {
	entry, ok := c.mem[key]
	if !ok {
		return
	}
	if !entry.failed {
		c.memBytes -= int64(len(entry.data))
		if c.memBytes < 0 {
			c.memBytes = 0
		}
	}
	delete(c.mem, key)
}

func (c *previewImageCache) evictMemLocked(keep string) {
	for c.memCap > 0 && c.memBytes > c.memCap {
		oldestKey := ""
		var oldest time.Time
		for key, entry := range c.mem {
			if key == keep || entry.failed {
				continue
			}
			if oldestKey == "" || entry.touchedAt.Before(oldest) {
				oldestKey = key
				oldest = entry.touchedAt
			}
		}
		if oldestKey == "" {
			return
		}
		c.dropMemLocked(oldestKey)
	}
}

func (c *previewImageCache) evictDiskLocked() {
	for c.diskCap > 0 && c.diskBytes > c.diskCap && len(c.disk) > 0 {
		oldestKey := ""
		var oldest time.Time
		for key, meta := range c.disk {
			if oldestKey == "" || meta.fetchedAt.Before(oldest) {
				oldestKey = key
				oldest = meta.fetchedAt
			}
		}
		if oldestKey == "" {
			return
		}
		c.removeDiskLocked(oldestKey)
	}
}

func (c *previewImageCache) removeDiskLocked(key string) {
	if meta, ok := c.disk[key]; ok {
		c.diskBytes -= meta.size
		if c.diskBytes < 0 {
			c.diskBytes = 0
		}
		delete(c.disk, key)
	}
	if c.dir != "" {
		_ = os.Remove(filepath.Join(c.dir, key))
	}
}

func isPreviewImageKey(name string) bool {
	if len(name) != sha256.Size*2 {
		return false
	}
	for _, r := range name {
		switch {
		case r >= '0' && r <= '9':
		case r >= 'a' && r <= 'f':
		default:
			return false
		}
	}
	return true
}

func writePreviewImageFile(path, contentType string, data []byte, fetchedAt, expiresAt time.Time) error {
	if len(contentType) > 1024 {
		return errors.New("content type too long")
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), "tmp-*")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	success := false
	defer func() {
		if !success {
			_ = os.Remove(tmpName)
		}
	}()
	var hdr [6]byte
	copy(hdr[:4], previewImageMagic)
	binary.BigEndian.PutUint16(hdr[4:], uint16(len(contentType)))
	if _, err := tmp.Write(hdr[:]); err != nil {
		_ = tmp.Close()
		return err
	}
	if _, err := tmp.WriteString(contentType); err != nil {
		_ = tmp.Close()
		return err
	}
	var times [16]byte
	binary.BigEndian.PutUint64(times[0:8], uint64(fetchedAt.UnixNano()))
	binary.BigEndian.PutUint64(times[8:16], uint64(expiresAt.UnixNano()))
	if _, err := tmp.Write(times[:]); err != nil {
		_ = tmp.Close()
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmpName, path); err != nil {
		return err
	}
	success = true
	return nil
}

func readPreviewImageMeta(path string) (diskPreviewImage, error) {
	f, err := os.Open(path)
	if err != nil {
		return diskPreviewImage{}, err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return diskPreviewImage{}, err
	}
	contentType, fetchedAt, expiresAt, err := readPreviewImageHeader(f)
	if err != nil {
		return diskPreviewImage{}, err
	}
	return diskPreviewImage{
		size:        info.Size(),
		fetchedAt:   fetchedAt,
		expiresAt:   expiresAt,
		contentType: contentType,
	}, nil
}

func readPreviewImageFile(path string) (contentType string, data []byte, fetchedAt, expiresAt time.Time, err error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return "", nil, time.Time{}, time.Time{}, err
	}
	if len(b) < 6 || string(b[:4]) != previewImageMagic {
		return "", nil, time.Time{}, time.Time{}, errors.New("invalid preview image cache file")
	}
	n := int(binary.BigEndian.Uint16(b[4:6]))
	if n > 1024 || len(b) < 6+n+16 {
		return "", nil, time.Time{}, time.Time{}, errors.New("invalid preview image cache file")
	}
	contentType = string(b[6 : 6+n])
	ts := b[6+n : 6+n+16]
	fetchedAt = time.Unix(0, int64(binary.BigEndian.Uint64(ts[0:8])))
	expiresAt = time.Unix(0, int64(binary.BigEndian.Uint64(ts[8:16])))
	data = append([]byte(nil), b[6+n+16:]...)
	return contentType, data, fetchedAt, expiresAt, nil
}

func readPreviewImageHeader(r io.Reader) (contentType string, fetchedAt, expiresAt time.Time, err error) {
	var hdr [6]byte
	if _, err = io.ReadFull(r, hdr[:]); err != nil {
		return "", time.Time{}, time.Time{}, err
	}
	if string(hdr[:4]) != previewImageMagic {
		return "", time.Time{}, time.Time{}, errors.New("invalid preview image cache file")
	}
	n := int(binary.BigEndian.Uint16(hdr[4:]))
	if n > 1024 {
		return "", time.Time{}, time.Time{}, errors.New("invalid preview image cache file")
	}
	buf := make([]byte, n+16)
	if _, err = io.ReadFull(r, buf); err != nil {
		return "", time.Time{}, time.Time{}, err
	}
	contentType = string(buf[:n])
	fetchedAt = time.Unix(0, int64(binary.BigEndian.Uint64(buf[n:n+8])))
	expiresAt = time.Unix(0, int64(binary.BigEndian.Uint64(buf[n+8:n+16])))
	return contentType, fetchedAt, expiresAt, nil
}
