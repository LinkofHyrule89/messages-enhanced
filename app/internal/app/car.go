package app

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strings"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"

	"github.com/maxghenis/openmessage/internal/client"
	"github.com/maxghenis/openmessage/internal/db"
)

// Backend for the Tesla car page's message menu (Delete), "Start chat" panel
// and read-only Google folders (Archived / Spam / Blocked). The HTTP layer
// lives in internal/tesla; these methods hold the logic so they can be tested
// with the mock GMClient.

// CarError is an error with the HTTP status the car endpoints should return.
type CarError struct {
	Status int
	Msg    string
}

func (e *CarError) Error() string   { return e.Msg }
func (e *CarError) HTTPStatus() int { return e.Status }

func carErr(status int, format string, args ...any) *CarError {
	return &CarError{Status: status, Msg: fmt.Sprintf(format, args...)}
}

const carGoogleDisconnectedMsg = "Google Messages isn't connected. Pair your phone, then try again."

func isGooglePlatform(p string) bool {
	switch strings.ToLower(strings.TrimSpace(p)) {
	case "", "sms", "rcs":
		return true
	}
	return false
}

// ---------- delete ----------

// CarDeleteResult reports how a message was deleted.
type CarDeleteResult struct {
	MessageID      string `json:"message_id"`
	ConversationID string `json:"conversation_id"`
	// Scope is "google" when libgm's DeleteMessage removed it on the paired
	// phone (and so from Messages for web), or "local" when only this
	// server's database row was removed (non-Google platforms, unsent
	// placeholders). Neither removes it from the other person's phone.
	Scope string `json:"scope"`
}

// CarDeleteMessage deletes one message. Google Messages messages are deleted
// on the paired phone through libgm (ActionType DELETE_MESSAGE, a "delete for
// me" request that carries only the message ID) and then removed from the
// local database; if Google isn't connected nothing is deleted, because a
// local-only delete would come back on the next sync.
func (a *App) CarDeleteMessage(messageID string) (*CarDeleteResult, error) {
	messageID = strings.TrimSpace(messageID)
	if messageID == "" {
		return nil, carErr(http.StatusBadRequest, "message_id is required")
	}
	m, err := a.Store.GetMessageByID(messageID)
	if errors.Is(err, sql.ErrNoRows) || (err == nil && m == nil) {
		return nil, carErr(http.StatusNotFound, "message not found")
	}
	if err != nil {
		return nil, carErr(http.StatusInternalServerError, "load message: %v", err)
	}
	res := &CarDeleteResult{MessageID: m.MessageID, ConversationID: m.ConversationID, Scope: "local"}
	if isGooglePlatform(m.SourcePlatform) && !strings.HasPrefix(m.MessageID, "tmp_") {
		gm := a.getGMClient()
		if gm == nil {
			return nil, carErr(http.StatusServiceUnavailable, carGoogleDisconnectedMsg)
		}
		resp, err := gm.DeleteMessage(m.MessageID)
		if err != nil {
			a.HandleGoogleAuthExpiredError(err)
			return nil, carErr(http.StatusBadGateway, "Google Messages couldn't delete it: %v", err)
		}
		if !resp.GetSuccess() {
			return nil, carErr(http.StatusBadGateway, "Google Messages refused to delete this message")
		}
		res.Scope = "google"
	}
	if err := a.Store.DeleteMessageByID(m.MessageID); err != nil {
		return nil, carErr(http.StatusInternalServerError, "delete local copy: %v", err)
	}
	a.Logger.Info().Str("conv_id", m.ConversationID).Str("scope", res.Scope).Msg("Car page: message deleted")
	a.emitMessagesChange(m.ConversationID)
	a.emitConversationsChange()
	return res, nil
}

// ---------- contacts ----------

