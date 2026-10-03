package client

import (
	"sync"
	"time"
)

var selfAvatar struct {
	sync.Mutex
	at time.Time
}

// SelfAvatarDue reports whether to queue your own participant (is_me) for
// the Google avatar cache now: at most once an hour, so the list header can
// show the account's photo without flooding the avatar queue (every
// conversation snapshot lists you).
func SelfAvatarDue() bool {
	selfAvatar.Lock()
	defer selfAvatar.Unlock()
	if time.Since(selfAvatar.at) < time.Hour {
		return false
	}
	selfAvatar.at = time.Now()
	return true
}
