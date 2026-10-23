package webapp

// Firebase Cloud Messaging for the Android app's built-in viewer (a WebView
// has no Web Push). The app registers its FCM token as a push "device" with
// endpoint "fcm:<token>", so on/off, hide-text, test and pruning all reuse
// the Web Push code paths; only delivery differs. Messages are data-only so
// the app builds the notification itself (and can clear it when the chat is
// read elsewhere).
//
// Configuration (all optional; FCM stays off without a service account):
//   MESSAGES_FCM_SERVICE_ACCOUNT  path to the service account JSON key
//                                 (default: $CREDENTIALS_DIRECTORY/fcm-service-account.json,
//                                 i.e. systemd LoadCredential=)
//   MESSAGES_FCM_APP_ID           Firebase Android app id (1:NNN:android:...)
//   MESSAGES_FCM_API_KEY          Firebase Android API key (public client key)
//   MESSAGES_FCM_SENDER_ID        project number (defaults to the app id's)

import (
	"bytes"
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	fcmPrefix   = "fcm:"
	fcmScope    = "https://www.googleapis.com/auth/firebase.messaging"
	fcmTokenURL = "https://oauth2.googleapis.com/token"
)

var fcmTokenRE = regexp.MustCompile(`^[A-Za-z0-9_\-:.]+$`)

// FCMClientConfig is what the Android app needs to initialise Firebase.
type FCMClientConfig struct {
	APIKey    string `json:"api_key"`
	AppID     string `json:"app_id"`
	ProjectID string `json:"project_id"`
	SenderID  string `json:"sender_id"`
}

type fcmSender struct {
	client    FCMClientConfig
	email     string
	key       *rsa.PrivateKey
	tokenURI  string
	sendBase  string
	http      *http.Client
	mu        sync.Mutex
	access    string
	accessExp time.Time
}

type serviceAccountFile struct {
	Type        string `json:"type"`
	ProjectID   string `json:"project_id"`
	PrivateKey  string `json:"private_key"`
	ClientEmail string `json:"client_email"`
	TokenURI    string `json:"token_uri"`
}

// loadFCM returns nil (FCM off) when no service account is configured.
func loadFCM() (*fcmSender, error) {
	path := strings.TrimSpace(Getenv("MESSAGES_FCM_SERVICE_ACCOUNT"))
	if path == "" {
		if d := os.Getenv("CREDENTIALS_DIRECTORY"); d != "" {
			p := filepath.Join(d, "fcm-service-account.json")
			if _, err := os.Stat(p); err == nil {
				path = p
			}
		}
	}
	if path == "" {
		return nil, nil
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("fcm: read service account: %w", err)
	}
	var sa serviceAccountFile
	if err := json.Unmarshal(b, &sa); err != nil || sa.ClientEmail == "" || sa.PrivateKey == "" || sa.ProjectID == "" {
		return nil, errors.New("fcm: service account file is not a valid key")
	}
	blk, _ := pem.Decode([]byte(sa.PrivateKey))
	if blk == nil {
		return nil, errors.New("fcm: service account private key is not PEM")
	}
	var key *rsa.PrivateKey
	if k, err := x509.ParsePKCS8PrivateKey(blk.Bytes); err == nil {
		key, _ = k.(*rsa.PrivateKey)
	} else if k, err := x509.ParsePKCS1PrivateKey(blk.Bytes); err == nil {
		key = k
	}
	if key == nil {
		return nil, errors.New("fcm: service account private key is not RSA")
	}
	cc := FCMClientConfig{
		APIKey:    strings.TrimSpace(Getenv("MESSAGES_FCM_API_KEY")),
		AppID:     strings.TrimSpace(Getenv("MESSAGES_FCM_APP_ID")),
		ProjectID: sa.ProjectID,
		SenderID:  strings.TrimSpace(Getenv("MESSAGES_FCM_SENDER_ID")),
	}
	if cc.SenderID == "" {
		if p := strings.Split(cc.AppID, ":"); len(p) >= 2 {
			cc.SenderID = p[1]
		}
	}
	if cc.APIKey == "" || cc.AppID == "" || cc.SenderID == "" {
		return nil, errors.New("fcm: set MESSAGES_FCM_APP_ID and MESSAGES_FCM_API_KEY")
	}
	tu := sa.TokenURI
	if tu == "" {
		tu = fcmTokenURL
	}
	return &fcmSender{
		client: cc, email: sa.ClientEmail, key: key, tokenURI: tu,
		sendBase: "https://fcm.googleapis.com/v1/projects/" + url.PathEscape(sa.ProjectID) + "/messages:send",
		http:     &http.Client{Timeout: 20 * time.Second},
	}, nil
}

