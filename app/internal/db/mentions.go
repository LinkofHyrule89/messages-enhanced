package db

import (
	"database/sql"
	"strings"
	"sync"
	"time"
	"unicode"
)

// Google Messages encodes an @mention as plain text wrapped in no-break
// spaces: "\u00A0@Full Name\u00A0" or "\u00A0@First\u00A0". There is no
// structured mention metadata, so mentions_me is derived from the text.

var ownerNameCache struct {
	sync.Mutex
	name string
	at   time.Time
}

// ownerNameTx returns the user's own display name, taken from the most
// common sender name on their own Google messages. Cached for 10 minutes.
func ownerNameTx(tx *sql.Tx) string {
	ownerNameCache.Lock()
	defer ownerNameCache.Unlock()
	if !ownerNameCache.at.IsZero() && time.Since(ownerNameCache.at) < 10*time.Minute {
		return ownerNameCache.name
	}
	var name string
	_ = tx.QueryRow(`SELECT sender_name FROM messages WHERE is_from_me=1 AND source_platform='sms'
		AND sender_name NOT IN ('', 'You', 'Me') GROUP BY sender_name ORDER BY COUNT(*) DESC LIMIT 1`).Scan(&name)
	ownerNameCache.name, ownerNameCache.at = strings.TrimSpace(name), time.Now()
	return ownerNameCache.name
}

// MentionsName reports whether body contains a Google-style mention of the
// full name or its first word (case-insensitive).
func MentionsName(body, fullName string) bool {
	fullName = strings.TrimSpace(fullName)
	if fullName == "" || !strings.Contains(body, "@") {
		return false
	}
	cands := []string{fullName}
	if f := strings.Fields(fullName); len(f) > 1 {
		cands = append(cands, f[0])
	}
	lower := strings.ToLower(body)
	for _, c := range cands {
		needle := "@" + strings.ToLower(c)
		for i := 0; ; {
			j := strings.Index(lower[i:], needle)
			if j < 0 {
				break
			}
			start, end := i+j, i+j+len(needle)
			i = end
			if start > 0 {
				prev := []rune(lower[:start])
				if p := prev[len(prev)-1]; !unicode.IsSpace(p) && !unicode.IsPunct(p) {
					continue
				}
			}
			if end == len(lower) {
				return true
			}
			next := []rune(lower[end:])[0]
			if unicode.IsSpace(next) || unicode.IsPunct(next) || next == '\u00a0' {
				return true
			}
		}
	}
	return false
}
