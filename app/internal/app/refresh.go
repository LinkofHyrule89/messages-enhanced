package app

import (
	"errors"
	"fmt"
	"sync"
	"time"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"

	"github.com/maxghenis/openmessage/internal/db"
)

// Background refresh of the conversation list from Google Messages: names,
// members, archive/spam/trash state and photos.
//
//   - "list": on page load (at most once a minute): Inbox, Archive and Spam
//     folders' first pages, photos queued only if their cache is due.
//   - "all": Settings > Refresh everything (at most once every 5 minutes):
//     the same plus every photo and group icon re-downloaded.
//   - "periodic": every 12 hours, like "list".
const (
	refreshListMinInterval = time.Minute
	refreshAllMinInterval  = 5 * time.Minute
	refreshPeriodicEvery   = 12 * time.Hour
	refreshInboxCount      = 100
	refreshFolderCount     = 50
	refreshStaleCheckMax   = 25
)

// ErrRefreshRateLimited is returned (wrapped) when a refresh was asked for
// too soon after the previous one.
var ErrRefreshRateLimited = errors.New("refresh rate limited")

type RefreshStatus struct {
	Running       bool   `json:"running"`
	Scope         string `json:"scope,omitempty"`
	Stage         string `json:"stage,omitempty"`
	Conversations int    `json:"conversations"`
	PhotosQueued  int    `json:"photos_queued"`
	PhotosLeft    int64  `json:"photos_left"`
	StartedAtMS   int64  `json:"started_at_ms,omitempty"`
	FinishedAtMS  int64  `json:"finished_at_ms,omitempty"`
	Error         string `json:"error,omitempty"`
	AvatarVersion int64  `json:"avatar_version"`
	RetryAfterSec int    `json:"retry_after_sec,omitempty"`
}

type googleRefreshState struct {
	mu       sync.Mutex
	status   RefreshStatus
	lastList time.Time
	lastAll  time.Time
	stop     chan struct{}
	stopOnce sync.Once
}

func (a *App) refreshStopCh() chan struct{} {
	a.refresh.mu.Lock()
	defer a.refresh.mu.Unlock()
	if a.refresh.stop == nil {
		a.refresh.stop = make(chan struct{})
	}
	return a.refresh.stop
}

// GoogleRefreshStatus reports the current or last refresh.
func (a *App) GoogleRefreshStatus() RefreshStatus {
	a.refresh.mu.Lock()
	st := a.refresh.status
	a.refresh.mu.Unlock()
	st.PhotosLeft = a.avatarPending.Load()
	if a.Store != nil {
		st.AvatarVersion = a.Store.AvatarVersion()
	}
	return st
}

// StartGoogleRefresh starts a refresh in the background ("list", "all" or
// "periodic"). A refresh already running is reported, not restarted; one
// asked for too soon returns ErrRefreshRateLimited with RetryAfterSec set.
func (a *App) StartGoogleRefresh(scope string) (RefreshStatus, error) {
	switch scope {
	case "list", "all", "periodic":
	default:
		return RefreshStatus{}, fmt.Errorf("unknown refresh scope %q", scope)
	}
	now := time.Now()
	a.refresh.mu.Lock()
	if a.refresh.status.Running {
		a.refresh.mu.Unlock()
		return a.GoogleRefreshStatus(), nil
	}
	last, min := a.refresh.lastList, refreshListMinInterval
	if scope == "all" {
		last, min = a.refresh.lastAll, refreshAllMinInterval
	}
	if scope != "periodic" && !last.IsZero() && now.Sub(last) < min {
		a.refresh.mu.Unlock()
		st := a.GoogleRefreshStatus()
		st.RetryAfterSec = int((min - now.Sub(last)).Seconds()) + 1
		return st, ErrRefreshRateLimited
	}
	a.refresh.lastList = now
	if scope == "all" {
		a.refresh.lastAll = now
	}
	a.refresh.status = RefreshStatus{Running: true, Scope: scope, Stage: "Fetching conversations", StartedAtMS: now.UnixMilli()}
	a.refresh.mu.Unlock()
	go a.runGoogleRefresh(scope)
	return a.GoogleRefreshStatus(), nil
}

func (a *App) setRefreshStage(stage string, convs, photos int) {
	a.refresh.mu.Lock()
	a.refresh.status.Stage = stage
	if convs >= 0 {
		a.refresh.status.Conversations = convs
	}
	if photos >= 0 {
		a.refresh.status.PhotosQueued = photos
	}
	a.refresh.mu.Unlock()
}

func (a *App) finishRefresh(err error) {
	a.refresh.mu.Lock()
	a.refresh.status.Running = false
	a.refresh.status.FinishedAtMS = time.Now().UnixMilli()
	if err != nil {
		a.refresh.status.Error = err.Error()
		a.refresh.status.Stage = "Failed"
	} else {
		a.refresh.status.Stage = "Done"
	}
	a.refresh.mu.Unlock()
}