// CarContact is one person the Start chat panel can pick.
type CarContact struct {
	Name          string `json:"name"`
	Number        string `json:"number"`
	ContactID     string `json:"contact_id,omitempty"`
	ParticipantID string `json:"participant_id,omitempty"`
	// ConversationID is the existing 1:1 Google Messages conversation, if any.
	ConversationID string `json:"conversation_id,omitempty"`
	LastMessageTS  int64  `json:"last_message_ts,omitempty"`
}

// CarContacts is the Start chat panel's data: up to 8 top contacts (most
// recent 1:1 conversations) and everyone A to Z.
type CarContacts struct {
	Top []CarContact `json:"top"`
	All []CarContact `json:"all"`
}

// carPhoneKey normalizes a phone number (or email) for matching: the last
// 10 digits when there are at least 10, otherwise all digits; addresses
// without digits are lowercased.
func carPhoneKey(v string) string {
	v = strings.TrimSpace(v)
	var digits strings.Builder
	for _, r := range v {
		if r >= '0' && r <= '9' {
			digits.WriteRune(r)
		}
	}
	d := digits.String()
	if strings.Contains(v, "@") || len(d) < 3 {
		return strings.ToLower(v)
	}
	if len(d) > 10 {
		d = d[len(d)-10:]
	}
	return d
}

type carParticipant struct {
	Name      string `json:"name"`
	Number    string `json:"number"`
	IsMe      bool   `json:"is_me"`
	ID        string `json:"id"`
	ContactID string `json:"contact_id"`
}

func parseCarParticipants(raw string) []carParticipant {
	var ps []carParticipant
	_ = json.Unmarshal([]byte(raw), &ps)
	return ps
}

// oneToOnePeople maps phone key -> the most recent 1:1 Google conversation.
func (a *App) oneToOnePeople() (map[string]CarContact, error) {
	convs, err := a.Store.ListConversations(5000)
	if err != nil {
		return nil, err
	}
	out := map[string]CarContact{}
	for _, c := range convs {
		if c == nil || c.IsGroup || !isGooglePlatform(c.SourcePlatform) {
			continue
		}
		var other *carParticipant
		n := 0
		for _, p := range parseCarParticipants(c.Participants) {
			if p.IsMe {
				continue
			}
			n++
			pp := p
			other = &pp
		}
		if n != 1 || other == nil || strings.TrimSpace(other.Number) == "" {
			continue
		}
		key := carPhoneKey(other.Number)
		if prev, ok := out[key]; ok && prev.LastMessageTS >= c.LastMessageTS {
			continue
		}
		name := strings.TrimSpace(other.Name)
		if name == "" {
			name = strings.TrimSpace(c.Name)
		}
		out[key] = CarContact{
			Name:           name,
			Number:         other.Number,
			ContactID:      other.ContactID,
			ParticipantID:  other.ID,
			ConversationID: c.ConversationID,
			LastMessageTS:  c.LastMessageTS,
		}
	}
	return out, nil
}

// CarContacts merges the synced contacts table with people from existing 1:1
// conversations. Local data only; no Google calls.
func (a *App) CarContacts() (*CarContacts, error) {
	people, err := a.oneToOnePeople()
	if err != nil {
		return nil, carErr(http.StatusInternalServerError, "list conversations: %v", err)
	}
	contacts, err := a.Store.ListContacts("", 5000)
	if err != nil {
		return nil, carErr(http.StatusInternalServerError, "list contacts: %v", err)
	}
	seen := map[string]bool{}
	all := []CarContact{}
	for _, c := range contacts {
		if c == nil || strings.TrimSpace(c.Number) == "" {
			continue
		}
		key := carPhoneKey(c.Number)
		if seen[key] {
			continue
		}
		seen[key] = true
		cc := CarContact{Name: strings.TrimSpace(c.Name), Number: strings.TrimSpace(c.Number), ContactID: c.ContactID}
		if p, ok := people[key]; ok {
			cc.ParticipantID = p.ParticipantID
			cc.ConversationID = p.ConversationID
			cc.LastMessageTS = p.LastMessageTS
			if cc.Name == "" {
				cc.Name = p.Name
			}
		}
		all = append(all, cc)
	}
	for key, p := range people {
		if seen[key] {
			continue
		}
		seen[key] = true
		all = append(all, p)
	}
	for i := range all {
		if all[i].Name == "" {
			all[i].Name = all[i].Number
		}
	}
	sort.SliceStable(all, func(i, j int) bool {
		ni, nj := strings.ToLower(all[i].Name), strings.ToLower(all[j].Name)
		if ni != nj {
			return ni < nj
		}
		return all[i].Number < all[j].Number
	})
	var top []CarContact
	for _, c := range all {
		if c.ConversationID != "" {
			top = append(top, c)
		}
	}
	sort.SliceStable(top, func(i, j int) bool { return top[i].LastMessageTS > top[j].LastMessageTS })
	if len(top) > 8 {
		top = top[:8]
	}
	if top == nil {
		top = []CarContact{}
	}
	return &CarContacts{Top: top, All: all}, nil
}

