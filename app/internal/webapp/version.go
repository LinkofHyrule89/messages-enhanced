package webapp

// Build version of the web app: a hash of the embedded static files. The
// page (index.html) is served with ?v=<version> on its scripts/styles and
// the version in a <meta>, and GET /api/app/version reports the running
// one, so open tabs notice a deploy and reload themselves.

import (
	"crypto/sha256"
	"encoding/hex"
	"io/fs"
	"net/http"
	"regexp"
	"sort"
	"strings"
)

var assetRefRe = regexp.MustCompile(`((?:src|href)="/app/[\w./-]+\.(?:js|css))"`)

func staticVersion(sub fs.FS) string {
	var names []string
	_ = fs.WalkDir(sub, ".", func(p string, d fs.DirEntry, err error) error {
		if err == nil && !d.IsDir() {
			names = append(names, p)
		}
		return nil
	})
	sort.Strings(names)
	h := sha256.New()
	for _, n := range names {
		b, err := fs.ReadFile(sub, n)
		if err != nil {
			continue
		}
		h.Write([]byte(n))
		h.Write([]byte{0})
		h.Write(b)
	}
	return hex.EncodeToString(h.Sum(nil))[:12]
}

// versionedIndex: index.html with cache-busted asset URLs and the version.
func versionedIndex(sub fs.FS, ver string) []byte {
	b, err := fs.ReadFile(sub, "index.html")
	if err != nil {
		return nil
	}
	s := assetRefRe.ReplaceAllString(string(b), `$1?v=`+ver+`"`)
	meta := `<meta name="app-version" content="` + ver + `">`
	if i := strings.Index(s, "<meta charset"); i >= 0 {
		if j := strings.Index(s[i:], ">"); j >= 0 {
			s = s[:i+j+1] + "\n" + meta + s[i+j+1:]
			return []byte(s)
		}
	}
	return []byte(strings.Replace(s, "<head>", "<head>\n"+meta, 1))
}

// GET /api/app/version -> {"version": "..."}
func (s *Server) handleVersion(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]string{"version": s.version})
}
