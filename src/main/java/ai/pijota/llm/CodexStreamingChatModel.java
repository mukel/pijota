package ai.pijota.llm;

import ai.pijota.config.OAuthTokenStore;
import ai.pijota.util.Util;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class CodexStreamingChatModel implements StreamingChatModel {
    private static final Logger log = LoggerFactory.getLogger(CodexStreamingChatModel.class);
    private static final String DEFAULT_BASE_URL = "https://chatgpt.com/backend-api";

    private final HttpClient client;
    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final String providerName;
    private final ThinkingLevel thinkingLevel;

    // Cancellation: the in-flight SSE stream, closed by abort() to unblock the reading virtual thread immediately
    // (langchain4j's StreamingHandle does not reach this custom model, so we cancel it ourselves).
    private final AtomicReference<Closeable> inflight = new AtomicReference<>();
    private volatile boolean aborted;

    CodexStreamingChatModel(String baseUrl, String apiKey, String modelName, String providerName,
                                    ThinkingLevel thinkingLevel) {
        this(HttpClient.newHttpClient(), baseUrl, apiKey, modelName, providerName, thinkingLevel);
    }

    /** Test seam: inject the HTTP client so streaming + cancellation can be exercised without a live endpoint. */
    CodexStreamingChatModel(HttpClient client, String baseUrl, String apiKey, String modelName, String providerName,
                                    ThinkingLevel thinkingLevel) {
        this.client = client;
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl : DEFAULT_BASE_URL;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.providerName = providerName;
        this.thinkingLevel = thinkingLevel != null ? thinkingLevel : ThinkingLevel.OFF;
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        aborted = false;
        Thread.startVirtualThread(() -> {
            try {
                var body = requestBody(request);
                var httpRequest = HttpRequest.newBuilder().uri(URI.create(resolveCodexUrl(baseUrl)))
                                                .timeout(Duration.ofSeconds(300))
                                                .header("Authorization", "Bearer " + apiKey)
                                                .header("chatgpt-account-id", accountId())
                                                .header("originator", "pijota").header("User-Agent", userAgent())
                                                .header("OpenAI-Beta", "responses=experimental")
                                                .header("accept", "text/event-stream")
                                                .header("content-type", "application/json")
                                                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
                var response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                inflight.set(response.body()); // so abort() can close it and unblock the read
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String error = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    throw new IOException("Codex request failed: HTTP " + response.statusCode() + " - " + error);
                }
                streamResponse(response, handler);
            } catch (Throwable e) {
                handler.onError(e);
            } finally {
                inflight.set(null);
            }
        });
    }

    /** Cancel an in-flight stream (ESC / turn abort). Closes the SSE stream so the reading thread stops immediately. */
    void abort() {
        aborted = true;
        var stream = inflight.getAndSet(null);
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // closing solely to interrupt the blocking read; nothing actionable if it fails
            }
        }
    }

    private ObjectNode requestBody(ChatRequest request) {
        return buildRequestBody(request, modelName, thinkingLevel);
    }

    static ObjectNode buildRequestBody(ChatRequest request, String defaultModel, ThinkingLevel level) {
        var body = Util.MAPPER.createObjectNode();
        body.put("model", request.modelName() != null ? request.modelName() : defaultModel);
        body.put("store", false);
        body.put("stream", true);
        body.put("instructions", systemPrompt(request.messages()));
        body.set("input", inputMessages(request.messages()));
        body.set("text", Util.MAPPER.createObjectNode().put("verbosity", "low"));
        body.set("include", Util.MAPPER.createArrayNode().add("reasoning.encrypted_content"));
        body.put("tool_choice", "auto");
        body.put("parallel_tool_calls", true);
        if (level != ThinkingLevel.OFF) {
            body.set("reasoning", Util.MAPPER.createObjectNode().put("effort", codexThinkingLevel(level)).put("summary",
                                            "auto"));
        }
        if (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
            body.set("tools", tools(request.toolSpecifications()));
        }
        return body;
    }

    static String systemPrompt(List<ChatMessage> messages) {
        for (var message : messages) {
            if (message instanceof SystemMessage system)
                return system.text();
        }
        return "You are a helpful assistant.";
    }

    static ArrayNode inputMessages(List<ChatMessage> messages) {
        var input = Util.MAPPER.createArrayNode();
        for (var message : messages) {
            if (message instanceof SystemMessage)
                continue;
            if (message instanceof UserMessage user) {
                input.add(messageItem("message", "user", userText(user)));
            } else if (message instanceof AiMessage ai) {
                if (ai.text() != null && !ai.text().isBlank()) {
                    input.add(messageItem("message", "assistant", ai.text()));
                }
                if (ai.toolExecutionRequests() != null) {
                    for (var tool : ai.toolExecutionRequests()) {
                        var item = Util.MAPPER.createObjectNode();
                        item.put("type", "function_call");
                        item.put("call_id", tool.id());
                        item.put("name", tool.name());
                        item.put("arguments", tool.arguments() != null ? tool.arguments() : "{}");
                        input.add(item);
                    }
                }
            } else if (message instanceof ToolExecutionResultMessage toolResult) {
                var item = Util.MAPPER.createObjectNode();
                item.put("type", "function_call_output");
                item.put("call_id", toolResult.id());
                item.put("output", toolResult.text() != null ? toolResult.text() : "");
                input.add(item);
            }
        }
        return input;
    }

    static String userText(UserMessage user) {
        if (user.hasSingleText())
            return user.singleText();
        return user.contents().stream().map(Object::toString).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    static ObjectNode messageItem(String type, String role, String text) {
        var item = Util.MAPPER.createObjectNode();
        item.put("type", type);
        item.put("role", role);
        item.set("content", Util.MAPPER.createArrayNode().add(Util.MAPPER.createObjectNode()
                                        .put("type", role.equals("assistant") ? "output_text" : "input_text")
                                        .put("text", text != null ? text : "")));
        return item;
    }

    static ArrayNode tools(List<ToolSpecification> specs) {
        var tools = Util.MAPPER.createArrayNode();
        for (var spec : specs) {
            var tool = Util.MAPPER.createObjectNode();
            tool.put("type", "function");
            tool.put("name", spec.name());
            if (spec.description() != null)
                tool.put("description", spec.description());
            try {
                JsonNode json = Util.MAPPER.readTree(spec.toJson());
                if (json.has("parameters"))
                    tool.set("parameters", json.get("parameters"));
            } catch (Exception ignored) {}
            tools.add(tool);
        }
        return tools;
    }

    private void streamResponse(HttpResponse<InputStream> response, StreamingChatResponseHandler handler)
                                    throws IOException {
        var text = new StringBuilder();
        var toolCalls = new ArrayList<ToolExecutionRequest>();
        var seenToolCalls = new HashSet<String>();
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (aborted)
                    break; // turn was cancelled — stop consuming the stream
                if (!line.startsWith("data:"))
                    continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || data.equals("[DONE]"))
                    continue;
                String delta = processSseLine(data, text, toolCalls, seenToolCalls);
                if (delta != null) {
                    handler.onPartialResponse(new PartialResponse(text.toString()), null);
                }
            }
        }
        var ai = toolCalls.isEmpty() ? AiMessage.from(text.toString()) : AiMessage.from(text.toString(), toolCalls);
        handler.onCompleteResponse(ChatResponse.builder().aiMessage(ai).tokenUsage(new TokenUsage()).build());
    }

    static String processSseLine(String data, StringBuilder text, List<ToolExecutionRequest> toolCalls,
                                    HashSet<String> seenToolCalls) throws IOException {
        JsonNode event = Util.MAPPER.readTree(data);
        String type = event.has("type") ? event.get("type").asText() : "";
        if (type.equals("response.output_text.delta") && event.has("delta")) {
            String delta = event.get("delta").asText();
            text.append(delta);
            return delta;
        } else if (type.equals("response.output_item.done") && event.has("item")) {
            addToolCall(event.get("item"), toolCalls, seenToolCalls);
        } else if (type.equals("response.completed") && event.has("response")) {
            collectCompleted(event.get("response"), toolCalls, seenToolCalls);
        } else if (type.equals("response.failed")) {
            throw new IOException(event.toString());
        }
        return null;
    }

    static void collectCompleted(JsonNode response, List<ToolExecutionRequest> toolCalls,
                                    HashSet<String> seenToolCalls) {
        var output = response.get("output");
        if (output == null || !output.isArray())
            return;
        for (var item : output)
            addToolCall(item, toolCalls, seenToolCalls);
    }

    static void addToolCall(JsonNode item, List<ToolExecutionRequest> toolCalls, HashSet<String> seenToolCalls) {
        if (item == null || !"function_call".equals(item.path("type").asText()))
            return;
        String callId = item.path("call_id").asText(item.path("id").asText());
        String name = item.path("name").asText();
        if (callId.isBlank() || name.isBlank())
            return;
        if (!seenToolCalls.add(callId))
            return;
        toolCalls.add(ToolExecutionRequest.builder().id(callId).name(name)
                                        .arguments(item.path("arguments").asText("{}")).build());
    }

    private String accountId() {
        String fromStore = new OAuthTokenStore().getAccountId(providerName);
        if (fromStore != null && !fromStore.isBlank())
            return fromStore;
        String fromToken = extractAccountId(apiKey);
        if (fromToken != null && !fromToken.isBlank())
            return fromToken;
        throw new IllegalStateException("missing ChatGPT account id for " + providerName);
    }

    static String extractAccountId(String token) {
        try {
            var parts = token.split("\\.");
            if (parts.length != 3)
                return null;
            // Parse via String, not byte[]: Jackson's byte[] encoding-detection path returns null/throws under a
            // native image (Jackson is initialized at build time), whereas the char-based readTree(String) works.
            String json = new String(Base64.getUrlDecoder().decode(padBase64(parts[1])), StandardCharsets.UTF_8);
            var node = Util.MAPPER.readTree(json);
            var auth = node.get("https://api.openai.com/auth");
            return auth != null && auth.has("chatgpt_account_id") ? auth.get("chatgpt_account_id").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String padBase64(String value) {
        int pad = (4 - value.length() % 4) % 4;
        return value + "=".repeat(pad);
    }

    static String resolveCodexUrl(String baseUrl) {
        String normalized = (baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl).replaceAll("/+$", "");
        if (normalized.endsWith("/codex/responses"))
            return normalized;
        if (normalized.endsWith("/codex"))
            return normalized + "/responses";
        return normalized + "/codex/responses";
    }

    static String codexThinkingLevel(ThinkingLevel thinkingLevel) {
        if (thinkingLevel == null)
            return "none";
        return switch (thinkingLevel) {
            case MINIMAL -> "low";
            case LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH -> "high";
            case OFF -> "none";
        };
    }

    static String userAgent() {
        return "pijota (" + System.getProperty("os.name") + "; " + System.getProperty("os.arch") + ")";
    }
}