// ---------- start conversation ----------

// CarStartResult is the conversation to open after picking recipients.
type CarStartResult struct {
	ConversationID string `json:"conversation_id"`
	Name           string `json:"name"`
	IsGroup        bool   `json:"is_group"`
	// Existing is true when an existing local 1:1 conversation was opened
	// without contacting Google.
	Existing bool `json:"existing"`
}

const carMaxRecipients = 20

// CarStartConversation opens the existing 1:1 conversation with a number, or
// gets/creates one through libgm's GetOrCreateConversation (several numbers =
// a group; when Google answers CREATE_RCS it retries with createRCSGroup, the
// same way mautrix-gmessages' startchat.go does). Nothing is sent.
func (a *App) CarStartConversation(numbers []string, groupName string) (*CarStartResult, error) {
	var clean []string
	seen := map[string]bool{}
	for _, n := range numbers {
		n = strings.TrimSpace(n)
		if n == "" {
			continue
		}
		key := carPhoneKey(n)
		if seen[key] {
			continue
		}
		seen[key] = true
		clean = append(clean, n)
	}
	if len(clean) == 0 {
		return nil, carErr(http.StatusBadRequest, "pick at least one recipient")
	}
	if len(clean) > carMaxRecipients {
		return nil, carErr(http.StatusBadRequest, "too many recipients (max %d)", carMaxRecipients)
	}
	if len(clean) == 1 {
		people, err := a.oneToOnePeople()
		if err == nil {
			if p, ok := people[carPhoneKey(clean[0])]; ok {
				return &CarStartResult{ConversationID: p.ConversationID, Name: p.Name, Existing: true}, nil
			}
		}
	}
	gm := a.getGMClient()
	if gm == nil {
		return nil, carErr(http.StatusServiceUnavailable, carGoogleDisconnectedMsg)
	}
	req := &gmproto.GetOrCreateConversationRequest{Numbers: NewContactNumbers(clean)}
	groupName = strings.TrimSpace(groupName)
	if len(clean) > 1 && groupName != "" {
		req.RCSGroupName = &groupName
	}
	resp, err := gm.GetOrCreateConversation(req)
	if err == nil && len(clean) > 1 && resp.GetStatus() == gmproto.GetOrCreateConversationResponse_CREATE_RCS {
		if req.RCSGroupName == nil {
			empty := ""
			req.RCSGroupName = &empty
		}
		yes := true
		req.CreateRCSGroup = &yes
		resp, err = gm.GetOrCreateConversation(req)
	}
	if err != nil {
		a.HandleGoogleAuthExpiredError(err)
		return nil, carErr(http.StatusBadGateway, "Google Messages couldn't open the conversation: %v", err)
	}
	conv := resp.GetConversation()
	if conv == nil || conv.GetConversationID() == "" {
		return nil, carErr(http.StatusBadGateway, "Google Messages returned no conversation (status %s)", resp.GetStatus())
	}
	if err := a.storeConversation(conv); err != nil {
		return nil, carErr(http.StatusInternalServerError, "store conversation: %v", err)
	}
	a.emitConversationsChange()
	a.Logger.Info().Str("conv_id", conv.GetConversationID()).Int("recipients", len(clean)).Msg("Car page: opened conversation via GetOrCreateConversation")
	return &CarStartResult{
		ConversationID: conv.GetConversationID(),
		Name:           carConversationName(conv),
		IsGroup:        conv.GetIsGroupChat() || len(clean) > 1,
	}, nil
}

