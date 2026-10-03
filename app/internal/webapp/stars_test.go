package webapp

import "testing"

func TestStarStorePersists(t *testing.T) {
	dir := t.TempDir()
	s, err := OpenStarStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.Set("m1", "c1", true); err != nil {
		t.Fatal(err)
	}
	_ = s.Set("m2", "c2", true)
	_ = s.Set("m2", "c2", false)
	s2, err := OpenStarStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	if l := s2.List(""); len(l) != 1 || l["m1"].ConversationID != "c1" {
		t.Fatalf("reloaded %+v", l)
	}
	if l := s2.List("c2"); len(l) != 0 {
		t.Fatalf("filter %+v", l)
	}
}
