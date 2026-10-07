package client

import (
	"encoding/json"
	"testing"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

func TestParticipantsSnapshotMarksFormerMembers(t *testing.T) {
	conv := &gmproto.Conversation{ConversationID: "g1", IsGroupChat: true, Participants: []*gmproto.Participant{
		{ID: &gmproto.SmallInfo{ParticipantID: "p1", Number: "+15555550101"}, FullName: "Current", IsVisible: true},
		{ID: &gmproto.SmallInfo{ParticipantID: "p2", Number: "+15555550102"}, FullName: "Left"},
		{ID: &gmproto.SmallInfo{ParticipantID: "me", Number: "+15555550100"}, IsMe: true},
	}}
	js, cands := ParticipantsSnapshot(conv, "live", false)
	var infos []ParticipantInfo
	if err := json.Unmarshal([]byte(js), &infos); err != nil || len(infos) != 3 {
		t.Fatalf("infos=%v err=%v", infos, err)
	}
	if infos[0].Hidden || !infos[1].Hidden || infos[2].Hidden {
		t.Fatalf("hidden flags wrong: %+v", infos)
	}
	if len(cands) != 1 || cands[0].ParticipantID != "p1" {
		t.Fatalf("former member must not be an avatar candidate: %+v", cands)
	}
	// No visibility info at all: nobody is hidden.
	conv.Participants[0].IsVisible = false
	js, _ = ParticipantsSnapshot(conv, "live", false)
	infos = nil
	_ = json.Unmarshal([]byte(js), &infos)
	for _, i := range infos {
		if i.Hidden {
			t.Fatalf("without visibility data nobody should be hidden: %+v", infos)
		}
	}
}
