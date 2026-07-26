package org.pijota.extensions.mcp;

import java.util.Map;

/**
 * A tool as advertised by an MCP server's {@code tools/list}: its server-side name, human description, and JSON-Schema
 * {@code inputSchema}. {@link McpToolBridge} turns this into a pijota {@code Tool.ToolDefinition}.
 */
record McpToolDescriptor(String name, String description, Map<String, Object> inputSchema) {
}
