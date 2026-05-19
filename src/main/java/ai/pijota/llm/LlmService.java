package ai.pijota.llm;

import ai.pijota.messages.Message;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

public final class LlmService implements AutoCloseable {
    private final ConcurrentHashMap<String, StreamingChatModel> modelCache = new ConcurrentHashMap<>();
    private final List<ProviderFactory> customProviders;
    private final UsageTracker usage;

    private volatile String providerName;
    private volatile String modelName;
    private volatile String apiKey;
    private final String customBaseUrl;
    private volatile ThinkingLevel thinkingLevel = ThinkingLevel.OFF;
    private volatile java.util.function.BiConsumer<String, String> modelChangeCallback;

    private final AtomicReference<StreamingHandle> activeHandle = new AtomicReference<>();

    private static String defaultProvider(String apiKey) {
        if (apiKey != null) {
            if (apiKey.startsWith("sk-ant")) return "anthropic";
            if (apiKey.startsWith("sk-")) return "openai";
        }
        if (System.getenv("ANTHROPIC_API_KEY") != null) return "anthropic";
        if (System.getenv("OPENAI_API_KEY") != null) return "openai";
        if (System.getenv("GEMINI_API_KEY") != null) return "google";
        return "openai";
    }

    public LlmService(String provider, String modelName, String apiKey) {
        this(provider, modelName, apiKey, null, List.of(), null);
    }

    public LlmService(String provider, String modelName, String apiKey,
                      String customBaseUrl, List<ProviderFactory> customProviders,
                      Double budgetLimit) {
        this.providerName = provider != null ? provider : defaultProvider(apiKey);
        this.modelName = modelName != null ? modelName : ModelRegistry.defaultModelForProvider(this.providerName);
        this.apiKey = apiKey;
        this.customBaseUrl = customBaseUrl;
        this.customProviders = customProviders;
        this.usage = new UsageTracker(budgetLimit);
    }

    public CompletableFuture<StreamResult> streamMessages(
            List<Message> messages, List<ToolSpecification> tools,
            BiConsumer<String, String> onPartial
    ) {
        if (usage.overBudget()) {
            var r = new StreamResult(); r.setContent("[Budget limit exceeded]");
            return CompletableFuture.completedFuture(r);
        }

        return streamLangChain4j(messages, tools, onPartial);
    }