func carConversationName(conv *gmproto.Conversation) string {
	if n := strings.TrimSpace(conv.GetName()); n != "" {
		return n
	}
	var names []string
	for _, p := range conv.GetParticipants() {
		if p.GetIsMe() {
			continue
		}
		n := strings.TrimSpace(p.GetFullName())
		if n == "" {
			n = strings.TrimSpace(p.GetFormattedNumber())
		}
		if n != "" {
			names = append(names, n)
		}
	}
	return strings.Join(names, ", ")
}

// ---------- folders (read-only) ----------

// Car folder names. Spam and Blocked share one protocol folder
// (ListConversationsRequest_SPAM_BLOCKED); they are told apart by the
// conversation's Status (SPAM_FOLDER vs BLOCKED_FOLDER).
const (
	CarFolderArchived = "archived"
	CarFolderSpam     = "spam"
	CarFolderBlocked  = "blocked"
)

// CarFolderConversation is a conversation listed live from a Google folder.
type CarFolderConversation struct {
	*db.Conversation
	Folder string `json:"folder"`
	Status string `json:"google_status"`
	// Local is true when the conversation (and so its messages) is also in
	// the local database; otherwise the car page reads messages live.
	Local bool `json:"local"`
}

func carFolderRequest(folder string) (gmproto.ListConversationsRequest_Folder, bool) {
	switch folder {
	case CarFolderArchived:
		return gmproto.ListConversationsRequest_ARCHIVE, true
	case CarFolderSpam, CarFolderBlocked:
		return gmproto.ListConversationsRequest_SPAM_BLOCKED, true
	}
	return gmproto.ListConversationsRequest_UNKNOWN, false
}

func carFolderMatches(folder string, status gmproto.ConversationStatus) bool {
	switch folder {
	case CarFolderBlocked:
		return status == gmproto.ConversationStatus_BLOCKED_FOLDER
	case CarFolderSpam:
		return status != gmproto.ConversationStatus_BLOCKED_FOLDER
	}
	return true
}

// carConversationSnapshot converts a Google conversation to the local shape
// without storing it.
func carConversationSnapshot(conv *gmproto.Conversation) *db.Conversation {
	var ps []carParticipant
	for _, p := range conv.GetParticipants() {
		cp := carParticipant{Name: p.GetFullName(), IsMe: p.GetIsMe(), ContactID: p.GetContactID()}
		if id := p.GetID(); id != nil {
			cp.Number = id.GetNumber()
			cp.ID = id.GetParticipantID()
		}
		if cp.Number == "" {
			cp.Number = p.GetFormattedNumber()
		}
		ps = append(ps, cp)
	}
	participants := "[]"
	if len(ps) > 0 {
		if b, err := json.Marshal(ps); err == nil {
			participants = string(b)
		}
	}
	unread := 0
	if conv.GetUnread() {
		unread = 1
	}
	return &db.Conversation{
		ConversationID:     conv.GetConversationID(),
		Name:               carConversationName(conv),
		IsGroup:            conv.GetIsGroupChat(),
		Participants:       participants,
		LastMessageTS:      conv.GetLastMessageTimestamp() / 1000,
		UnreadCount:        unread,
		SourcePlatform:     "sms",
		LastMessagePreview: strings.TrimSpace(conv.GetLatestMessage().GetDisplayContent()),
	}
}

const carFolderMaxPages = 3

