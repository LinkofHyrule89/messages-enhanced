package app

import (
	"net/http"
	"strings"
	"sync"
	"time"
)

// Per-conversation refresh ("Refresh" in a conversation's ⋮ menu): re-reads
// one Google Messages conversation from the phone without touching others.
const (
	convRefreshPerConversation = 30 * time.Second
	convRefreshGlobalWindow    = time.Minute
	convRefreshGlobalMax       = 6
	convRefreshMessageCount    = 50
	convRefreshMaxPhotos       = 20
	convRefreshPhotoBudget     = 40 * time.Second
)

type convRefreshLimiter struct {
	mu      sync.Mutex
	last    map[string]time.Time
	recent  []time.Time
	running bool
}

// reserve returns 0 when a refresh of id may start now (and records it), or
// the seconds to wait.
func (l *convRefreshLimiter) reserve(id string, now time.Time) int {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.running {
		return 2
	}
	if l.last == nil {
		l.last = map[string]time.Time{}
	}
	wait := time.Duration(0)
	if t, ok := l.last[id]; ok && now.Sub(t) < convRefreshPerConversation {
		wait = convRefreshPerConversation - now.Sub(t)
	}
	kept := l.recent[:0]
	for _, t := range l.recent {
		if now.Sub(t) < convRefreshGlobalWindow {
			kept = append(kept, t)
		}
	}
	l.recent = kept
	if len(l.recent) >= convRefreshGlobalMax {
		if w := convRefreshGlobalWindow - now.Sub(l.recent[0]); w > wait {
			wait = w
		}
	}
	if wait > 0 {
		return int(wait.Seconds()) + 1
	}
	l.last[id] = now
	l.recent = append(l.recent, now)
	l.running = true
	if len(l.last) > 1000 {
		for k, t := range l.last {
			if now.Sub(t) > convRefreshPerConversation {
				delete(l.last, k)
			}
		}
	}
	return 0
}

func (l *convRefreshLimiter) done() {
	l.mu.Lock()
	l.running = false
	l.mu.Unlock()
}

// ConversationRefreshResult reports what a per-conversation refresh did.
type ConversationRefreshResult struct {
	ConversationID string `json:"conversation_id"`
	Deleted        bool   `json:"deleted,omitempty"`
	Members        int    `json:"members"`
	Photos         int    `json:"photos"`
	Messages       int    `json:"messages"`
	AvatarVersion  int64  `json:"avatar_version"`
}

// RefreshConversation re-reads one conversation from Google Messages: its
// name, members and archive state (GetConversation), its group icon (plain
// URL or end-to-end encrypted, re-downloaded even if cached or recently
// failed), its members' photos, and its latest page of messages (so missing
// bubbles, reactions and statuses correct themselves). Rate limited per
// conversation and overall; retryAfter > 0 means "try again in that many
// seconds" (with ErrRefreshRateLimited).
func (a *App) RefreshConversation(conversationID string) (res *ConversationRefreshResult, retryAfter int, err error) {
	conversationID = strings.TrimSpace(conversationID)
	if a == nil || a.Store == nil || conversationID == "" {
		return nil, 0, carErr(http.StatusBadRequest, "conversation_id is required")
	}
	local, err := a.Store.GetConversation(conversationID)
	if err != nil || local == nil {
		return nil, 0, carErr(http.StatusNotFound, "conversation not found")
	}
	if !isGooglePlatform(local.SourcePlatform) {
		return nil, 0, carErr(http.StatusBadRequest, "only Google Messages conversations can be refreshed")
	}
	gm := a.getGMClient()
	if gm == nil {
		return nil, 0, carErr(http.StatusServiceUnavailable, carGoogleDisconnectedMsg)
	}
	if wait := a.convRefresh.reserve(conversationID, time.Now()); wait > 0 {
		return nil, wait, ErrRefreshRateLimited
	}
	defer a.convRefresh.done()
	log := a.Logger.With().Str("conv_id", conversationID).Logger()
	res = &ConversationRefreshResult{ConversationID: conversationID}

	conv, err := gm.GetConversation(conversationID)
	if err != nil {
		if a.HandleGoogleAuthExpiredError(err) {
			return nil, 0, carErr(http.StatusServiceUnavailable, "Google Messages sign-in expired")
		}
		log.Warn().Err(err).Msg("Conversation refresh: get conversation failed")
		return nil, 0, carErr(http.StatusBadGateway, "couldn't reach Google Messages, try again")
	}
	if conv == nil || conv.GetConversationID() == "" {
		return nil, 0, carErr(http.StatusBadGateway, "Google Messages returned no conversation")
	}
	cands, err := a.storeConversationSnapshot(conv, false)
	if err != nil {
		log.Warn().Err(err).Msg("Conversation refresh: store conversation failed")
		return nil, 0, carErr(http.StatusInternalServerError, "couldn't save the conversation")
	}
	if !a.Store.ConversationExists(conversationID) {
		res.Deleted = true // deleted on the phone
		a.emitConversationsChange()
		res.AvatarVersion = a.Store.AvatarVersion()
		return res, 0, nil
	}
	res.Members = len(conv.GetParticipants())

	// Group icon first, then members' photos, all forced.
	photosStart := time.Now()
	for i, c := range cands {
		if i >= convRefreshMaxPhotos || time.Since(photosStart) > convRefreshPhotoBudget {
			break
		}
		c.Force = true
		a.fetchGoogleAvatarCandidate(c)
		res.Photos++
	}

	msgResp, err := gm.FetchMessages(conversationID, convRefreshMessageCount, nil)
	if err != nil {
		if a.HandleGoogleAuthExpiredError(err) {
			return nil, 0, carErr(http.StatusServiceUnavailable, "Google Messages sign-in expired")
		}
		log.Warn().Err(err).Msg("Conversation refresh: fetch messages failed")
	} else {
		for _, m := range msgResp.GetMessages() {
			if m.GetConversationID() != "" && m.GetConversationID() != conversationID {
				continue
			}
			a.storeMessage(m)
			res.Messages++
		}
	}
	a.emitConversationsChange()
	a.emitMessagesChange(conversationID)
	res.AvatarVersion = a.Store.AvatarVersion()
	log.Info().Int("members", res.Members).Int("photos", res.Photos).Int("messages", res.Messages).Msg("Conversation refreshed from Google")
	return res, 0, nil
}
