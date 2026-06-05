package ai.pijota.llm;

import ai.pijota.config.ProviderCatalog;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

public final class LlmService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final ConcurrentHashMap<ModelCacheKey, StreamingChatModel> modelCache = new ConcurrentHashMap<>();
    private final List<ProviderFactory> customProviders;
    private final UsageTracker usage;

    private volatile String providerName;
    private volatile String modelName;
    private final java.util.function.Function<String, String> apiKeyResolver;
    private volatile String lastResolvedKey;
    private final String customBaseUrl;
    private volatile ThinkingLevel thinkingLevel = ThinkingLevel.OFF;
    private volatile java.util.function.BiConsumer<String, String> modelChangeCallback;

    private final AtomicReference<StreamingHandle> activeHandle = new AtomicReference<>();

    private record ModelCacheKey(String provider, String model, String apiKey, ThinkingLevel thinkingLevel) {}

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

    public LlmService(String provider, String modelName,
                      java.util.function.Function<String, String> apiKeyResolver) {
        this(provider, modelName, apiKeyResolver, null, List.of(), null);
    }

    /**
     * Primary constructor with a dynamic API key resolver.
     * The resolver is called before each LLM request with the current provider name,
     * enabling transparent OAuth token refresh mid-session.
     */
    public LlmService(String provider, String modelName,
                      java.util.function.Function<String, String> apiKeyResolver,
                      String customBaseUrl, List<ProviderFactory> customProviders,
                      Double budgetLimit) {
        this.providerName = provider != null ? provider
            : defaultProvider(apiKeyResolver != null ? apiKeyResolver.apply(null) : null);
        this.modelName = modelName != null ? modelName : ModelRegistry.defaultModelForProvider(this.providerName);
        this.apiKeyResolver = apiKeyResolver != null ? apiKeyResolver : p -> "";
        String initialKey = this.apiKeyResolver.apply(this.providerName);
        this.lastResolvedKey = initialKey != null ? initialKey : "";
        this.customBaseUrl = customBaseUrl;
        this.customProviders = customProviders;
        this.usage = new UsageTracker(budgetLimit);
    }

    public UsageTracker usage() { return usage; }

    public long totalInputTokens() { return usage.totalInputTokens(); }
    public long totalOutputTokens() { return usage.totalOutputTokens(); }
    public boolean isOverBudget() { return usage.overBudget(); }

    public CompletableFuture<StreamResult> streamMessages(
            List<Message> messages, List<ai.pijota.tools.Tool> tools,
            BiConsumer<String, String> onPartial
    ) {
        if (usage.overBudget()) {
            var r = new StreamResult(); r.setContent("[Budget limit exceeded]");
            return CompletableFuture.completedFuture(r);
        }
        resolveCurrentApiKey();
        var adapted = MessageTransformer.transform(messages, providerName, modelName);
        var specs = tools.stream()
            .sorted(java.util.Comparator.comparing(ai.pijota.tools.Tool::name))
            .map(t -> ToolSpecification.builder()
                .name(t.name())
                .description(t.description())
                .parameters(ToolSchema.objectFromMap(
                    ai.pijota.tools.ToolManifest.canonicalSchema(t.schema())))
                .build())
            .toList();
        return streamLangChain4j(adapted, specs, onPartial);
    }

    private CompletableFuture<StreamResult> streamLangChain4j(
            List<Message> messages, List<ToolSpecification> tools,
            BiConsumer<String, String> onPartial) {
        return streamLangChain4j(lc4jModel(), messages, tools, onPartial, activeHandle);
    }

    private CompletableFuture<StreamResult> streamLangChain4j(
            StreamingChatModel model,
            List<Message> messages, List<ToolSpecification> tools,
            BiConsumer<String, String> onPartial,
            AtomicReference<StreamingHandle> handleRef) {
        var future = new CompletableFuture<StreamResult>();
        var result = new StreamResult();
        future.completeOnTimeout(result, 300, TimeUnit.SECONDS);

        var chatMessages = MessageConverter.toLangChain4j(messages);
        var requestBuilder = ChatRequest.builder().messages(chatMessages);
        if (tools != null && !tools.isEmpty())
            requestBuilder.toolSpecifications(tools);

        model.chat(requestBuilder.build(), new StreamingChatResponseHandler() {
            private final StringBuilder thinkingBuf = new StringBuilder();

            @Override public void onPartialResponse(String t) {
                applyPartialContent(result, t);
                onPartial.accept(result.content(), thinkingBuf.toString());
            }
            @Override public void onPartialResponse(PartialResponse pr, PartialResponseContext ctx) {
                if (ctx != null && ctx.streamingHandle() != null && handleRef != null)
                    handleRef.set(ctx.streamingHandle());
                String t = pr.text();
                if (t != null) {
                    applyPartialContent(result, t);
                    onPartial.accept(result.content(), thinkingBuf.toString());
                }
            }
            @Override public void onPartialThinking(PartialThinking pt, PartialThinkingContext ctx) {
                if (ctx != null && ctx.streamingHandle() != null && handleRef != null)
                    handleRef.set(ctx.streamingHandle());
                thinkingBuf.append(pt.text());
                onPartial.accept(result.content(), thinkingBuf.toString());
            }
            @Override public void onCompleteResponse(ChatResponse r) {
                var ai = r.aiMessage();
                if (ai != null) {
                    if (ai.text() != null && !ai.text().isEmpty())
                        result.setContent(ai.text());
                    if (ai.thinking() != null && !ai.thinking().isBlank() && thinkingBuf.isEmpty())
                        result.setThinking(ai.thinking());
                    if (ai.toolExecutionRequests() != null)
                        result.setToolCalls(ai.toolExecutionRequests().stream()
                            .map(tc -> new ai.pijota.messages.ToolCall(tc.id(), tc.name(), tc.arguments()))
                            .toList());
                }
                if (!thinkingBuf.isEmpty())
                    result.setThinking(thinkingBuf.toString());
                var u = r.metadata().tokenUsage();
                if (u != null) {
                    result.setUsage(new ai.pijota.messages.Usage(
                        u.inputTokenCount() != null ? u.inputTokenCount() : 0,
                        u.outputTokenCount() != null ? u.outputTokenCount() : 0));
                }
                usage.add(result.usage());
                if (handleRef != null) handleRef.set(null);
                future.complete(result);
            }
            @Override public void onError(Throwable e) {
                if (handleRef != null) handleRef.set(null);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    private static void applyPartialContent(StreamResult result, String text) {
        if (text == null || text.isEmpty()) return;
        String current = result.content();
        if (text.startsWith(current)) {
            result.setContent(text);
        } else {
            result.appendContent(text);
        }
    }

    private String resolveCurrentApiKey() {
        String key = apiKeyResolver.apply(providerName);
        if (key == null) key = "";
        if (!key.equals(lastResolvedKey)) {
            modelCache.clear();
            lastResolvedKey = key;
        }
        return key;
    }

    private StreamingChatModel lc4jModel() {
        String effectiveModel = modelName != null ? modelName
            : ModelRegistry.defaultModelForProvider(providerName);
        var key = new ModelCacheKey(providerName, effectiveModel, lastResolvedKey, thinkingLevel);
        return modelCache.computeIfAbsent(key, cacheKey -> {
            var factory = ProviderFactory.resolve(cacheKey.provider(), customProviders);
            String url = customBaseUrl != null ? customBaseUrl
                : ProviderCatalog.baseUrl(cacheKey.provider());
            return factory.create(url, cacheKey.apiKey(), cacheKey.model(), cacheKey.provider(), cacheKey.thinkingLevel());
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
        return summarize(toSummarize, instructions, previousSummary, onPartial, null, null);
    }

    /**
     * Summarize with optional model override and custom system prompt.
     * If {@code modelOverride} is non-null, a one-off model instance is created without
     * affecting the cached model used for the main conversation.
     */
    public String summarize(List<Message> toSummarize, String instructions, String previousSummary,
                            BiConsumer<String, String> onPartial,
                            String modelOverride, String customSystemPrompt) {
        String prompt = Summarizer.buildSummaryPrompt(toSummarize, instructions, previousSummary);
        String effectiveSystemPrompt = customSystemPrompt != null && !customSystemPrompt.isBlank()
            ? customSystemPrompt
            : "You are a context summarization assistant. Read the conversation and output only the structured summary requested.";
        try {
            // Always use a fresh model instance, isolated from the main conversation's handle
            var model = (modelOverride != null && !modelOverride.isEmpty())
                ? lc4jModelForModel(modelOverride)
                : lc4jModelForModel(modelName);
            var sysMsg = new ai.pijota.messages.Message.System(java.util.UUID.randomUUID(),
                java.time.Instant.now(), effectiveSystemPrompt);
            var userMsg = new ai.pijota.messages.Message.User(prompt);
            var messages = List.<ai.pijota.messages.Message>of(sysMsg, userMsg);
            var result = streamLangChain4j(model, messages, List.of(),
                onPartial != null ? onPartial : (_, _) -> {}, null)
                .get(120, java.util.concurrent.TimeUnit.SECONDS);
            return result.content() != null ? result.content().strip() : null;
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("Summarization timed out after 120s", e);
            return null;
        } catch (Exception e) {
            log.warn("Summarization failed: {}", e.toString());
            return null;
        }
    }

    private StreamingChatModel lc4jModelForModel(String m) {
        String requested = m != null && !m.isEmpty() ? m : null;
        String explicitProvider = null;
        if (requested != null && requested.contains("/")) {
            int slash = requested.indexOf('/');
            explicitProvider = requested.substring(0, slash);
            requested = requested.substring(slash + 1);
        }
        String effectiveModel = requested != null ? requested : ModelRegistry.defaultModelForProvider(providerName);
        String effectiveProvider = explicitProvider != null ? explicitProvider
            : requested != null ? ModelRegistry.providerForModel(requested) : providerName;
        if (effectiveProvider == null || effectiveProvider.isBlank()) effectiveProvider = providerName;
        String key = apiKeyResolver.apply(effectiveProvider);
        if (key == null) key = "";
        var factory = ProviderFactory.resolve(effectiveProvider, customProviders);
        String url = customBaseUrl != null ? customBaseUrl : ProviderCatalog.baseUrl(effectiveProvider);
        return factory.create(url, key, effectiveModel, effectiveProvider, thinkingLevel);
    }

    public String provider() { return providerName; }
    public String model() { return modelName; }
    public String apiKey() { return lastResolvedKey; }

    public void setModelName(String name) {
        String old = this.modelName;
        this.modelName = name != null ? name : ModelRegistry.defaultModelForProvider(this.providerName);
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
        this.modelName = newModel != null ? newModel : ModelRegistry.defaultModelForProvider(newProvider);
        this.lastResolvedKey = newApiKey != null ? newApiKey : "";
        modelCache.clear();
        if (modelChangeCallback != null && !newModel.equals(old))
            modelChangeCallback.accept(old, newModel);
    }

    public ThinkingLevel thinkingLevel() { return thinkingLevel; }

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

        int totalInputTokens() { return totalInputTokens; }
        int totalOutputTokens() { return totalOutputTokens; }
        double totalCost() { return totalCost; }

        void add(ai.pijota.messages.Usage usage) {
            if (usage == null) return;
            int in = (int) usage.inputTokens();
            int out = (int) usage.outputTokens();
            totalInputTokens += in;
            totalOutputTokens += out;
            totalCost += (in / 1000.0) * COST_PER_1K_INPUT
                       + (out / 1000.0) * COST_PER_1K_OUTPUT;
        }
    }
}
