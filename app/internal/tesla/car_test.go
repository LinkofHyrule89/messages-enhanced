package tesla

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

type statusErr struct {
	code int
	msg  string
}

func (e statusErr) Error() string   { return e.msg }
func (e statusErr) HTTPStatus() int { return e.code }

type fakeCar struct {
	deleted   []string
	started   [][]string
	groupName string
	folder    string
	folderMsg string
	deleteErr error
}

func (f *fakeCar) DeleteMessage(id string) (any, error) {
	if f.deleteErr != nil {
		return nil, f.deleteErr
	}
	f.deleted = append(f.deleted, id)
	return map[string]string{"message_id": id, "scope": "google"}, nil
}
func (f *fakeCar) Contacts() (any, error) {
	return map[string]any{"top": []any{}, "all": []map[string]string{{"name": "Ann", "number": "+15550001111"}}}, nil
}
func (f *fakeCar) StartConversation(numbers []string, groupName string) (any, error) {
	f.started = append(f.started, numbers)
	f.groupName = groupName
	return map[string]any{"conversation_id": "c1", "existing": true}, nil
}
func (f *fakeCar) FolderConversations(folder string) (any, error) {
	f.folder = folder
	if folder == "nope" {
		return nil, statusErr{400, "unknown folder"}
	}
	return []map[string]string{{"ConversationID": "a1", "folder": folder}}, nil
}
func (f *fakeCar) FolderMessages(id string) (any, error) {
	f.folderMsg = id
	return nil, statusErr{503, "Google Messages isn't connected"}
}

func TestCarEndpointsRequireLogin(t *testing.T) {
	car := &fakeCar{}
	h, _, inner := newTestServer(t, func(_ *Config, d *Deps) { d.Car = car; d.Typing = NewTypingTracker() })
	for _, p := range []string{"/api/tesla/contacts", "/api/tesla/folder?name=archived", "/api/tesla/folder/messages?conversation_id=x", "/api/tesla/typing"} {
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "http://car.example"+p, nil))
		if rr.Code != http.StatusUnauthorized && rr.Code != http.StatusFound && rr.Code != http.StatusSeeOther {
			t.Fatalf("%s without login: %d", p, rr.Code)
		}
	}
	for _, p := range []string{"/api/tesla/messages/delete", "/api/tesla/conversations/start"} {
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost, "http://car.example"+p, strings.NewReader(`{"message_id":"m1","numbers":["1"]}`)))
		if rr.Code < 300 {
			t.Fatalf("%s without login: %d", p, rr.Code)
		}
	}
	if len(car.deleted) != 0 || len(car.started) != 0 || inner.hits.Load() != 0 {
		t.Fatalf("unauthenticated requests reached the backend: %+v hits=%d", car, inner.hits.Load())
	}
}

func TestCarDeleteAndStartAreSameOriginPOSTs(t *testing.T) {
	car := &fakeCar{}
	h, _, _ := newTestServer(t, func(_ *Config, d *Deps) { d.Car = car })
	c := login(t, h)

	// Cross-origin write is rejected before the backend.
	req := httptest.NewRequest(http.MethodPost, "http://car.example/api/tesla/messages/delete", strings.NewReader(`{"message_id":"m1"}`))
	req.Header.Set("Origin", "https://evil.example")
	req.AddCookie(c)
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	if rr.Code != http.StatusForbidden || len(car.deleted) != 0 {
		t.Fatalf("cross-origin delete: %d deleted=%v", rr.Code, car.deleted)
	}
	if rr := authedReq(t, h, c, http.MethodGet, "/api/tesla/messages/delete", "", nil); rr.Code != http.StatusMethodNotAllowed {
		t.Fatalf("GET delete = %d", rr.Code)
	}
	rr = authedReq(t, h, c, http.MethodPost, "/api/tesla/messages/delete", "application/json", strings.NewReader(`{"message_id":"m1"}`))
	if rr.Code != 200 || len(car.deleted) != 1 || car.deleted[0] != "m1" || !strings.Contains(rr.Body.String(), `"scope":"google"`) {
		t.Fatalf("delete: %d %s %v", rr.Code, rr.Body.String(), car.deleted)
	}
	rr = authedReq(t, h, c, http.MethodPost, "/api/tesla/messages/delete", "application/json", strings.NewReader(`not json`))
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("bad json = %d", rr.Code)
	}
	car.deleteErr = statusErr{503, "Google Messages isn't connected"}
	rr = authedReq(t, h, c, http.MethodPost, "/api/tesla/messages/delete", "application/json", strings.NewReader(`{"message_id":"m2"}`))
	if rr.Code != 503 || !strings.Contains(rr.Body.String(), "isn't connected") {
		t.Fatalf("status passthrough: %d %s", rr.Code, rr.Body.String())
	}

	rr = authedReq(t, h, c, http.MethodPost, "/api/tesla/conversations/start", "application/json", strings.NewReader(`{"numbers":["+15550001111"],"group_name":"x"}`))
	if rr.Code != 200 || len(car.started) != 1 || car.started[0][0] != "+15550001111" || car.groupName != "x" {
		t.Fatalf("start: %d %s %+v", rr.Code, rr.Body.String(), car)
	}
}

