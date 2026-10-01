package webapp

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
	convPins  map[string]bool
	archived  map[string]bool
	trashed   []string
}

func (f *fakeCar) ArchiveConversation(id string, archived bool) (any, error) {
	if id == "offline" {
		return nil, statusErr{503, "Google Messages isn't connected"}
	}
	if f.archived == nil {
		f.archived = map[string]bool{}
	}
	f.archived[id] = archived
	return map[string]any{"conversation_id": id, "archived": archived, "scope": "google"}, nil
}

func (f *fakeCar) MuteConversation(id string, muted bool) (any, error) {
	return map[string]any{"conversation_id": id, "muted": muted, "scope": "local"}, nil
}

func (f *fakeCar) MarkConversationRead(id string) (any, error) {
	return map[string]any{"conversation_id": id, "read": true}, nil
}

func (f *fakeCar) ConversationMeta(id string) (any, error) {
	return map[string]any{"conversation_id": id, "protocol": "RCS", "e2ee": true}, nil
}

func (f *fakeCar) TrashConversation(id string) (any, error) {
	f.trashed = append(f.trashed, id)
	return map[string]any{"conversation_id": id, "action": "trash", "scope": "google"}, nil
}

func (f *fakeCar) PinConversation(id string, pinned bool) (any, error) {
	if id == "missing" {
		return nil, statusErr{404, "conversation not found"}
	}
	if id == "full" {
		return nil, statusErr{409, "You can pin up to 20 conversations"}
	}
	if f.convPins == nil {
		f.convPins = map[string]bool{}
	}
	f.convPins[id] = pinned
	return map[string]any{"conversation_id": id, "pinned": pinned, "scope": "local"}, nil
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

func TestCarConversationPinEndpoint(t *testing.T) {
	car := &fakeCar{}
	h, _, _ := newTestServer(t, func(_ *Config, d *Deps) { d.Car = car })
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost, "http://car.example/api/app/conversations/pin", strings.NewReader(`{"conversation_id":"c1","pinned":true}`)))
	if rr.Code < 300 || car.convPins["c1"] {
		t.Fatalf("pin without login: %d", rr.Code)
	}
	c := login(t, h)
	const path = "/api/app/conversations/pin"
	if rr := authedReq(t, h, c, http.MethodGet, path, "", nil); rr.Code != 405 {
		t.Fatalf("GET: %d", rr.Code)
	}
	for body, want := range map[string]int{
		`{"conversation_id":"c1"}`:                    400,
		`{"pinned":true}`:                             400,
		`{"conversation_id":"missing","pinned":true}`: 404,
		`{"conversation_id":"full","pinned":true}`:    409,
		`{"conversation_id":"c1","pinned":true}`:      200,
	} {
		if rr := authedReq(t, h, c, http.MethodPost, path, "application/json", strings.NewReader(body)); rr.Code != want {
			t.Fatalf("%s: got %d want %d (%s)", body, rr.Code, want, rr.Body.String())
		}
	}
	if !car.convPins["c1"] {
		t.Fatal("c1 not pinned")
	}
	if rr := authedReq(t, h, c, http.MethodPost, path, "application/json", strings.NewReader(`{"conversation_id":"c1","pinned":false}`)); rr.Code != 200 || car.convPins["c1"] {
		t.Fatalf("unpin: %d", rr.Code)
	}
	req := httptest.NewRequest(http.MethodPost, "http://car.example"+path, strings.NewReader(`{"conversation_id":"c1","pinned":true}`))
	req.Header.Set("Origin", "https://evil.example")
	req.AddCookie(c)
	rr = httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	if rr.Code != http.StatusForbidden || car.convPins["c1"] {
		t.Fatalf("cross-origin pin: %d", rr.Code)
	}
}

