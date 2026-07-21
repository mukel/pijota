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
    private Runnable onRequest = () -> {};
    private RuntimeException streamThrows;
    private String provider = "faux";
    private String model = "faux-model";
    private long inputTokens;
    private long outputTokens;
    private long lastContextTokens; // in + out of the most recent response, mirroring LlmService (the anchor reads it)

    /**
     * Makes {@link #streamMessages} throw synchronously (a provider that fails on the calling thread), rather than
     * returning a failed future — to exercise the agent loop's synchronous-failure resilience.
     */
    public FauxLlm throwOnStream(RuntimeException error) {
        this.streamThrows = error;
        return this;
    }

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

    @Override
    public long lastContextTokens() {
        return lastContextTokens;
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

    /**
     * Runs at the start of every {@link #streamMessages} call — lets a test abort the agent while a request is in
     * flight.
     */
    public FauxLlm onRequest(Runnable hook) {
        this.onRequest = hook;
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
        if (streamThrows != null)
            throw streamThrows;
        requests.add(new Request(List.copyOf(messages), List.copyOf(tools)));
        onRequest.run(); // test hook: e.g. abort the agent mid-flight to exercise retry cancellation
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
        if (response.usage() != null)
            lastContextTokens = response.usage().inputTokens() + response.usage().outputTokens();
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

        /**
         * A single-content response carrying a nominal non-zero {@link Usage} (1 token in, 1 out) — the common "this
         * turn consumed some tokens" case for agent/session tests.
         */
        public static Response contentWithUsage(String content) {
            return content(new Usage(1, 1), content);
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
