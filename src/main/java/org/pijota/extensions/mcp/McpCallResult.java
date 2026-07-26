package org.pijota.extensions.mcp;

/**
 * The outcome of an MCP {@code tools/call}: the server's content already flattened to text, and whether the server
 * flagged it as an error ({@code isError}). This is the MCP-domain value {@link McpClient} returns — the adapter
 * ({@link McpToolBridge}) maps it onto a pijota {@code Tool.ToolResult}. Keeping it free of JSON/Jackson types is what
 * keeps the client's surface opaque.
 */
record McpCallResult(String text, boolean isError) {
}
