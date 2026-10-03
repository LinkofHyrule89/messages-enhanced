package app

import "testing"

func TestAccountPhotoURLIn(t *testing.T) {
	body := `)]}'\n["gaia.l.a.r",[["gaia.l.a",1,"Alex","alex@example.com","https:\/\/lh3.googleusercontent.com\/a\/ACg8ocJx-abc_123\u003ds48-c",1,1,0]]]`
	got := accountPhotoURLIn(body)
	if got != "https://lh3.googleusercontent.com/a/ACg8ocJx-abc_123=s48-c" {
		t.Fatalf("got %q", got)
	}
	if s := sizedPhotoURL(got, 256); s != "https://lh3.googleusercontent.com/a/ACg8ocJx-abc_123=s256-c" {
		t.Fatalf("sized %q", s)
	}
	if s := sizedPhotoURL("https://lh3.googleusercontent.com/ogw/AF2bZy", 256); s != "https://lh3.googleusercontent.com/ogw/AF2bZy=s256-c" {
		t.Fatalf("sized %q", s)
	}
	if accountPhotoURLIn(`<img src="https://lh3.googleusercontent.com/proxy/xyz">`) != "" {
		t.Fatal("non-account photo matched")
	}
}