func (a *App) runGoogleRefresh(scope string) {
	err := a.refreshFromGoogle(scope)
	a.finishRefresh(err)
	ev := a.Logger.Info()
	if err != nil {
		ev = a.Logger.Warn().Err(err)
	}
	st := a.GoogleRefreshStatus()
	ev.Str("scope", scope).Int("conversations", st.Conversations).Int("photos_queued", st.PhotosQueued).Msg("Google refresh finished")
}

func (a *App) refreshFromGoogle(scope string) error {
	gm, token := a.currentBackfillClient()
	if gm == nil {
		return errors.New("Google Messages isn't connected")
	}
	force := scope == "all"
	stop := a.refreshStopCh()
	seen := map[string]bool{}
	var cands []db.ContactAvatarCandidate
	candSeen := map[string]bool{}
	addCands := func(cs []db.ContactAvatarCandidate) {
		for _, c := range cs {
			k := db.ContactAvatarID(c) + "#" + c.GroupAvatarURL + "#" + c.EncryptedGroupIcon.SourceHash()
			if candSeen[k] {
				continue
			}
			candSeen[k] = true
			c.Force = force
			cands = append(cands, c)
		}
	}
	var oldestInboxMS int64
	stored := 0
	folders := []struct {
		f     gmproto.ListConversationsRequest_Folder
		count int
		name  string
	}{
		{gmproto.ListConversationsRequest_INBOX, refreshInboxCount, "inbox"},
		{gmproto.ListConversationsRequest_ARCHIVE, refreshFolderCount, "archive"},
		{gmproto.ListConversationsRequest_SPAM_BLOCKED, refreshFolderCount, "spam"},
	}
	for i, fo := range folders {
		resp, err := gm.ListConversationsWithCursor(fo.count, fo.f, nil)
		if err != nil {
			if a.HandleGoogleAuthExpiredError(err) {
				return errors.New("Google Messages sign-in expired")
			}
			if i == 0 {
				return fmt.Errorf("list conversations: %w", err)
			}
			a.Logger.Warn().Err(err).Str("folder", fo.name).Msg("Google refresh: folder list failed")
			continue
		}
		for _, conv := range resp.GetConversations() {
			if !a.backfillClientStillCurrent(token) {
				return errors.New("Google Messages disconnected during refresh")
			}
			id := conv.GetConversationID()
			if id == "" || seen[id] {
				continue
			}
			seen[id] = true
			if fo.name == "inbox" {
				if ts := conv.GetLastMessageTimestamp() / 1000; ts > 0 && (oldestInboxMS == 0 || ts < oldestInboxMS) {
					oldestInboxMS = ts
				}
			}
			// Spam/blocked: only hide ones already here, don't import spam.
			if fo.name == "spam" && !a.Store.ConversationExists(id) {
				continue
			}
			cs, err := a.storeConversationSnapshot(conv, force && stored == 0)
			if err != nil {
				a.Logger.Warn().Err(err).Str("conv_id", id).Msg("Google refresh: store conversation failed")
				continue
			}
			if fo.name != "spam" {
				addCands(cs)
			}
			stored++
		}
		a.setRefreshStage("Fetching conversations", stored, -1)
	}

	// Conversations shown here as recent but missing from Google's lists may
	// have been deleted, archived or marked spam on another device while we
	// missed the update: ask Google about each (a few at most).
	if oldestInboxMS > 0 {
		ids, _ := a.Store.VisibleSMSConversationIDsSince(oldestInboxMS, 200)
		checked := 0
		for _, id := range ids {
			if seen[id] || checked >= refreshStaleCheckMax {
				continue
			}
			checked++
			conv, err := gm.GetConversation(id)
			if err != nil || conv == nil || conv.GetConversationID() == "" {
				continue
			}
			if _, err := a.storeConversationSnapshot(conv, false); err == nil {
				stored++
			}
		}
	}
	a.emitConversationsChange()

	if force {
		a.refreshAccountPhotoAsync()
	}
	a.setRefreshStage("Updating photos", stored, len(cands))
	queued := a.queueGoogleAvatarCandidatesWait(stop, cands)
	// Wait (bounded) for the photo queue to drain so "Done" means done.
	deadline := time.Now().Add(10 * time.Minute)
	for a.avatarPending.Load() > 0 && time.Now().Before(deadline) {
		select {
		case <-stop:
			return nil
		case <-time.After(time.Second):
		}
	}
	a.setRefreshStage("Done", stored, queued)
	return nil
}

// StartPeriodicGoogleRefresh refreshes conversation names, members and
// photos every 12 hours (first run 10 minutes after start).
func (a *App) StartPeriodicGoogleRefresh() {
	stop := a.refreshStopCh()
	go func() {
		t := time.NewTimer(10 * time.Minute)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				if _, err := a.StartGoogleRefresh("periodic"); err != nil {
					a.Logger.Debug().Err(err).Msg("Periodic Google refresh not started")
				}
				t.Reset(refreshPeriodicEvery)
			}
		}
	}()
}

// StopGoogleRefresh stops the periodic refresh and any running wait.
func (a *App) StopGoogleRefresh() {
	ch := a.refreshStopCh()
	a.refresh.stopOnce.Do(func() { close(ch) })
}
