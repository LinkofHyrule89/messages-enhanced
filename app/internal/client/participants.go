package client

import (
	"encoding/json"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"

	"github.com/maxghenis/openmessage/internal/db"
)

// ParticipantInfo is one entry of db.Conversation.Participants (JSON).
type ParticipantInfo struct {
	Name      string `json:"name"`
	Number    string `json:"number"`
	IsMe      bool   `json:"is_me,omitempty"`
	ID        string `json:"id,omitempty"` // participant ID, used to resolve reaction actors to names
	ContactID string `json:"contact_id,omitempty"`
	// As Google writes names in "Read by …" status text.
	FirstName string `json:"first_name,omitempty"`
	// Hidden: Google lists the person but not as a current member (left or
	// was removed; Google's isVisible=false). Kept so older messages still
	// resolve sender names; member lists skip them.
	Hidden bool `json:"hidden,omitempty"`
}

// ParticipantsSnapshot builds the stored participants JSON and the avatar
// candidates for a Google conversation. Former members get no avatar fetch.
// includeSelf adds your own participant as an avatar candidate.
func ParticipantsSnapshot(conv *gmproto.Conversation, source string, includeSelf bool) (string, []db.ContactAvatarCandidate) {
	ps := conv.GetParticipants()
	if len(ps) == 0 {
		return "[]", nil
	}
	// Some payloads don't carry isVisible at all; only trust it when at least
	// one other participant is marked visible.
	anyVisible := false
	for _, p := range ps {
		if !p.GetIsMe() && p.GetIsVisible() {
			anyVisible = true
			break
		}
	}
	var cands []db.ContactAvatarCandidate
	infos := make([]ParticipantInfo, 0, len(ps))
	for _, p := range ps {
		info := ParticipantInfo{
			Name:      p.GetFullName(),
			IsMe:      p.GetIsMe(),
			ContactID: p.GetContactID(),
			FirstName: p.GetFirstName(),
			Hidden:    anyVisible && !p.GetIsMe() && !p.GetIsVisible(),
		}
		if id := p.GetID(); id != nil {
			info.Number = id.GetNumber()
			info.ID = id.GetParticipantID()
		}
		if info.Number == "" {
			info.Number = p.GetFormattedNumber()
		}
		if !info.Hidden && (!info.IsMe || (includeSelf && info.ID != "")) {
			cands = append(cands, db.ContactAvatarCandidate{
				SourcePlatform: "sms",
				ParticipantID:  info.ID,
				ContactID:      info.ContactID,
				PhoneNumber:    info.Number,
				DisplayName:    info.Name,
				Source:         source,
			})
		}
		infos = append(infos, info)
	}
	b, err := json.Marshal(infos)
	if err != nil {
		return "[]", cands
	}
	return string(b), cands
}
