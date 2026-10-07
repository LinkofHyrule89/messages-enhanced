package cmd

// Remote MCP connector for an assistant (Messages Enhanced): the same
// OpenMessage MCP tools, served over Streamable HTTP at /mcp behind a
// bearer token (see webapp/mcp.go), limited to messaging tools. Tools that
// read or write files on the server (send media from a server path,
// download media, import, story/viz renders) or edit stored data are not
// exposed remotely.

import (
	"context"
	"net/http"
	"sort"

	"github.com/mark3labs/mcp-go/mcp"
	mcpserver "github.com/mark3labs/mcp-go/server"
	"github.com/maxghenis/openmessage/internal/app"
	"github.com/maxghenis/openmessage/internal/tools"
)

// remoteMCPTools is the allowlist; anything else registered is removed.
var remoteMCPTools = map[string]bool{
	"get_status":                true,
	"list_conversations":        true,
	"get_conversation":          true,
	"get_messages":              true,
	"search_messages":           true,
	"get_person_messages":       true,
	"get_person_messages_range": true,
	"list_contacts":             true,
	"resolve_contact_routes":    true,
	"send_message":              true, // to a phone number (starts a 1:1 chat if needed)
	"send_to_conversation":      true, // to an existing conversation (1:1 or group)
	"send_group_message":        true, // new group from phone numbers
	"react_to_message":          true,
	"draft_message":             true,
}

func newRemoteMCPServer(a *app.App, version string, options tools.Options) *mcpserver.MCPServer {
	srv := mcpserver.NewMCPServer("messages-enhanced", version,
		mcpserver.WithToolCapabilities(true),
		mcpserver.WithInstructions("Read and send SMS/RCS messages through the user's own Messages Enhanced (Google Messages) server. "+
			"Find people with list_contacts or resolve_contact_routes, find chats with list_conversations, read with get_conversation, "+
			"send with send_to_conversation (existing 1:1 or group chat) or send_message (phone number). Messages are sent as the user: "+
			"only send what the user asked for."))
	tools.RegisterWithOptions(srv, a, options)
	var drop []string
	for name := range srv.ListTools() {
		if !remoteMCPTools[name] {
			drop = append(drop, name)
		}
	}
	sort.Strings(drop)
	srv.DeleteTools(drop...)
	for _, name := range remoteMCPConfirmTools {
		requireConfirm(srv, name)
	}
	return srv
}

// Tools that send something visible to other people. A voice assistant may
// call tools without showing a draft, so these require confirm=true, and
// their description tells the model to read the recipient and the message
// back and get a clear yes from the user first.
var remoteMCPConfirmTools = []string{"send_message", "send_to_conversation", "send_group_message", "react_to_message"}

const confirmInstruction = " IMPORTANT: this sends as the user to real people. Before calling it, read the recipient " +
	"(the person's name or number, or the group/chat name) and the exact message text back to the user, and ask them to confirm. " +
	"Only after they clearly say yes in this conversation, call it with confirm=true. Never set confirm=true on your own, " +
	"and if they change anything, read it back again first."

func requireConfirm(srv *mcpserver.MCPServer, name string) {
	st := srv.GetTool(name)
	if st == nil {
		return
	}
	tool, handler := st.Tool, st.Handler
	tool.Description += confirmInstruction
	props := map[string]any{}
	for k, v := range tool.InputSchema.Properties {
		props[k] = v
	}
	props["confirm"] = map[string]any{
		"type":        "boolean",
		"description": "Must be true, and only after the user heard the recipient and message read back and said yes.",
	}
	tool.InputSchema.Properties = props
	tool.InputSchema.Required = append(append([]string{}, tool.InputSchema.Required...), "confirm")
	srv.DeleteTools(name)
	srv.AddTool(tool, func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
		if !req.GetBool("confirm", false) {
			return mcp.NewToolResultError("Not sent: confirmation required. Read the recipient and the exact message back to the user, " +
				"ask them to confirm, and only after they say yes call this again with confirm=true."), nil
		}
		return handler(ctx, req)
	})
}

func newRemoteMCPHandler(a *app.App, version string, options tools.Options) http.Handler {
	// Stateless: no session to lose when the server restarts on a deploy.
	return mcpserver.NewStreamableHTTPServer(newRemoteMCPServer(a, version, options),
		mcpserver.WithEndpointPath("/mcp"), mcpserver.WithStateLess(true))
}
