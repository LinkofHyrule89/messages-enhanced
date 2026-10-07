package cmd

import (
	"context"
	"strings"
	"testing"

	"github.com/mark3labs/mcp-go/mcp"
	"github.com/maxghenis/openmessage/internal/app"
	"github.com/maxghenis/openmessage/internal/tools"
	"github.com/rs/zerolog"
)

func TestRemoteMCPToolsAllowlistAndConfirm(t *testing.T) {
	t.Setenv("OPENMESSAGES_DATA_DIR", t.TempDir())
	a, err := app.New(zerolog.Nop())
	if err != nil {
		t.Fatalf("app.New: %v", err)
	}
	defer a.Close()
	srv := newRemoteMCPServer(a, "test", tools.Options{})
	got := srv.ListTools()
	for name := range got {
		if !remoteMCPTools[name] {
			t.Errorf("tool %q exposed remotely but not allowlisted", name)
		}
	}
	for _, name := range []string{"list_conversations", "get_conversation", "send_to_conversation", "send_message"} {
		if got[name] == nil {
			t.Errorf("tool %q missing", name)
		}
	}
	for _, banned := range []string{"download_media", "send_media_to_conversation", "import_messages"} {
		if got[banned] != nil {
			t.Errorf("tool %q must not be exposed remotely", banned)
		}
	}
	for _, name := range remoteMCPConfirmTools {
		st := got[name]
		if st == nil {
			t.Fatalf("%s missing", name)
		}
		if !strings.Contains(st.Tool.Description, "confirm=true") {
			t.Errorf("%s description lacks the read-back instruction", name)
		}
		if _, ok := st.Tool.InputSchema.Properties["confirm"]; !ok {
			t.Errorf("%s has no confirm property", name)
		}
		req := false
		for _, r := range st.Tool.InputSchema.Required {
			req = req || r == "confirm"
		}
		if !req {
			t.Errorf("%s: confirm not required", name)
		}
		for _, args := range []map[string]any{{}, {"confirm": false}} {
			var cr mcp.CallToolRequest
			cr.Params.Name = name
			cr.Params.Arguments = args
			res, err := st.Handler(context.Background(), cr)
			if err != nil || res == nil || !res.IsError {
				t.Fatalf("%s with %v: res=%+v err=%v (must refuse)", name, args, res, err)
			}
			txt := res.Content[0].(mcp.TextContent).Text
			if !strings.Contains(txt, "Not sent") {
				t.Fatalf("%s refusal text = %q", name, txt)
			}
		}
	}
}