func TestCarEndpointsRequireLogin(t *testing.T) {
	car := &fakeCar{}
	h, _, inner := newTestServer(t, func(_ *Config, d *Deps) { d.Car = car; d.Typing = NewTypingTracker() })
	for _, p := range []string{"/api/app/contacts", "/api/app/folder?name=archived", "/api/app/folder/messages?conversation_id=x", "/api/app/typing"} {
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "http://car.example"+p, nil))
		if rr.Code != http.StatusUnauthorized && rr.Code != http.StatusFound && rr.Code != http.StatusSeeOther {
			t.Fatalf("%s without login: %d", p, rr.Code)
		}
	}
	for _, p := range []string{"/api/app/messages/delete", "/api/app/conversations/start"} {
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
	req := httptest.NewRequest(http.MethodPost, "http://car.example/api/app/messages/delete", strings.NewReader(`{"message_id":"m1"}`))
	req.Header.Set("Origin", "https://evil.example")
	req.AddCookie(c)
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	if rr.Code != http.StatusForbidden || len(car.deleted) != 0 {
		t.Fatalf("cross-origin delete: %d deleted=%v", rr.Code, car.deleted)
	}
	if rr := authedReq(t, h, c, http.MethodGet, "/api/app/messages/delete", "", nil); rr.Code != http.StatusMethodNotAllowed {
		t.Fatalf("GET delete = %d", rr.Code)
	}
	rr = authedReq(t, h, c, http.MethodPost, "/api/app/messages/delete", "application/json", strings.NewReader(`{"message_id":"m1"}`))
	if rr.Code != 200 || len(car.deleted) != 1 || car.deleted[0] != "m1" || !strings.Contains(rr.Body.String(), `"scope":"google"`) {
		t.Fatalf("delete: %d %s %v", rr.Code, rr.Body.String(), car.deleted)
	}
	rr = authedReq(t, h, c, http.MethodPost, "/api/app/messages/delete", "application/json", strings.NewReader(`not json`))
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("bad json = %d", rr.Code)
	}
	car.deleteErr = statusErr{503, "Google Messages isn't connected"}
	rr = authedReq(t, h, c, http.MethodPost, "/api/app/messages/delete", "application/json", strings.NewReader(`{"message_id":"m2"}`))
	if rr.Code != 503 || !strings.Contains(rr.Body.String(), "isn't connected") {
		t.Fatalf("status passthrough: %d %s", rr.Code, rr.Body.String())
	}

	rr = authedReq(t, h, c, http.MethodPost, "/api/app/conversations/start", "application/json", strings.NewReader(`{"numbers":["+15550001111"],"group_name":"x"}`))
	if rr.Code != 200 || len(car.started) != 1 || car.started[0][0] != "+15550001111" || car.groupName != "x" {
		t.Fatalf("start: %d %s %+v", rr.Code, rr.Body.String(), car)
	}
}

func TestCarReadEndpoints(t *testing.T) {
	car := &fakeCar{}
	h, _, _ := newTestServer(t, func(_ *Config, d *Deps) { d.Car = car })
	c := login(t, h)
	rr := authedReq(t, h, c, http.MethodGet, "/api/app/contacts", "", nil)
	if rr.Code != 200 || !strings.Contains(rr.Body.String(), `"Ann"`) {
		t.Fatalf("contacts: %d %s", rr.Code, rr.Body.String())
	}
	rr = authedReq(t, h, c, http.MethodGet, "/api/app/folder?name=spam", "", nil)
	if rr.Code != 200 || car.folder != "spam" {
		t.Fatalf("folder: %d %s", rr.Code, rr.Body.String())
	}
	rr = authedReq(t, h, c, http.MethodGet, "/api/app/folder?name=nope", "", nil)
	if rr.Code != 400 {
		t.Fatalf("bad folder: %d", rr.Code)
	}
	rr = authedReq(t, h, c, http.MethodPost, "/api/app/folder?name=spam", "application/json", strings.NewReader(`{}`))
	if rr.Code != http.StatusMethodNotAllowed {
		t.Fatalf("POST folder must be rejected (read-only): %d", rr.Code)
	}
	rr = authedReq(t, h, c, http.MethodGet, "/api/app/folder/messages?conversation_id=a1", "", nil)
	if rr.Code != 503 || car.folderMsg != "a1" {
		t.Fatalf("folder messages: %d", rr.Code)
	}
}

