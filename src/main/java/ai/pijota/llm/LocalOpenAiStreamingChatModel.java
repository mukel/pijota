package ai.pijota.llm;

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
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class LocalOpenAiStreamingChatModel implements StreamingChatModel {
    private final HttpClient client = HttpClient.newHttpClient();
    private final String url;
    private final String apiKey;
    private final String modelName;
    private final ThinkingLevel thinkingLevel;

    LocalOpenAiStreamingChatModel(String baseUrl, String apiKey, String modelName, ThinkingLevel thinkingLevel) {
        this.url = normalizeBaseUrl(baseUrl) + "/chat/completions";
        this.apiKey = apiKey != null ? apiKey : "";
        this.modelName = modelName;
        this.thinkingLevel = thinkingLevel != null ? thinkingLevel : ThinkingLevel.OFF;
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        try {
            var httpRequest = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(request).toString(), StandardCharsets.UTF_8))
                .build();
            client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                .thenAccept(response -> {
                    try {
                        streamResponse(response, handler);
                    } catch (Throwable e) {
                        handler.onError(e);
                    }
                })
                .exceptionally(e -> { handler.onError(e); return null; });
        } catch (Throwable e) {
            handler.onError(e);
        }
    }

    private ObjectNode requestBody(ChatRequest request) {
        var body = Util.MAPPER.createObjectNode();
        body.put("model", request.modelName() != null ? request.modelName() : modelName);
        body.put("stream", true);
        body.set("messages", messages(request.messages()));
        boolean enableThinking = thinkingLevel != ThinkingLevel.OFF;
        body.put("enable_thinking", enableThinking);
        body.set("chat_template_kwargs", Util.MAPPER.createObjectNode()
            .put("enable_thinking", enableThinking));
        if (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
            body.set("tools", tools(request.toolSpecifications()));
            body.put("tool_choice", "auto");
        }
        return body;
    }

    private ArrayNode messages(List<ChatMessage> messages) {
        var out = Util.MAPPER.createArrayNode();
        for (var message : messages) {
            if (message instanceof SystemMessage system) {
                out.add(message("system", system.text()));
            } else if (message instanceof UserMessage user) {
                out.add(message("user", userText(user)));
            } else if (message instanceof AiMessage ai) {
                var item = message("assistant", ai.text() != null ? ai.text() : "");
                if (ai.toolExecutionRequests() != null && !ai.toolExecutionRequests().isEmpty()) {
                    var toolCalls = Util.MAPPER.createArrayNode();
                    for (var call : ai.toolExecutionRequests()) toolCalls.add(toolCall(call));
                    item.set("tool_calls", toolCalls);
                }
                out.add(item);
            } else if (message instanceof ToolExecutionResultMessage tool) {
                var item = message("tool", tool.text() != null ? tool.text() : "");
                item.put("tool_call_id", tool.id());
                out.add(item);
            }
        }
        return out;
    }

    private static ObjectNode message(String role, String content) {
        var item = Util.MAPPER.createObjectNode();
        item.put("role", role);
        item.put("content", content != null ? content : "");
        return item;
    }

    private static ObjectNode toolCall(ToolExecutionRequest call) {
        var item = Util.MAPPER.createObjectNode();
        item.put("id", call.id());
        item.put("type", "function");
        var fn = Util.MAPPER.createObjectNode();
        fn.put("name", call.name());
        fn.put("arguments", call.arguments() != null ? call.arguments() : "{}");
        item.set("function", fn);
        return item;
    }

    private static String userText(UserMessage user) {
        if (user.hasSingleText()) return user.singleText();
        return user.contents().stream()
            .map(Object::toString)
            .reduce((a, b) -> a + "\n" + b)
            .orElse("");
    }

    private ArrayNode tools(List<ToolSpecification> specs) {
        var tools = Util.MAPPER.createArrayNode();
        for (var spec : specs) {
            try {
                JsonNode json = Util.MAPPER.readTree(spec.toJson());
                var tool = Util.MAPPER.createObjectNode();
                tool.put("type", "function");
                tool.set("function", json);
                tools.add(tool);
            } catch (Exception ignored) {}
        }
        return tools;
    }

    private void streamResponse(HttpResponse<java.io.InputStream> response,
                                StreamingChatResponseHandler handler) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + ": " + new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
        }
        var content = new StringBuilder();
        var thinking = new StringBuilder();
        var toolCalls = new HashMap<Integer, ToolCallBuilder>();
        TokenUsage usage = null;
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty()) continue;
                if ("[DONE]".equals(data)) break;
                JsonNode event = Util.MAPPER.readTree(data);
                usage = usage(event, usage);
                var choices = event.get("choices");
                if (choices == null || !choices.isArray() || choices.isEmpty()) continue;
                JsonNode delta = choices.get(0).get("delta");
                if (delta == null) continue;
                String thought = text(delta.get("reasoning_content"));
                if (!thought.isEmpty()) {
                    thinking.append(thought);
                    handler.onPartialThinking(new PartialThinking(thought), null);
                }
                String text = text(delta.get("content"));
                if (!text.isEmpty()) {
                    content.append(text);
                    handler.onPartialResponse(new PartialResponse(content.toString()), null);
                }
                collectToolCalls(delta.get("tool_calls"), toolCalls);
            }
        }

        var requests = toolCalls.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getValue().build())
            .toList();
        var ai = AiMessage.builder()
            .text(content.toString())
            .thinking(thinking.isEmpty() ? null : thinking.toString())
            .toolExecutionRequests(requests.isEmpty() ? null : requests)
            .build();
        var builder = ChatResponse.builder().aiMessage(ai);
        if (usage != null) builder.tokenUsage(usage);
        handler.onCompleteResponse(builder.build());
    }

    private static String text(JsonNode node) {
        return node != null && !node.isNull() ? node.asText("") : "";
    }

    private static void collectToolCalls(JsonNode calls, Map<Integer, ToolCallBuilder> out) {
        if (calls == null || !calls.isArray()) return;
        for (var call : calls) {
            int index = call.path("index").asInt(out.size());
            var b = out.computeIfAbsent(index, _ -> new ToolCallBuilder());
            if (call.hasNonNull("id")) b.id = call.get("id").asText();
            var fn = call.get("function");
            if (fn != null) {
                if (fn.hasNonNull("name")) b.name = fn.get("name").asText();
                if (fn.hasNonNull("arguments")) b.arguments.append(fn.get("arguments").asText());
            }
        }
    }

    private static TokenUsage usage(JsonNode event, TokenUsage current) {
        JsonNode usage = event.get("usage");
        if (usage == null || usage.isNull()) return current;
        Integer prompt = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null;
        Integer completion = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : null;
        Integer total = usage.has("total_tokens") ? usage.get("total_tokens").asInt() : null;
        return new TokenUsage(prompt, completion, total);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl == null || baseUrl.isBlank() ? "http://localhost:17325/v1" : baseUrl;
        return normalized.replaceAll("/+$", "");
    }

    private static final class ToolCallBuilder {
        String id;
        String name;
        StringBuilder arguments = new StringBuilder();

        ToolExecutionRequest build() {
            return ToolExecutionRequest.builder()
                .id(id)
                .name(name)
                .arguments(arguments.isEmpty() ? "{}" : arguments.toString())
                .build();
        }
    }
}