// CarFolderConversations lists a Google folder live (read-only
// ListConversations calls; nothing is stored or changed).
func (a *App) CarFolderConversations(folder string) ([]CarFolderConversation, error) {
	folder = strings.ToLower(strings.TrimSpace(folder))
	reqFolder, ok := carFolderRequest(folder)
	if !ok {
		return nil, carErr(http.StatusBadRequest, "unknown folder %q (use archived, spam or blocked)", folder)
	}
	gm := a.getGMClient()
	if gm == nil {
		return nil, carErr(http.StatusServiceUnavailable, "Folders are read live from Google Messages, which isn't connected. Pair your phone, then try again.")
	}
	out := []CarFolderConversation{}
	seen := map[string]bool{}
	var cursor *gmproto.Cursor
	for page := 0; page < carFolderMaxPages; page++ {
		resp, err := gm.ListConversationsWithCursor(100, reqFolder, cursor)
		if err != nil {
			a.HandleGoogleAuthExpiredError(err)
			return nil, carErr(http.StatusBadGateway, "Google Messages couldn't list the folder: %v", err)
		}
		for _, conv := range resp.GetConversations() {
			id := conv.GetConversationID()
			if id == "" || seen[id] || !carFolderMatches(folder, conv.GetStatus()) {
				continue
			}
			seen[id] = true
			snap := carConversationSnapshot(conv)
			fc := CarFolderConversation{Conversation: snap, Folder: folder, Status: conv.GetStatus().String()}
			if local, err := a.Store.GetConversation(id); err == nil && local != nil {
				fc.Local = true
				if snap.Name == "" {
					snap.Name = local.Name
				}
			}
			out = append(out, fc)
		}
		next := resp.GetCursor()
		if next == nil || next.GetLastItemID() == "" || len(resp.GetConversations()) == 0 {
			break
		}
		cursor = next
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].LastMessageTS > out[j].LastMessageTS })
	return out, nil
}

// CarFolderMessages reads a folder conversation's latest messages live
// (read-only FetchMessages), newest first like /api/conversations/{id}/messages.
// Attachments are shown as a label only (their keys aren't stored).
func (a *App) CarFolderMessages(conversationID string) ([]*db.Message, error) {
	conversationID = strings.TrimSpace(conversationID)
	if conversationID == "" {
		return nil, carErr(http.StatusBadRequest, "conversation_id is required")
	}
	gm := a.getGMClient()
	if gm == nil {
		return nil, carErr(http.StatusServiceUnavailable, carGoogleDisconnectedMsg)
	}
	resp, err := gm.FetchMessages(conversationID, 50, nil)
	if err != nil {
		a.HandleGoogleAuthExpiredError(err)
		return nil, carErr(http.StatusBadGateway, "Google Messages couldn't load the messages: %v", err)
	}
	out := []*db.Message{}
	for _, msg := range resp.GetMessages() {
		if msg == nil || msg.GetMessageID() == "" {
			continue
		}
		status := "unknown"
		if ms := msg.GetMessageStatus(); ms != nil {
			status = ms.GetStatus().String()
		}
		if status == gmproto.MessageStatusType_MESSAGE_DELETED.String() {
			continue
		}
		name, number := client.ExtractSenderInfo(msg)
		m := &db.Message{
			MessageID:      msg.GetMessageID(),
			ConversationID: conversationID,
			SenderName:     name,
			SenderNumber:   number,
			Body:           client.ExtractMessageBody(msg),
			TimestampMS:    msg.GetTimestamp() / 1000,
			Status:         status,
			IsFromMe:       client.MessageIsFromMe(msg),
			SourcePlatform: "sms",
		}
		if strings.TrimSpace(m.Body) == "" {
			if media := client.ExtractMediaInfo(msg); media != nil {
				m.Body = "[Attachment]"
			}
		}
		out = append(out, m)
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].TimestampMS > out[j].TimestampMS })
	return out, nil
}
