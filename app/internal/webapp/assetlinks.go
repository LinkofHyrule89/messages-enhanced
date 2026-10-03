package webapp

import (
	"encoding/json"
	"net/http"
	"strings"
)

// Digital Asset Links for the Messages Enhanced Android app (a Trusted Web
// Activity). With this file Chrome opens the web app full screen, without a
// URL bar, inside the app. Any self-hosted server works with the official
// release APK out of the box; people who sign their own build set
// MESSAGES_ANDROID_CERT_SHA256 (comma-separated) and, for a different
// package name, MESSAGES_ANDROID_PACKAGE.
const (
	androidPackage = "com.ubermicrostudios.messagesenhanced"
	// SHA-256 of the official release signing certificate.
	androidReleaseCertSHA256 = "C5:B7:0B:CA:8B:FA:4B:9C:FE:A0:A2:56:5E:34:AD:2E:CC:D2:DE:30:4B:60:FC:48:EC:3D:FD:23:91:B2:63:46"
)

type assetStatement struct {
	Relation []string    `json:"relation"`
	Target   assetTarget `json:"target"`
}

type assetTarget struct {
	Namespace    string   `json:"namespace"`
	PackageName  string   `json:"package_name"`
	Fingerprints []string `json:"sha256_cert_fingerprints"`
}

func assetLinksJSON() []byte {
	pkg := strings.TrimSpace(Getenv("MESSAGES_ANDROID_PACKAGE"))
	if pkg == "" {
		pkg = androidPackage
	}
	var fps []string
	for _, f := range strings.Split(Getenv("MESSAGES_ANDROID_CERT_SHA256"), ",") {
		if f = strings.ToUpper(strings.TrimSpace(f)); f != "" {
			fps = append(fps, f)
		}
	}
	if len(fps) == 0 {
		fps = []string{androidReleaseCertSHA256}
	}
	b, _ := json.MarshalIndent([]assetStatement{{
		Relation: []string{"delegate_permission/common.handle_all_urls"},
		Target:   assetTarget{Namespace: "android_app", PackageName: pkg, Fingerprints: fps},
	}}, "", "  ")
	return b
}

// handleAssetLinks serves /.well-known/assetlinks.json (public, no secrets).
func handleAssetLinks(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "public, max-age=3600")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	_, _ = w.Write(assetLinksJSON())
}
