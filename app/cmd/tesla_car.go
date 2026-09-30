package cmd

import "github.com/maxghenis/openmessage/internal/app"

// teslaCarBackend adapts *app.App to tesla.CarBackend.
type teslaCarBackend struct{ a *app.App }

func (b teslaCarBackend) DeleteMessage(id string) (any, error) { return b.a.CarDeleteMessage(id) }
func (b teslaCarBackend) Contacts() (any, error)               { return b.a.CarContacts() }
func (b teslaCarBackend) StartConversation(numbers []string, groupName string) (any, error) {
	return b.a.CarStartConversation(numbers, groupName)
}
func (b teslaCarBackend) FolderConversations(folder string) (any, error) {
	return b.a.CarFolderConversations(folder)
}
func (b teslaCarBackend) FolderMessages(id string) (any, error) { return b.a.CarFolderMessages(id) }
