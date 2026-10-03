package db

// The signed-in Google account's profile photo (see app.AccountPhoto),
// cached server-side so the browser never needs Google cookies.

import (
	"database/sql"
	"errors"
	"sync"
)

var accountPhotoOnce sync.Map // *Store -> *sync.Once

func (s *Store) ensureAccountPhotoTable() {
	o, _ := accountPhotoOnce.LoadOrStore(s, &sync.Once{})
	o.(*sync.Once).Do(func() {
		_, _ = s.db.Exec(`CREATE TABLE IF NOT EXISTS account_photo (
			id INTEGER PRIMARY KEY CHECK (id = 1),
			image BLOB,
			mime_type TEXT NOT NULL DEFAULT '',
			image_hash TEXT NOT NULL DEFAULT '',
			fetched_at_ms INTEGER NOT NULL DEFAULT 0,
			checked_at_ms INTEGER NOT NULL DEFAULT 0)`)
	})
}

type AccountPhoto struct {
	Image       []byte
	MimeType    string
	Hash        string
	FetchedAtMS int64
	CheckedAtMS int64
}

// GetAccountPhoto returns the cached photo (nil: never checked).
func (s *Store) GetAccountPhoto() (*AccountPhoto, error) {
	s.ensureAccountPhotoTable()
	p := &AccountPhoto{}
	err := s.db.QueryRow(`SELECT image, mime_type, image_hash, fetched_at_ms, checked_at_ms FROM account_photo WHERE id = 1`).
		Scan(&p.Image, &p.MimeType, &p.Hash, &p.FetchedAtMS, &p.CheckedAtMS)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return p, nil
}

// SaveAccountPhoto stores a freshly fetched photo.
func (s *Store) SaveAccountPhoto(image []byte, mimeType, hash string, nowMS int64) error {
	s.ensureAccountPhotoTable()
	_, err := s.db.Exec(`INSERT INTO account_photo (id, image, mime_type, image_hash, fetched_at_ms, checked_at_ms) VALUES (1, ?, ?, ?, ?, ?)
		ON CONFLICT(id) DO UPDATE SET image=excluded.image, mime_type=excluded.mime_type, image_hash=excluded.image_hash,
		fetched_at_ms=excluded.fetched_at_ms, checked_at_ms=excluded.checked_at_ms`, image, mimeType, hash, nowMS, nowMS)
	return err
}

// MarkAccountPhotoChecked records a failed / empty check (keeps any photo).
func (s *Store) MarkAccountPhotoChecked(nowMS int64) error {
	s.ensureAccountPhotoTable()
	_, err := s.db.Exec(`INSERT INTO account_photo (id, checked_at_ms) VALUES (1, ?)
		ON CONFLICT(id) DO UPDATE SET checked_at_ms=excluded.checked_at_ms`, nowMS)
	return err
}