func TestCarEndpointsWithoutBackend(t *testing.T) {
	h, _, _ := newTestServer(t, nil)
	c := login(t, h)
	if rr := authedReq(t, h, c, http.MethodGet, "/api/app/contacts", "", nil); rr.Code != 503 {
		t.Fatalf("no backend = %d", rr.Code)
	}
	rr := authedReq(t, h, c, http.MethodGet, "/api/app/typing", "", nil)
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
	rr := authedReq(t, h, c, http.MethodGet, "/api/app/typing?conversation_id=c9", "", nil)
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

func TestCarConversationArchiveAndTrashEndpoints(t *testing.T) {
	car := &fakeCar{}
	h, _, _ := newTestServer(t, func(_ *Config, d *Deps) { d.Car = car })
	for _, p := range []string{"/api/app/conversations/archive", "/api/app/conversations/trash"} {
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost, "http://car.example"+p, strings.NewReader(`{"conversation_id":"c1","archived":true}`)))
		if rr.Code != http.StatusUnauthorized {
			t.Fatalf("%s without login: %d", p, rr.Code)
		}
	}
	if len(car.trashed) != 0 || car.archived != nil {
		t.Fatal("unauthenticated request reached the backend")
	}
	c := login(t, h)
	for body, want := range map[string]int{
		`{"conversation_id":"c1"}`:                      400,
		`{"archived":true}`:                             400,
		`{"conversation_id":"offline","archived":true}`: 503,
		`{"conversation_id":"c1","archived":true}`:      200,
	} {
		if rr := authedReq(t, h, c, http.MethodPost, "/api/app/conversations/archive", "application/json", strings.NewReader(body)); rr.Code != want {
			t.Fatalf("archive %s: got %d want %d", body, rr.Code, want)
		}
	}
	if !car.archived["c1"] {
		t.Fatal("c1 not archived")
	}
	if rr := authedReq(t, h, c, http.MethodPost, "/api/app/conversations/trash", "application/json", strings.NewReader(`{}`)); rr.Code != 400 {
		t.Fatalf("trash without id: %d", rr.Code)
	}
	if rr := authedReq(t, h, c, http.MethodGet, "/api/app/conversations/trash", "", nil); rr.Code != 405 {
		t.Fatalf("GET trash: %d", rr.Code)
	}
	if rr := authedReq(t, h, c, http.MethodPost, "/api/app/conversations/trash", "application/json", strings.NewReader(`{"conversation_id":"c1"}`)); rr.Code != 200 || len(car.trashed) != 1 {
		t.Fatalf("trash: %d %v", rr.Code, car.trashed)
	}
	// Cross-origin writes are refused by the gate.
	req := httptest.NewRequest(http.MethodPost, "http://car.example/api/app/conversations/trash", strings.NewReader(`{"conversation_id":"c2"}`))
	req.AddCookie(c)
	req.Header.Set("Origin", "https://evil.example")
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	if rr.Code != http.StatusForbidden || len(car.trashed) != 1 {
		t.Fatalf("cross-origin trash: %d", rr.Code)
	}
}

func TestCarMuteReadMetaEndpoints(t *testing.T) {
	h, _, _ := newTestServer(t, func(_ *Config, d *Deps) { d.Car = &fakeCar{} })
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost, "http://car.example/api/app/conversations/mute", strings.NewReader(`{"conversation_id":"c1","muted":true}`)))
	if rr.Code != http.StatusUnauthorized {
		t.Fatalf("mute without login: %d", rr.Code)
	}
	c := login(t, h)
	cases := []struct {
		method, path, body string
		want               int
	}{
		{"POST", "/api/app/conversations/mute", `{"conversation_id":"c1"}`, 400},
		{"POST", "/api/app/conversations/mute", `{"conversation_id":"c1","muted":true}`, 200},
		{"POST", "/api/app/conversations/read", `{}`, 400},
		{"POST", "/api/app/conversations/read", `{"conversation_id":"c1"}`, 200},
		{"GET", "/api/app/conversations/meta?conversation_id=c1", "", 200},
		{"GET", "/api/app/conversations/meta", "", 400},
		{"POST", "/api/app/conversations/meta?conversation_id=c1", "", 405},
	}
	for _, tc := range cases {
		var body *strings.Reader
		if tc.body != "" {
			body = strings.NewReader(tc.body)
		} else {
			body = strings.NewReader("")
		}
		if rr := authedReq(t, h, c, tc.method, tc.path, "application/json", body); rr.Code != tc.want {
			t.Fatalf("%s %s %s: %d want %d", tc.method, tc.path, tc.body, rr.Code, tc.want)
		}
	}
}
