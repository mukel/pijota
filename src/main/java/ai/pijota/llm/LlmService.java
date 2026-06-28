package ai.pijota.llm;

import ai.pijota.config.ProviderCatalog;
import ai.pijota.messages.Message;
import ai.pijota.messages.ToolCall;
import ai.pijota.messages.Usage;
import ai.pijota.tools.Tool;
import ai.pijota.tools.ToolManifest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;

public final class LlmService implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final ConcurrentHashMap<ModelCacheKey, StreamingChatModel> modelCache = new ConcurrentHashMap<>();
    private final List<ProviderFactory> customProviders;
    private final UsageTracker usage;

    private volatile String providerName;
    private volatile String modelName;
    private final Function<String, String> apiKeyResolver;
    private volatile String lastResolvedKey;
    private final String customBaseUrl;
    private volatile ThinkingLevel thinkingLevel = ThinkingLevel.OFF;
    private volatile BiConsumer<String, String> modelChangeCallback;

    // A single cancel hook for the in-flight stream; abort() runs it. langchain4j models register their
    // StreamingHandle::cancel once streaming starts; the custom Codex model registers its own abort() up front.
    private final AtomicReference<Runnable> activeCancel = new AtomicReference<>();
    private volatile LlmClient.RequestSpeed lastRequestSpeed;

    @Override
    public LlmClient.RequestSpeed lastRequestSpeed() {
        return lastRequestSpeed;
    }

    private record ModelCacheKey(String provider, String model, String apiKey, ThinkingLevel thinkingLevel) {
    }

    private static String defaultProvider(String apiKey) {
        if (apiKey != null) {
            if (apiKey.startsWith("sk-ant"))
                return "anthropic";
            if (apiKey.startsWith("sk-"))
                return "openai";
        }
        if (System.getenv("ANTHROPIC_API_KEY") != null)
            return "anthropic";
        if (System.getenv("OPENAI_API_KEY") != null)
            return "openai";
        if (System.getenv("GEMINI_API_KEY") != null)
            return "google";
        return "openai";
    }

    public LlmService(String provider, String modelName, Function<String, String> apiKeyResolver) {
        this(provider, modelName, apiKeyResolver, null, List.of(), null);
    }

    /**
     * Primary constructor with a dynamic API key resolver. The resolver is called before each LLM request with the
     * current provider name, enabling transparent OAuth token refresh mid-session.
     */
    public LlmService(String provider, String modelName, Function<String, String> apiKeyResolver, String customBaseUrl,
                                    List<ProviderFactory> customProviders, Double budgetLimit) {
        String initialProvider = provider != null
                                        ? provider
                                        : defaultProvider(apiKeyResolver != null ? apiKeyResolver.apply(null) : null);
        var providerModel = ModelRegistry.resolveProviderModel(initialProvider, modelName, null);
        this.providerName = providerModel.provider();
        this.modelName = providerModel.model();
        this.apiKeyResolver = apiKeyResolver != null ? apiKeyResolver : p -> "";
        String initialKey = this.apiKeyResolver.apply(this.providerName);
        this.lastResolvedKey = initialKey != null ? initialKey : "";
        this.customBaseUrl = customBaseUrl;
        this.customProviders = customProviders;
        this.usage = new UsageTracker(budgetLimit);
    }

    public UsageTracker usage() {
        return usage;
    }

    public long totalInputTokens() {
        return usage.totalInputTokens();
    }

    @Override
    public long lastContextTokens() {
        return usage.lastContextTokens();
    }

    public long totalOutputTokens() {
        return usage.totalOutputTokens();
    }

    @Override
    public double totalCost() {
        return usage.totalCost();
    }

    @Override
    public Double costBudget() {
        return usage.budgetLimit();
    }

    @Override
    public boolean hasUnpricedUsage() {
        return usage.hasUnpricedUsage();
    }

    public CompletableFuture<StreamResult> streamMessages(List<Message> messages, List<Tool> tools,
                                    BiConsumer<String, String> onPartial) {
        if (usage.overBudget()) {
            var r = new StreamResult();
            r.setContent("[Budget limit exceeded]");
            return CompletableFuture.completedFuture(r);
        }
        resolveCurrentApiKey();
        var adapted = MessageTransformer.transform(messages, providerName, modelName);
        var specs = tools.stream().sorted(Comparator.comparing(Tool::name)).map(t -> ToolSpecification.builder()
                                        .name(t.name()).description(t.description())
                                        .parameters(ToolSchema.objectFromMap(ToolManifest.canonicalSchema(t.schema())))
                                        .build()).toList();
        return streamLangChain4j(adapted, specs, onPartial);
    }

    private CompletableFuture<StreamResult> streamLangChain4j(List<Message> messages, List<ToolSpecification> tools,
                                    BiConsumer<String, String> onPartial) {
        return streamLangChain4j(lc4jModel(), messages, tools, onPartial, activeCancel);
    }

    private CompletableFuture<StreamResult> streamLangChain4j(StreamingChatModel model, List<Message> messages,
                                    List<ToolSpecification> tools, BiConsumer<String, String> onPartial,
                                    AtomicReference<Runnable> cancelRef) {
        // The custom Codex model runs its own virtual-thread SSE loop that langchain4j's StreamingHandle never reaches,
        // so register its abort() up front; langchain4j models register their handle's cancel once streaming starts.
        // (cancelRef is null for internal, non-cancellable calls like summarization.)
        if (cancelRef != null)
            cancelRef.set(model instanceof CodexStreamingChatModel c ? c::abort : null);
        log.debug("LLM request → {}/{} · {} message(s) · {} tool(s)", providerName, modelName, messages.size(),
                                        tools == null ? 0 : tools.size());
        var future = new CompletableFuture<StreamResult>();
        var result = new StreamResult();
        // 300s safety net so a stalled provider never hangs the turn; log if it actually fires (was previously silent).
        var settled = new AtomicBoolean(false);
        future.completeOnTimeout(result, 300, TimeUnit.SECONDS);
        future.whenComplete((r, ex) -> {
            if (!settled.get())
                log.warn("LLM stream did not complete within 300s for {}/{}; returning the partial response",
                                                providerName, modelName);
        });

        // Gate attachments to the active model's accepted modalities (unsupported media is dropped with a note), so we
        // never send an image/PDF/audio/video to a model that can't read it — langchain4j maps the rest natively.
        var chatMessages = MessageConverter.toLangChain4j(messages,
                                        ModelRegistry.inputModalities(providerName, modelName));
        var requestBuilder = ChatRequest.builder().messages(chatMessages);
        if (tools != null && !tools.isEmpty())
            requestBuilder.toolSpecifications(tools);

        model.chat(requestBuilder.build(), new StreamingChatResponseHandler() {
            private final StringBuilder thinkingBuf = new StringBuilder();
            private final long streamStartNanos = System.nanoTime();
            private long firstTokenNanos = 0; // time-to-first-token: marks the end of prefill / start of decode

            private void markFirstToken() {
                if (firstTokenNanos == 0)
                    firstTokenNanos = System.nanoTime();
            }

            @Override
            public void onPartialResponse(String t) {
                markFirstToken();
                applyPartialContent(result, t);
                onPartial.accept(result.content(), thinkingBuf.toString());
            }

            @Override
            public void onPartialResponse(PartialResponse pr, PartialResponseContext ctx) {
                if (ctx != null && ctx.streamingHandle() != null && cancelRef != null)
                    cancelRef.set(ctx.streamingHandle()::cancel);
                String t = pr.text();
                if (t != null) {
                    markFirstToken();
                    applyPartialContent(result, t);
                    onPartial.accept(result.content(), thinkingBuf.toString());
                }
            }

            @Override
            public void onPartialThinking(PartialThinking pt, PartialThinkingContext ctx) {
                if (ctx != null && ctx.streamingHandle() != null && cancelRef != null)
                    cancelRef.set(ctx.streamingHandle()::cancel);
                String ptText = pt.text();
                if (ptText != null) {
                    markFirstToken();
                }
                thinkingBuf.append(ptText);
                onPartial.accept(result.content(), thinkingBuf.toString());
            }

            @Override
            public void onCompleteResponse(ChatResponse r) {
                var ai = r.aiMessage();
                if (ai != null) {
                    if (ai.text() != null && !ai.text().isEmpty())
                        result.setContent(ai.text());
                    if (ai.thinking() != null && !ai.thinking().isBlank() && thinkingBuf.isEmpty())
                        result.setThinking(ai.thinking());
                    if (ai.toolExecutionRequests() != null)
                        result.setToolCalls(ai.toolExecutionRequests().stream()
                                                        .map(tc -> new ToolCall(tc.id(), tc.name(), tc.arguments()))
                                                        .toList());
                }
                if (!thinkingBuf.isEmpty())
                    result.setThinking(thinkingBuf.toString());
                var u = r.metadata().tokenUsage();
                if (u != null) {
                    result.setUsage(new Usage(u.inputTokenCount() != null ? u.inputTokenCount() : 0,
                                                    u.outputTokenCount() != null ? u.outputTokenCount() : 0));
                }
                usage.add(result.usage(), ModelRegistry.pricingFor(providerName, modelName));
                recordSpeed(u);
                if (cancelRef != null)
                    cancelRef.set(null);
                settled.set(true);
                future.complete(result);
            }

            // prefill = input tokens / time-to-first-token; decode = output tokens / generation time.
            private void recordSpeed(TokenUsage u) {
                long completeNanos = System.nanoTime();
                long ttft = firstTokenNanos == 0 ? completeNanos : firstTokenNanos;
                double prefillSec = (ttft - streamStartNanos) / 1e9;
                double decodeSec = (completeNanos - ttft) / 1e9;
                int in = u != null && u.inputTokenCount() != null ? u.inputTokenCount() : 0;
                int out = u != null && u.outputTokenCount() != null ? u.outputTokenCount() : 0;
                double prefill = prefillSec > 0 ? in / prefillSec : 0;
                double decode = decodeSec > 0 ? out / decodeSec : 0;
                lastRequestSpeed = new LlmClient.RequestSpeed(prefill, decode);
            }

            @Override
            public void onError(Throwable e) {
                if (cancelRef != null)
                    cancelRef.set(null);
                settled.set(true);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    private static void applyPartialContent(StreamResult result, String text) {
        if (text == null || text.isEmpty())
            return;
        String current = result.content();
        if (text.startsWith(current)) {
            result.setContent(text);
        } else {
            result.appendContent(text);
        }
    }

    private String resolveCurrentApiKey() {
        String key = apiKeyResolver.apply(providerName);
        if (key == null)
            key = "";
        if (!key.equals(lastResolvedKey)) {
            modelCache.clear();
            lastResolvedKey = key;
            if (key.isBlank()) // diagnostic: a blank key usually means an unset key or a failed OAuth refresh → cryptic
                               // 401
                log.warn("No API key resolved for provider '{}'; requests may fail with an authentication error.",
                                                providerName);
        }
        return key;
    }

    private StreamingChatModel lc4jModel() {
        var providerModel = ModelRegistry.resolveProviderModel(providerName, modelName, null);
        String effectiveModel = providerModel.model();
        String effectiveProvider = providerModel.provider();
        var key = new ModelCacheKey(effectiveProvider, effectiveModel, lastResolvedKey, thinkingLevel);
        return modelCache.computeIfAbsent(key, cacheKey -> {
            var factory = ProviderFactory.resolve(cacheKey.provider(), customProviders);
            String url = customBaseUrl != null ? customBaseUrl : ProviderCatalog.baseUrl(cacheKey.provider());
            return factory.create(url, cacheKey.apiKey(), cacheKey.model(), cacheKey.provider(),
                                            cacheKey.thinkingLevel());
        });
    }

    public void abort() {
        var cancel = activeCancel.getAndSet(null);
        if (cancel != null) {
            cancel.run();
        }
        modelCache.clear();
    }

    /**
     * Summarize with optional model override and custom system prompt. If {@code modelOverride} is non-null, a one-off
     * model instance is created without affecting the cached model used for the main conversation.
     */
    public String summarize(List<Message> toSummarize, String instructions, String previousSummary,
                                    BiConsumer<String, String> onPartial, String modelOverride,
                                    String customSystemPrompt) {
        String prompt = Summarizer.buildSummaryPrompt(toSummarize, instructions, previousSummary);
        String effectiveSystemPrompt = customSystemPrompt != null && !customSystemPrompt.isBlank()
                                        ? customSystemPrompt
                                        : "You are a context summarization assistant. Read the conversation and output only the structured summary requested.";
        try {
            // Reuse the main conversation's model handle (same cached, fully-resolved provider+model+key) so
            // summarization works for every provider — notably Codex/OAuth, where re-resolving from modelName alone
            // loses the model. A summarize sub-agent can still force a different model via modelOverride.
            var model = (modelOverride != null && !modelOverride.isEmpty())
                                            ? lc4jModelForModel(modelOverride)
                                            : lc4jModel();
            var sysMsg = new Message.System(UUID.randomUUID(), Instant.now(), effectiveSystemPrompt);
            var userMsg = new Message.User(prompt);
            var messages = List.<Message>of(sysMsg, userMsg);
            var result = streamLangChain4j(model, messages, List.of(), onPartial != null ? onPartial : (_, _) -> {},
                                            null).get(120, TimeUnit.SECONDS);
            return result.content() != null ? result.content().strip() : null;
        } catch (TimeoutException e) {
            log.warn("Summarization timed out after 120s", e);
            return null;
        } catch (Exception e) {
            log.warn("Summarization failed: {}", e.toString());
            return null;
        }
    }

    private StreamingChatModel lc4jModelForModel(String m) {
        var pm = ModelRegistry.parseProviderModel(m != null && !m.isEmpty() ? m : null);
        String explicitProvider = pm.provider();
        String requested = pm.model();
        String effectiveProvider = explicitProvider != null
                                        ? explicitProvider
                                        : requested != null ? ModelRegistry.providerForModel(requested) : providerName;
        if (effectiveProvider == null || effectiveProvider.isBlank())
            effectiveProvider = providerName;
        var providerModel = ModelRegistry.resolveProviderModel(effectiveProvider, requested, null);
        effectiveProvider = providerModel.provider();
        String effectiveModel = providerModel.model();
        if (effectiveModel == null || effectiveModel.isBlank())
            effectiveModel = modelName; // never hand a null/blank model to a provider (e.g. Codex 400s on "the 'null'
                                        // model")
        String key = apiKeyResolver.apply(effectiveProvider);
        if (key == null)
            key = "";
        var factory = ProviderFactory.resolve(effectiveProvider, customProviders);
        String url = customBaseUrl != null ? customBaseUrl : ProviderCatalog.baseUrl(effectiveProvider);
        return factory.create(url, key, effectiveModel, effectiveProvider, thinkingLevel);
    }

    public String provider() {
        return providerName;
    }

    public String model() {
        return modelName;
    }

    public String apiKey() {
        return lastResolvedKey;
    }

    public void setModelName(String name) {
        String old = this.modelName;
        var providerModel = ModelRegistry.resolveProviderModel(this.providerName, name, null);
        this.providerName = providerModel.provider();
        this.modelName = providerModel.model();
        modelCache.clear();
        if (modelChangeCallback != null && !this.modelName.equals(old))
            modelChangeCallback.accept(old, this.modelName);
    }

    public void setModelChangeCallback(BiConsumer<String, String> callback) {
        this.modelChangeCallback = callback;
    }

    public void setProviderAndModel(String newProvider, String newModel, String newApiKey) {
        String old = this.modelName;
        var providerModel = ModelRegistry.resolveProviderModel(newProvider, newModel, null);
        this.providerName = providerModel.provider();
        this.modelName = providerModel.model();
        this.lastResolvedKey = newApiKey != null ? newApiKey : "";
        modelCache.clear();
        if (modelChangeCallback != null && !this.modelName.equals(old))
            modelChangeCallback.accept(old, this.modelName);
    }

    public ThinkingLevel thinkingLevel() {
        return thinkingLevel;
    }

    public void setThinkingLevel(ThinkingLevel level) {
        this.thinkingLevel = level != null ? level : ThinkingLevel.OFF;
        modelCache.clear();
    }

    @Override
    public void close() {
        modelCache.clear();
        abort();
    }

    static final class UsageTracker {
        private final Double budgetLimit;
        private int totalInputTokens;
        private int totalOutputTokens;
        private int lastContextTokens; // input+output of the most recent response ≈ the next request's prompt size
        private double totalCost;
        private boolean hasUnpricedUsage;

        UsageTracker(Double budgetLimit) {
            this.budgetLimit = budgetLimit;
        }

        boolean overBudget() {
            return budgetLimit != null && totalCost >= budgetLimit;
        }

        int totalInputTokens() {
            return totalInputTokens;
        }

        int totalOutputTokens() {
            return totalOutputTokens;
        }

        int lastContextTokens() {
            return lastContextTokens;
        }

        double totalCost() {
            return totalCost;
        }

        Double budgetLimit() {
            return budgetLimit;
        }

        /** Whether any recorded usage ran on a model with unknown pricing (so {@link #totalCost} understates spend). */
        boolean hasUnpricedUsage() {
            return hasUnpricedUsage;
        }

        void add(Usage usage, ModelRegistry.Pricing pricing) {
            if (usage == null)
                return;
            int in = (int) usage.inputTokens();
            int out = (int) usage.outputTokens();
            totalInputTokens += in;
            totalOutputTokens += out;
            if (in > 0)
                lastContextTokens = in + out; // the real prompt size this turn (+ output, which re-enters context)
            if (pricing != null)
                totalCost += (in / 1_000_000.0) * pricing.inputPerMTok()
                                                + (out / 1_000_000.0) * pricing.outputPerMTok();
            else if (in > 0 || out > 0)
                hasUnpricedUsage = true;
        }
    }
}
