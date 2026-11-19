package tools

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/mark3labs/mcp-go/mcp"

	"github.com/maxghenis/openmessage/internal/db"
)

func TestBoundedLimitRejectsUnboundedSQL(t *testing.T) {
	if got := boundedLimit(-1, 20, 200); got != 20 {
		t.Fatalf("negative limit = %d, want default 20", got)
	}
	if got := boundedLimit(1_000_000, 20, 200); got != 200 {
		t.Fatalf("huge limit = %d, want 200", got)
	}
	if got := boundedLimit(15, 20, 200); got != 15 {
		t.Fatalf("in-range limit = %d, want 15", got)
	}
}

func TestReactToPlaceholderDoesNotSend(t *testing.T) {
	a := testApp(t)
	handler := reactToMessageHandler(a)
	req := mcp.CallToolRequest{}
	req.Params.Arguments = map[string]any{
		"conversation_id": "4",
		"message_id":      "tmp_264794425382",
		"emoji":           "👍",
		"confirm":         true,
	}
	result, err := handler(context.Background(), req)
	if err != nil {
		t.Fatal(err)
	}
	if !result.IsError {
		t.Fatal("reacting to a sending placeholder should be an error")
	}
	text := result.Content[0].(mcp.TextContent).Text
	if !strings.Contains(text, "still sending") {
		t.Fatalf("error = %q", text)
	}
}

func TestDraftIDsDifferWithinTheSameMillisecond(t *testing.T) {
	a := testApp(t)
	handler := draftMessageHandler(a)
	for i := 0; i < 2; i++ {
		req := mcp.CallToolRequest{}
		req.Params.Arguments = map[string]any{
			"conversation_id": "4",
			"message":         "draft text",
		}
		result, err := handler(context.Background(), req)
		if err != nil || result.IsError {
			t.Fatalf("draft %d: %v %+v", i, err, result)
		}
	}
	drafts, err := a.Store.ListDrafts("4")
	if err != nil {
		t.Fatal(err)
	}
	if len(drafts) != 2 || drafts[0].DraftID == drafts[1].DraftID {
		t.Fatalf("drafts = %+v", drafts)
	}
}

func TestDeduplicateMessagesPrefersRealEcho(t *testing.T) {
	now := time.Now().UnixMilli()
	got := deduplicateMessages([]*db.Message{
		{MessageID: "tmp_264794425382", Body: "group hi", TimestampMS: now, IsFromMe: true},
		{MessageID: "200342", Body: "group hi", TimestampMS: now + 307, IsFromMe: true},
	})
	if len(got) != 1 || got[0].MessageID != "200342" {
		t.Fatalf("deduped = %+v", got)
	}
}
