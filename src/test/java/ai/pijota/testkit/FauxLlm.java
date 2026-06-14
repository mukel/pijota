package ai.pijota.testkit;

import ai.pijota.llm.LlmClient;
import ai.pijota.llm.StreamResult;
import ai.pijota.llm.ThinkingLevel;
import ai.pijota.messages.Message;
import ai.pijota.messages.ToolCall;
import ai.pijota.messages.Usage;
import ai.pijota.tools.Tool;

import java.io.IOException;
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
    private boolean aborted;
    private String provider = "faux";
    private String model = "faux-model";
    private long inputTokens;
    private long outputTokens;

    /** Sets the cumulative token counts reported by {@link #totalInputTokens()} / {@link #totalOutputTokens()}. */
    public FauxLlm tokens(long input, long output) {
        this.inputTokens = input;
        this.outputTokens = output;
        return this;
    }

    @Override
    public long totalInputTokens() {
        return inputTokens;
    }

    @Override
    public long totalOutputTokens() {
        return outputTokens;
    }

    public FauxLlm provider(String value) {
        this.provider = value;
        return this;
    }

    public FauxLlm model(String value) {
        this.model = value;
        return this;
    }

    public FauxLlm enqueue(Response response) {
        responses.add(response);
        return this;
    }

    public FauxLlm enqueueError(String message) {
        return enqueue(new Response(List.of(), List.of(), List.of(), null, new IOException(message), false));
    }

    public FauxLlm enqueueError(Throwable error) {
        return enqueue(new Response(List.of(), List.of(), List.of(), null, error, false));
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
    public void abort() {
        aborted = true;
    }

    @Override
    public void setProviderAndModel(String provider, String model, String apiKey) {
        this.provider = provider;
        this.model = model;
    }

    @Override
    public void setThinkingLevel(ThinkingLevel level) {
        // no-op for tests
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

        if (response.error() != null) {
            return CompletableFuture.failedFuture(response.error());
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
        enqueue(new Response(List.of(), List.of(), List.of(), null, null, true));
        return this;
    }

    public record Response(List<String> contentDeltas, List<String> thinkingDeltas, List<ToolCall> toolCalls,
                                    Usage usage, Throwable error, boolean blockUntilAborted) {
        Response(List<String> contentDeltas, List<String> thinkingDeltas, List<ToolCall> toolCalls, Usage usage) {
            this(contentDeltas, thinkingDeltas, toolCalls, usage, null, false);
        }

        Response(List<String> contentDeltas, List<String> thinkingDeltas, List<ToolCall> toolCalls, Usage usage,
                                        boolean blockUntilAborted) {
            this(contentDeltas, thinkingDeltas, toolCalls, usage, null, blockUntilAborted);
        }

        public static Response content(String... deltas) {
            return new Response(List.of(deltas), List.of(), List.of(), null);
        }

        public static Response content(Usage usage, String... deltas) {
            return new Response(List.of(deltas), List.of(), List.of(), usage, null, false);
        }

        public static Response thinkingAndContent(List<String> thinkingDeltas, String... contentDeltas) {
            return new Response(List.of(contentDeltas), thinkingDeltas, List.of(), null);
        }

        public static Response toolCall(ToolCall... toolCalls) {
            return new Response(List.of(), List.of(), List.of(toolCalls), null);
        }

        public static Response toolCall(Usage usage, ToolCall... toolCalls) {
            return new Response(List.of(), List.of(), List.of(toolCalls), usage, null, false);
        }
    }
}
