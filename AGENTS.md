# Agent Instructions

## iOS build/test workflow

- For `mobile_clients/ios`, use the `xcode-mcp` MCP server for Xcode builds, test discovery, and test runs.
- Once per agent session, before the first iOS build/test action, connect to `xcode-mcp` and list/query its tools. Treat that single connect/list result as the session's tool inventory.
- After that first query, call the specific `xcode-mcp_*` tools directly as needed; do not re-query the server's tool list in the same session unless the user asks.
