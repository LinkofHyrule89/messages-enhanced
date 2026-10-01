package cmd

import "github.com/maxghenis/openmessage/internal/app"

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
