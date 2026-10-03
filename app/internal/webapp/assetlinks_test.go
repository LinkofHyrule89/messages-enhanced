package webapp

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestAssetLinks(t *testing.T) {
	get := func() []assetStatement {
		rec := httptest.NewRecorder()
		handleAssetLinks(rec, httptest.NewRequest(http.MethodGet, "/.well-known/assetlinks.json", nil))
		if rec.Code != 200 || rec.Header().Get("Content-Type") != "application/json" {
			t.Fatalf("status %d type %q", rec.Code, rec.Header().Get("Content-Type"))
		}
		var s []assetStatement
		if err := json.Unmarshal(rec.Body.Bytes(), &s); err != nil || len(s) != 1 {
			t.Fatalf("bad json %v %s", err, rec.Body.String())
		}
		return s
	}
	s := get()
	if s[0].Target.PackageName != androidPackage || s[0].Target.Fingerprints[0] != androidReleaseCertSHA256 || s[0].Relation[0] != "delegate_permission/common.handle_all_urls" {
		t.Fatalf("default: %+v", s[0])
	}
	t.Setenv("MESSAGES_ANDROID_CERT_SHA256", " aa:bb , cc:dd ")
	t.Setenv("MESSAGES_ANDROID_PACKAGE", "com.example.me")
	s = get()
	if s[0].Target.PackageName != "com.example.me" || len(s[0].Target.Fingerprints) != 2 || s[0].Target.Fingerprints[1] != "CC:DD" {
		t.Fatalf("override: %+v", s[0])
	}
}