func TestCarReadEndpoints(t *testing.T) {
	car := &fakeCar{}
	h, _, _ := newTestServer(t, func(_ *Config, d *Deps) { d.Car = car })
	c := login(t, h)
	rr := authedReq(t, h, c, http.MethodGet, "/api/tesla/contacts", "", nil)
	if rr.Code != 200 || !strings.Contains(rr.Body.String(), `"Ann"`) {
		t.Fatalf("contacts: %d %s", rr.Code, rr.Body.String())
	}
	rr = authedReq(t, h, c, http.MethodGet, "/api/tesla/folder?name=spam", "", nil)
	if rr.Code != 200 || car.folder != "spam" {
		t.Fatalf("folder: %d %s", rr.Code, rr.Body.String())
	}
	rr = authedReq(t, h, c, http.MethodGet, "/api/tesla/folder?name=nope", "", nil)
	if rr.Code != 400 {
		t.Fatalf("bad folder: %d", rr.Code)
	}
	rr = authedReq(t, h, c, http.MethodPost, "/api/tesla/folder?name=spam", "application/json", strings.NewReader(`{}`))
	if rr.Code != http.StatusMethodNotAllowed {
		t.Fatalf("POST folder must be rejected (read-only): %d", rr.Code)
	}
	rr = authedReq(t, h, c, http.MethodGet, "/api/tesla/folder/messages?conversation_id=a1", "", nil)
	if rr.Code != 503 || car.folderMsg != "a1" {
		t.Fatalf("folder messages: %d", rr.Code)
	}
}

func TestCarEndpointsWithoutBackend(t *testing.T) {
	h, _, _ := newTestServer(t, nil)
	c := login(t, h)
	if rr := authedReq(t, h, c, http.MethodGet, "/api/tesla/contacts", "", nil); rr.Code != 503 {
		t.Fatalf("no backend = %d", rr.Code)
	}
	rr := authedReq(t, h, c, http.MethodGet, "/api/tesla/typing", "", nil)
	if rr.Code != 200 || !strings.Contains(rr.Body.String(), `"typing":[]`) {
		t.Fatalf("typing without tracker: %d %s", rr.Code, rr.Body.String())
	}
}

func TestTypingTrackerExpiresAfter15s(t *testing.T) {
	now := time.Unix(1000, 0)
	tr := NewTypingTracker()
	tr.now = func() time.Time { return now }
	tr.Set("c1", "Ann", "+1555", true)
	tr.Set("c2", "Bob", "+1666", true)
	if got := tr.Active("c1"); len(got) != 1 || got[0].SenderName != "Ann" || got[0].ExpiresInMS != 15000 {
		t.Fatalf("active c1 = %+v", got)
	}
	if got := tr.Active(""); len(got) != 2 {
		t.Fatalf("active all = %+v", got)
	}
	tr.Set("c2", "Bob", "+1666", false)
	if got := tr.Active("c2"); len(got) != 0 {
		t.Fatalf("stopped typing still active: %+v", got)
	}
	now = now.Add(10 * time.Second)
	tr.Set("c1", "Ann", "+1555", true) // refresh
	now = now.Add(14 * time.Second)
	if got := tr.Active("c1"); len(got) != 1 {
		t.Fatalf("refreshed typing expired early: %+v", got)
	}
	now = now.Add(time.Second)
	if got := tr.Active("c1"); len(got) != 0 {
		t.Fatalf("typing not expired after 15s: %+v", got)
	}
	tr.Set("", "x", "y", true) // ignored
	if len(tr.state) != 0 {
		t.Fatalf("state not cleaned: %+v", tr.state)
	}
}

func TestTypingEndpoint(t *testing.T) {
	tr := NewTypingTracker()
	h, _, _ := newTestServer(t, func(_ *Config, d *Deps) { d.Typing = tr })
	c := login(t, h)
	tr.Set("c9", "Ann", "+1555", true)
	rr := authedReq(t, h, c, http.MethodGet, "/api/tesla/typing?conversation_id=c9", "", nil)
	var out struct {
		Typing []TypingState `json:"typing"`
		TTL    int64         `json:"ttl_ms"`
	}
	if err := json.Unmarshal(rr.Body.Bytes(), &out); err != nil || rr.Code != 200 {
		t.Fatalf("typing: %d %s", rr.Code, rr.Body.String())
	}
	if len(out.Typing) != 1 || out.Typing[0].ConversationID != "c9" || out.TTL != 15000 {
		t.Fatalf("typing = %+v", out)
	}
}