    private CompletableFuture<StreamResult> streamLangChain4j(
            List<Message> messages, List<ToolSpecification> tools,
            BiConsumer<String, String> onPartial) {
        var future = new CompletableFuture<StreamResult>();
        var result = new StreamResult();
        future.completeOnTimeout(result, 300, TimeUnit.SECONDS);

        var chatMessages = MessageConverter.toLangChain4j(messages);
        var requestBuilder = ChatRequest.builder().messages(chatMessages);
        if (tools != null && !tools.isEmpty())
            requestBuilder.toolSpecifications(tools);

        lc4jModel().chat(requestBuilder.build(), new StreamingChatResponseHandler() {
            private final StringBuilder thinkingBuf = new StringBuilder();

            @Override public void onPartialResponse(String t) {
                result.appendContent(t);
                onPartial.accept(result.content(), thinkingBuf.toString());
            }
            @Override public void onPartialResponse(PartialResponse pr, PartialResponseContext ctx) {
                if (ctx != null && ctx.streamingHandle() != null)
                    activeHandle.set(ctx.streamingHandle());
                String t = pr.text();
                if (t != null) {
                    result.appendContent(t);
                    onPartial.accept(result.content(), thinkingBuf.toString());
                }
            }
            @Override public void onPartialThinking(PartialThinking pt, PartialThinkingContext ctx) {
                if (ctx != null && ctx.streamingHandle() != null)
                    activeHandle.set(ctx.streamingHandle());
                thinkingBuf.append(pt.text());
                onPartial.accept(result.content(), thinkingBuf.toString());
            }
            @Override public void onCompleteResponse(ChatResponse r) {
                var ai = r.aiMessage();
                if (ai != null) {
                    if (ai.text() != null && !ai.text().isEmpty())
                        result.setContent(ai.text());
                    if (ai.toolExecutionRequests() != null)
                        result.setToolRequests(ai.toolExecutionRequests());
                }
                if (!thinkingBuf.isEmpty())
                    result.setThinking(thinkingBuf.toString());
                var u = r.metadata().tokenUsage();
                result.setUsage(u);
                usage.add(u);
                activeHandle.set(null);
                future.complete(result);
            }
            @Override public void onError(Throwable e) {
                activeHandle.set(null);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    private StreamingChatModel lc4jModel() {
        return modelCache.computeIfAbsent(providerName, p -> {
            var factory = ProviderFactory.resolve(p, customProviders);
            String url = customBaseUrl != null ? customBaseUrl
                : ProviderCatalog.baseUrl(p);
            return factory.create(url, apiKey, modelName, p, thinkingLevel);
        });
    }

    public void abort() {
        var handle = activeHandle.getAndSet(null);
        if (handle != null) {
            handle.cancel();
        }
        modelCache.clear();
    }

    public String summarize(List<Message> toSummarize, String instructions, String previousSummary) {
        return summarize(toSummarize, instructions, previousSummary, (_, _) -> {});
    }

    public String summarize(List<Message> toSummarize, String instructions, String previousSummary,
                            BiConsumer<String, String> onPartial) {
        String prompt = Summarizer.buildSummaryPrompt(toSummarize, instructions, previousSummary);
        try {
            var model = lc4jModel();
            var sysMsg = new ai.pijota.messages.Message.System(java.util.UUID.randomUUID(),
                java.time.Instant.now(),
                "You are a context summarization assistant. Read the conversation and output only the structured summary requested.");
            var userMsg = new ai.pijota.messages.Message.User(prompt);
            var messages = List.<ai.pijota.messages.Message>of(sysMsg, userMsg);
            var result = streamLangChain4j(messages, List.of(), onPartial != null ? onPartial : (_, _) -> {})
                .get(120, java.util.concurrent.TimeUnit.SECONDS);
            return result.content() != null ? result.content().strip() : null;
        } catch (java.util.concurrent.TimeoutException e) {
            abort();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public String provider() { return providerName; }
    public String model() { return modelName; }
    public String apiKey() { return apiKey; }

    public void setModelName(String name) {
        String old = this.modelName;
        this.modelName = name;
        modelCache.clear();
        if (modelChangeCallback != null && !name.equals(old))
            modelChangeCallback.accept(old, name);
    }

    public void setModelChangeCallback(java.util.function.BiConsumer<String, String> callback) {
        this.modelChangeCallback = callback;
    }

    public void setProviderAndModel(String newProvider, String newModel, String newApiKey) {
        String old = this.modelName;
        this.providerName = newProvider;
        this.modelName = newModel;
        this.apiKey = newApiKey;
        modelCache.clear();
        if (modelChangeCallback != null && !newModel.equals(old))
            modelChangeCallback.accept(old, newModel);
    }

    public void setThinkingLevel(ThinkingLevel level) {
        this.thinkingLevel = level != null ? level : ThinkingLevel.OFF;
        modelCache.clear();
    }

    @Override public void close() {
        modelCache.clear();
        abort();
    }

    static final class UsageTracker {
        private static final double COST_PER_1K_INPUT = 0.0025;
        private static final double COST_PER_1K_OUTPUT = 0.01;

        private final Double budgetLimit;
        private int totalInputTokens;
        private int totalOutputTokens;
        private double totalCost;

        UsageTracker(Double budgetLimit) { this.budgetLimit = budgetLimit; }

        boolean overBudget() { return budgetLimit != null && totalCost >= budgetLimit; }

        void add(TokenUsage usage) {
            if (usage == null) return;
            int in = usage.inputTokenCount() != null ? usage.inputTokenCount() : 0;
            int out = usage.outputTokenCount() != null ? usage.outputTokenCount() : 0;
            totalInputTokens += in;
            totalOutputTokens += out;
            totalCost += (in / 1000.0) * COST_PER_1K_INPUT
                       + (out / 1000.0) * COST_PER_1K_OUTPUT;
        }
    }
}
