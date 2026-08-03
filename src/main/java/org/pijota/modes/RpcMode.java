package org.pijota.modes;

import org.pijota.messages.Message;
import org.pijota.util.Util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class RpcMode {

    public static void run(Function<String, CompletableFuture<List<Message>>> handler) {
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var writer = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);

        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank())
                    continue;

                var request = parseRpcRequest(line);
                if (request == null) {
                    // -32700, not silence. A client whose serialiser has a bug got no answer at all and sat waiting
                    // for a response that was never coming; the protocol has a code for exactly this.
                    writeError(writer, null, -32700, "Parse error: not a JSON-RPC request");
                    writer.flush();
                    continue;
                }

                String method = request.method();
                String id = request.id();

                switch (method) {
                    case "prompt" -> {
                        writeNotification(writer, "prompt_start", "{\"id\":" + jsonId(id) + "}");
                        writer.flush();
                        try {
                            var result = handler.apply(request.params()).get();
                            writeNotification(writer, "prompt_end", "{\"id\":" + jsonId(id) + "}");
                            writeResponse(writer, id, "\"" + Util.escapeJson(responseContent(result)) + "\"");
                        } catch (Exception e) {
                            writeNotification(writer, "prompt_error", "{\"id\":" + jsonId(id) + ",\"message\":\""
                                                            + Util.escapeJson(e.getMessage()) + "\"}");
                            throw e;
                        }
                    }
                    case "ping" -> writeResponse(writer, id, "\"pong\"");
                    case "shutdown" -> {
                        writeResponse(writer, id, "null");
                        writer.flush();
                        return;
                    }
                    default -> writeError(writer, id, -32601, "Method not found: " + method);
                }
                writer.flush();
            }
        } catch (Exception e) {
            try {
                writeError(writer, null, -32603, e.getMessage());
                writer.flush();
            } catch (IOException ignored) {}
        }
    }

    static RpcRequest parseRpcRequest(String line) {
        try {
            var node = Util.MAPPER.readTree(line);
            String method = node.get("method").asText();
            String id = node.has("id") ? node.get("id").asText() : null;
            String params = node.has("params") ? node.get("params").get("prompt").asText() : "";
            return new RpcRequest(method, id, params);
        } catch (Exception e) {
            return null;
        }
    }

    static String responseContent(List<Message> messages) {
        var sb = new StringBuilder();
        for (var msg : messages) {
            if (msg instanceof Message.Assistant a && a.content() != null && !a.content().isBlank()) {
                sb.append(a.content());
            }
        }
        return sb.toString();
    }

    private static void writeResponse(Writer writer, String id, String result) throws IOException {
        writer.write("{\"jsonrpc\":\"2.0\",\"id\":" + jsonId(id) + ",\"result\":" + result + "}\n");
    }

    private static void writeNotification(Writer writer, String method, String params) throws IOException {
        writer.write("{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":" + params + "}\n");
    }

    private static void writeError(Writer writer, String id, int code, String message) throws IOException {
        writer.write("{\"jsonrpc\":\"2.0\",\"id\":" + jsonId(id) + ",\"error\":{\"code\":" + code + ",\"message\":\""
                                        + Util.escapeJson(message) + "\"}}\n");
    }

    private static String jsonId(String id) {
        if (id == null)
            return "null";
        try {
            Double.parseDouble(id);
            return id;
        } catch (NumberFormatException e) {
            return "\"" + Util.escapeJson(id) + "\"";
        }
    }

    // package-visible for testing
    record RpcRequest(String method, String id, String params) {
    }
}
