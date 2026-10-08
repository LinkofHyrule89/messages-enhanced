package cmd

import (
	"errors"

	"github.com/maxghenis/openmessage/internal/app"
)

// webCarBackend adapts *app.App to webapp.CarBackend.
type webCarBackend struct{ a *app.App }

func (b webCarBackend) DeleteMessage(id string) (any, error) { return b.a.CarDeleteMessage(id) }
func (b webCarBackend) Contacts() (any, error)               { return b.a.CarContacts() }
func (b webCarBackend) StartConversation(numbers []string, groupName string) (any, error) {
	return b.a.CarStartConversation(numbers, groupName)
}
func (b webCarBackend) FolderConversations(folder string) (any, error) {
	return b.a.CarFolderConversations(folder)
}
func (b webCarBackend) FolderMessages(id string) (any, error) { return b.a.CarFolderMessages(id) }
func (b webCarBackend) PinConversation(id string, pinned bool) (any, error) {
	return b.a.CarPinConversation(id, pinned)
}
func (b webCarBackend) ArchiveConversation(id string, archived bool) (any, error) {
	return b.a.CarArchiveConversation(id, archived)
}
func (b webCarBackend) TrashConversation(id string) (any, error) {
	return b.a.CarTrashConversation(id)
}
func (b webCarBackend) MuteConversation(id string, muted bool) (any, error) {
	return b.a.CarMuteConversation(id, muted)
}
func (b webCarBackend) MarkConversationRead(id string) (any, error) {
	return b.a.CarMarkConversationRead(id)
}
func (b webCarBackend) ConversationMeta(id string) (any, error) {
	return b.a.CarConversationMeta(id)
}
func (b webCarBackend) RefreshConversation(id string) (any, int, error) {
	res, retry, err := b.a.RefreshConversation(id)
	if retry > 0 {
		return nil, retry, nil
	}
	if err != nil {
		return nil, 0, err
	}
	return res, 0, nil
}

// webRefreshBackend adapts *app.App to webapp.RefreshBackend.
type webRefreshBackend struct{ a *app.App }

func (b webRefreshBackend) StartRefresh(scope string) (any, int, error) {
	st, err := b.a.StartGoogleRefresh(scope)
	if errors.Is(err, app.ErrRefreshRateLimited) {
		return st, st.RetryAfterSec, nil
	}
	return st, 0, err
}
func (b webRefreshBackend) RefreshStatus() any { return b.a.GoogleRefreshStatus() }

// webGrokBackend adapts *app.App to webapp.GrokBackend.
type webGrokBackend struct{ a *app.App }

func (b webGrokBackend) GrokStatus() any { return b.a.GrokStatus() }
func (b webGrokBackend) SetGrokSettings(enabled bool, trigger string, groqEnabled *bool) (any, error) {
	groq := b.a.GrokStatus().GroqEnabled
	if groqEnabled != nil {
		groq = *groqEnabled
	}
	return b.a.SetGrokSettings(app.GrokSettings{Enabled: enabled, Trigger: trigger, GroqEnabled: groq})
}