func b64url(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

// accessToken mints (and caches) an OAuth token from a self-signed JWT.
func (f *fcmSender) accessToken(ctx context.Context) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.access != "" && time.Until(f.accessExp) > 5*time.Minute {
		return f.access, nil
	}
	now := time.Now()
	hdr, _ := json.Marshal(map[string]string{"alg": "RS256", "typ": "JWT"})
	cl, _ := json.Marshal(map[string]any{
		"iss": f.email, "scope": fcmScope, "aud": f.tokenURI,
		"iat": now.Unix(), "exp": now.Add(time.Hour).Unix(),
	})
	unsigned := b64url(hdr) + "." + b64url(cl)
	sum := sha256.Sum256([]byte(unsigned))
	sig, err := rsa.SignPKCS1v15(rand.Reader, f.key, crypto.SHA256, sum[:])
	if err != nil {
		return "", err
	}
	form := url.Values{"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"}, "assertion": {unsigned + "." + b64url(sig)}}
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, f.tokenURI, strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := f.http.Do(req)
	if err != nil {
		return "", fmt.Errorf("fcm: token request: %w", err)
	}
	defer resp.Body.Close()
	var tr struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
		Error       string `json:"error"`
	}
	_ = json.NewDecoder(io.LimitReader(resp.Body, 64<<10)).Decode(&tr)
	if resp.StatusCode != 200 || tr.AccessToken == "" {
		return "", fmt.Errorf("fcm: token request: HTTP %d %s", resp.StatusCode, tr.Error)
	}
	f.access = tr.AccessToken
	f.accessExp = now.Add(time.Duration(tr.ExpiresIn) * time.Second)
	return f.access, nil
}

// send delivers one data-only message. Returns 404 when FCM says the token
// is gone (so the hub prunes it, like an expired Web Push subscription).
func (f *fcmSender) send(ctx context.Context, token string, payload []byte) (int, error) {
	var n map[string]any
	if err := json.Unmarshal(payload, &n); err != nil {
		return 0, err
	}
	data := map[string]string{}
	for k, v := range n {
		switch x := v.(type) {
		case string:
			data[k] = x
		case bool:
			data[k] = strconv.FormatBool(x)
		case float64:
			data[k] = strconv.FormatInt(int64(x), 10)
		}
	}
	if data["type"] == "" {
		data["type"] = "message"
	}
	body, _ := json.Marshal(map[string]any{"message": map[string]any{
		"token":   token,
		"data":    data,
		"android": map[string]any{"priority": "HIGH", "ttl": "21600s"},
	}})
	at, err := f.accessToken(ctx)
	if err != nil {
		return 0, err
	}
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, f.sendBase, bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+at)
	req.Header.Set("Content-Type", "application/json")
	resp, err := f.http.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	rb, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		return resp.StatusCode, nil
	}
	if resp.StatusCode == http.StatusUnauthorized {
		f.mu.Lock()
		f.access = ""
		f.mu.Unlock()
	}
	if fcmTokenGone(resp.StatusCode, rb) {
		return http.StatusNotFound, nil
	}
	return resp.StatusCode, fmt.Errorf("FCM HTTP %d", resp.StatusCode)
}

// fcmTokenGone: 404 / UNREGISTERED, or INVALID_ARGUMENT about the token.
func fcmTokenGone(status int, body []byte) bool {
	if status == http.StatusNotFound {
		return true
	}
	s := string(body)
	if strings.Contains(s, "UNREGISTERED") {
		return true
	}
	return status == http.StatusBadRequest && strings.Contains(s, "registration token")
}
