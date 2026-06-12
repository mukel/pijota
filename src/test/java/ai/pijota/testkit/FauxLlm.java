package ai.pijota.testkit;

import ai.pijota.llm.LlmClient;
import ai.pijota.llm.StreamResult;
import ai.pijota.messages.Message;
import ai.pijota.messages.ToolCall;
import ai.pijota.messages.Usage;
import ai.pijota.tools.Tool;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public final class FauxLlm implements LlmClient {
    private final Queue<Response> responses = new ArrayDeque<>();
    private final List<Request> requests = new ArrayList<>();
    private boolean streamsWithTools = true;
    private boolean aborted;
    private String provider = "faux";
    private String model = "faux-model";

    public FauxLlm streamsWithTools(boolean value) {
        this.streamsWithTools = value;
        return this;
    }

    public FauxLlm enqueue(Response response) {
        responses.add(response);
        return this;
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public boolean aborted() {
        return aborted;
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public String apiKey() {
        return "faux-key";
    }

    @Override
    public boolean streamsWithTools() {
        return streamsWithTools;
    }

    @Override
    public void abort() {
        aborted = true;
    }

    @Override
    public CompletableFuture<StreamResult> streamMessages(List<Message> messages, List<Tool> tools,
                                    BiConsumer<String, String> onPartial) {
        requests.add(new Request(List.copyOf(messages), List.copyOf(tools)));
        var response = responses.poll();
        if (response == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No faux response queued"));
        }

        if (response.blockUntilAborted()) {
            var future = new CompletableFuture<StreamResult>();
            future.completeOnTimeout(new StreamResult(), 2, TimeUnit.SECONDS);
            return future;
        }

        var result = new StreamResult();
        var content = new StringBuilder();
        var thinking = new StringBuilder();
        for (String delta : response.thinkingDeltas()) {
            thinking.append(delta);
            onPartial.accept(content.toString(), thinking.toString());
        }
        for (String delta : response.contentDeltas()) {
            content.append(delta);
            onPartial.accept(content.toString(), thinking.toString());
        }
        result.setContent(content.toString());
        result.setThinking(thinking.toString());
        result.setToolCalls(response.toolCalls());
        result.setUsage(response.usage());
        return CompletableFuture.completedFuture(result);
    }

    public record Request(List<Message> messages, List<Tool> tools) {
    }

    public FauxLlm blockUntilAborted() {
        enqueue(new Response(List.of(), List.of(), List.of(), null, true));
        return this;
    }

    public record Response(List<String> contentDeltas, List<String> thinkingDeltas, List<ToolCall> toolCalls,
                                    Usage usage, boolean blockUntilAborted) {
        Response(List<String> contentDeltas, List<String> thinkingDeltas, List<ToolCall> toolCalls, Usage usage) {
            this(contentDeltas, thinkingDeltas, toolCalls, usage, false);
        }

        public static Response content(String... deltas) {
            return new Response(List.of(deltas), List.of(), List.of(), null);
        }

        public static Response thinkingAndContent(List<String> thinkingDeltas, String... contentDeltas) {
            return new Response(List.of(contentDeltas), thinkingDeltas, List.of(), null);
        }

        public static Response toolCall(ToolCall... toolCalls) {
            return new Response(List.of(), List.of(), List.of(toolCalls), null);
        }
    }
}
