package db

import "testing"

func TestGroupAvatarParticipantIDAndHash(t *testing.T) {
	if got := GroupAvatarParticipantID(" 42 "); got != "conv:42" {
		t.Fatalf("GroupAvatarParticipantID = %q, want conv:42", got)
	}
	if got := GroupAvatarParticipantID(" "); got != "" {
		t.Fatalf("GroupAvatarParticipantID(blank) = %q, want empty", got)
	}
	a, b := GroupAvatarURLHash("https://x/a"), GroupAvatarURLHash(" https://x/a ")
	if a == "" || a != b || len(a) != 64 {
		t.Fatalf("GroupAvatarURLHash not a stable sha256 hex: %q %q", a, b)
	}
	if GroupAvatarURLHash("https://x/b") == a {
		t.Fatal("different URLs must hash differently")
	}
}

func TestGroupAvatarStoreRoundTrip(t *testing.T) {
	s, err := New(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	c := ContactAvatarCandidate{SourcePlatform: "sms", ParticipantID: GroupAvatarParticipantID("7"), DisplayName: "Climbing Crew", GroupAvatarURL: "https://lh3.googleusercontent.com/x"}

	if st, err := s.GetGroupAvatarState(c); err != nil || st != nil {
		t.Fatalf("empty state = %+v, %v; want nil, nil", st, err)
	}
	// A failed first try records the URL hash with no image.
	if err := s.MarkGroupAvatarChecked(c, "hash-1", 1000); err != nil {
		t.Fatal(err)
	}
	st, err := s.GetGroupAvatarState(c)
	if err != nil || st == nil || st.SourceURLHash != "hash-1" || st.ImageHash != "" || st.LastCheckedAtMS != 1000 {
		t.Fatalf("after failed check = %+v, %v", st, err)
	}
	// Success stores the image and URL hash, and /api/avatar's lookup finds it.
	img := []byte("\x89PNG fake")
	if err := s.UpsertGroupAvatar(c, "hash-1", img, "image/png", "img-hash", 2000); err != nil {
		t.Fatal(err)
	}
	st, _ = s.GetGroupAvatarState(c)
	if st.SourceURLHash != "hash-1" || st.ImageHash != "img-hash" {
		t.Fatalf("after upsert = %+v", st)
	}
	av, err := s.GetContactAvatar("sms", "conv:7", "", "")
	if err != nil || av == nil || string(av.ImageData) != string(img) || av.MimeType != "image/png" {
		t.Fatalf("GetContactAvatar(conv:7) = %+v, %v", av, err)
	}
	// Marking checked with "" keeps the recorded URL hash and the image.
	if err := s.MarkGroupAvatarChecked(c, "", 3000); err != nil {
		t.Fatal(err)
	}
	st, _ = s.GetGroupAvatarState(c)
	if st.SourceURLHash != "hash-1" || st.ImageHash != "img-hash" || st.LastCheckedAtMS != 3000 {
		t.Fatalf("after keep-hash check = %+v", st)
	}
	// A person's avatar lookup is unaffected by the group row.
	if av, _ := s.GetContactAvatar("sms", "7", "", ""); av != nil {
		t.Fatal("participant 7 must not match the conv:7 group icon")
	}
}
