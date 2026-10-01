package app

import (
	"context"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// GMClient abstracts the libgm methods used by backfill so we can test with
// a mock implementation. The real implementation wraps *libgm.Client.
type GMClient interface {
	ListConversationsWithCursor(count int, folder gmproto.ListConversationsRequest_Folder, cursor *gmproto.Cursor) (*gmproto.ListConversationsResponse, error)
	FetchMessages(conversationID string, count int64, cursor *gmproto.Cursor) (*gmproto.ListMessagesResponse, error)
	GetOrCreateConversation(req *gmproto.GetOrCreateConversationRequest) (*gmproto.GetOrCreateConversationResponse, error)
	ListContacts() (*gmproto.ListContactsResponse, error)
	GetParticipantThumbnail(participantIDs ...string) (*gmproto.GetThumbnailResponse, error)
	GetContactThumbnail(contactIDs ...string) (*gmproto.GetThumbnailResponse, error)
	// DownloadAvatar fetches an avatar image by URL (group conversation icons).
	DownloadAvatar(ctx context.Context, url string) ([]byte, error)
	// DeleteMessage deletes one message on the paired phone (delete for me;
	// the protocol request carries only the message ID).
	DeleteMessage(messageID string) (*gmproto.DeleteMessageResponse, error)
	// UpdateConversation changes a conversation on the phone (archive /
	// unarchive via UpdateData status, delete via DeleteData).
	UpdateConversation(req *gmproto.UpdateConversationRequest) (*gmproto.UpdateConversationResponse, error)
	// GetConversation reads one conversation from the phone.
	GetConversation(conversationID string) (*gmproto.Conversation, error)
}

// realGMClient wraps *libgm.Client to implement GMClient.
type realGMClient struct {
	gm *libgm.Client
}

func newRealGMClient(gm *libgm.Client) GMClient {
	return &realGMClient{gm: gm}
}

func (r *realGMClient) ListConversationsWithCursor(count int, folder gmproto.ListConversationsRequest_Folder, cursor *gmproto.Cursor) (*gmproto.ListConversationsResponse, error) {
	return r.gm.ListConversationsWithCursor(count, folder, cursor)
}

func (r *realGMClient) FetchMessages(conversationID string, count int64, cursor *gmproto.Cursor) (*gmproto.ListMessagesResponse, error) {
	return r.gm.FetchMessages(conversationID, count, cursor)
}

func (r *realGMClient) GetOrCreateConversation(req *gmproto.GetOrCreateConversationRequest) (*gmproto.GetOrCreateConversationResponse, error) {
	return r.gm.GetOrCreateConversation(req)
}

func (r *realGMClient) ListContacts() (*gmproto.ListContactsResponse, error) {
	return r.gm.ListContacts()
}

func (r *realGMClient) GetParticipantThumbnail(participantIDs ...string) (*gmproto.GetThumbnailResponse, error) {
	return r.gm.GetParticipantThumbnail(participantIDs...)
}

func (r *realGMClient) GetContactThumbnail(contactIDs ...string) (*gmproto.GetThumbnailResponse, error) {
	return r.gm.GetContactThumbnail(contactIDs...)
}

func (r *realGMClient) DownloadAvatar(ctx context.Context, url string) ([]byte, error) {
	return r.gm.DownloadAvatar(ctx, url)
}

func (r *realGMClient) DeleteMessage(messageID string) (*gmproto.DeleteMessageResponse, error) {
	return r.gm.DeleteMessage(messageID)
}

func (r *realGMClient) UpdateConversation(req *gmproto.UpdateConversationRequest) (*gmproto.UpdateConversationResponse, error) {
	return r.gm.UpdateConversation(req)
}

func (r *realGMClient) GetConversation(conversationID string) (*gmproto.Conversation, error) {
	return r.gm.GetConversation(conversationID)
}
