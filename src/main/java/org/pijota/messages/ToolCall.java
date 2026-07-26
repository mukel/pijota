package org.pijota.messages;

/** A tool invocation embedded in an assistant message: unique id, tool name, and JSON-encoded arguments string. */
public record ToolCall(String id, String name, String arguments) {
}
