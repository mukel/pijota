package org.pijota.llm;

import org.pijota.messages.Message;
import org.pijota.messages.Usage;
import org.pijota.tools.Tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class LlmServiceTest {

    private static LlmService serviceWithFake(FakeModel fake) {
        return new LlmService("openai", "gpt-4o", ignored -> "test-key", null, List.of(new FakeProviderFactory(fake)),
                                        null);
    }

    private static LlmService serviceWithFake(FakeModel fake, String provider, String model) {
        return new LlmService(provider, model, ignored -> "test-key", null, List.of(new FakeProviderFactory(fake)),
                                        null);
    }

    private static LlmService serviceWithKeyResolver(Function<String, String> resolver) {
        return new LlmService("openai", "gpt-4o", resolver, null,
                                        List.of(new FakeProviderFactory(new FakeModel().withContent("ok"))), null);
    }

    @Test
    void extractsReasoningTokensFromOpenAiUsage() {
        // Exercises the typed (non-reflective) extraction that must also work in the native image.
        var usage = OpenAiTokenUsage.builder().inputTokenCount(100).outputTokenCount(900).outputTokensDetails(
                                        OpenAiTokenUsage.OutputTokensDetails.builder().reasoningTokens(600).build())
                                        .build();
        var service = serviceWithFake(new FakeModel().withContent("ok").withUsage(usage));

        service.streamMessages(List.of(new Message.User("hi")), List.of(), (ignoredChunk, ignoredType) -> {}).join();

        assertEquals(600, service.totalReasoningTokens());
        assertEquals(900, service.totalOutputTokens());
    }

    @Test
    void extractsCacheReadFromOpenAiCompatibleUsage() {
        // OpenAI/Mistral/xAI fold cached tokens into prompt_tokens; pijota reports them apart and nets input down.
        var usage = OpenAiTokenUsage.builder().inputTokenCount(1000).outputTokenCount(50).inputTokensDetails(
                                        OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(800).build())
                                        .build();
        var service = serviceWithFake(new FakeModel().withContent("ok").withUsage(usage));

        service.streamMessages(List.of(new Message.User("hi")), List.of(), (ignoredChunk, ignoredType) -> {}).join();

        assertEquals(800, service.totalCacheReadTokens());
        assertEquals(200, service.totalInputTokens(), "input excludes the 800 cached tokens folded into prompt_tokens");
        assertEquals(0, service.totalCacheWriteTokens(), "OpenAI-compatible caching has no separate write count");
    }

    /**
     * A cache hit is precisely the prompt the server did not process, so it cannot count toward prefill speed. The
     * speed line read the provider's raw prompt_tokens while the usage conversion two lines above it had already netted
     * the cached ones out -- so a reused KV cache divided a whole conversation's prompt by the fraction of a second the
     * reuse took. Local servers show it worst because they reuse most aggressively.
     */
    @Test
    void prefillSpeedCountsOnlyThePromptTheServerActuallyProcessed() {
        var usage = OpenAiTokenUsage.builder().inputTokenCount(100_000).outputTokenCount(10).inputTokensDetails(
                                        OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(99_900).build())
                                        .build();
        var service = serviceWithFake(new FakeModel().withContent("ok").withUsage(usage).withPrefillMillis(50));

        service.streamMessages(List.of(new Message.User("hi")), List.of(), (ignoredChunk, ignoredType) -> {}).join();

        var speed = service.lastRequestSpeed();
        assertNotNull(speed, "a completed request must record its speed");
        // 100 tokens over a 50ms stall is ~2000/s. Counting the 99,900 cached ones gives ~2,000,000/s, so any bound
        // between them separates the two; 100,000 leaves room for a slow machine without admitting the bug.
        assertTrue(speed.prefillTokensPerSec() < 100_000, "cached prompt counted as prefill work: "
                                        + Math.round(speed.prefillTokensPerSec()) + " tok/s from 100 processed tokens");
    }

    @Test
    void extractsCacheTokensFromAnthropicUsage() {
        var usage = AnthropicTokenUsage.builder().inputTokenCount(100).outputTokenCount(20).cacheReadInputTokens(4000)
                                        .cacheCreationInputTokens(1000).build();
        var service = serviceWithFake(new FakeModel().withContent("ok").withUsage(usage));

        service.streamMessages(List.of(new Message.User("hi")), List.of(), (ignoredChunk, ignoredType) -> {}).join();

        assertEquals(4000, service.totalCacheReadTokens());
        assertEquals(1000, service.totalCacheWriteTokens());
    }

    @Test
    void cacheTokensCountTowardTheContextAnchor() {
        var tracker = new LlmService.UsageTracker(null);
        tracker.add(new Usage(100, 20, 4000, 1000, 0), null); // 4000 cache read + 1000 cache write

        // The context anchor is the real prompt size: input + cache read + cache write, plus output (re-enters
        // context).
        assertEquals(100 + 4000 + 1000 + 20, tracker.lastContextTokens());
        assertEquals(4000, tracker.totalCacheReadTokens());
        assertEquals(1000, tracker.totalCacheWriteTokens());
    }

    @Test
    void costUsesLongContextTierWhenInputExceedsThreshold() {
        var tracker = new LlmService.UsageTracker(null);
        var pricing = new ModelRegistry.Pricing(1.0, 2.0, List.of(new ModelRegistry.Pricing.Tier(200_000, 4.0, 8.0)));

        tracker.add(new Usage(100_000, 1000), pricing); // under the tier → base rate
        assertEquals(100_000 / 1e6 * 1.0 + 1000 / 1e6 * 2.0, tracker.totalCost(), 1e-9);

        tracker.add(new Usage(250_000, 1000), pricing); // over the tier → surcharge rate on the whole request
        double expected = (100_000 / 1e6 * 1.0 + 1000 / 1e6 * 2.0) + (250_000 / 1e6 * 4.0 + 1000 / 1e6 * 8.0);
        assertEquals(expected, tracker.totalCost(), 1e-9);
    }

    @Test
    void constructorDefaultsProviderFromOpenAiKey() {
        var service = new LlmService(null, null, ignored -> "sk-openai", null,
                                        List.of(new FakeProviderFactory(new FakeModel())), null);
        assertEquals("openai", service.provider());
    }

    @Test
    void constructorDefaultsProviderFromAnthropicKey() {
        var service = new LlmService(null, null, ignored -> "sk-ant-xxx", null,
                                        List.of(new FakeProviderFactory(new FakeModel())), null);
        assertEquals("anthropic", service.provider());
    }

    @Test
    void constructorFallsBackToOpenAiWhenNoKey() {
        var service = new LlmService(null, null, ignored -> null, null,
                                        List.of(new FakeProviderFactory(new FakeModel())), null);
        assertEquals("openai", service.provider());
    }

    @Test
    void setModelNameUpdatesModelAndClearsCache() {
        var fake = new FakeModel().withContent("hi");
        var service = serviceWithFake(fake, "anthropic", "claude-opus-4-5-20251101");
        service.setModelName("claude-sonnet-4-5-20250929");
        assertEquals("claude-sonnet-4-5-20250929", service.model());
    }

    @Test
    void setProviderAndModelUpdatesProvider() {
        var service = serviceWithFake(new FakeModel());
        service.setProviderAndModel("anthropic", "claude-opus-4-5-20251101", "new-key");
        assertEquals("anthropic", service.provider());
        assertEquals("claude-opus-4-5-20251101", service.model());
        assertEquals("new-key", service.apiKey());
    }

    @Test
    void setProviderAndModelHonorsOffCatalogModelVerbatim() {
        // Custom / off-catalog models (e.g. an `alias/<name>` target or a local fine-tune) must reach the provider
        // as-is. The setter must not silently substitute the provider's default catalog model, or the live model
        // would diverge from what the UI shows and the session persists.
        var service = serviceWithFake(new FakeModel(), "openai", "gpt-5.5");
        service.setProviderAndModel("openai", "my-finetuned-model", "k");
        assertEquals("openai", service.provider());
        assertEquals("my-finetuned-model", service.model());
    }

    @Test
    void setThinkingLevelClearsCache() {
        var service = serviceWithFake(new FakeModel());
        service.setThinkingLevel(ThinkingLevel.LOW);
        assertEquals(ThinkingLevel.LOW, service.thinkingLevel());
    }

    @Test
    void setThinkingLevelNullFallsBackToOff() {
        var service = serviceWithFake(new FakeModel());
        service.setThinkingLevel(null);
        assertEquals(ThinkingLevel.OFF, service.thinkingLevel());
    }

    @Test
    void apiKeyResolverRefreshClearsCache() {
        var key = new AtomicReference<>("key1");
        var service = serviceWithKeyResolver(ignored -> key.get());

        service.streamMessages(List.of(new Message.User("hi")), List.of(), (ignoredChunk, ignoredType) -> {}).join();
        service.streamMessages(List.of(new Message.User("hi")), List.of(), (ignoredChunk, ignoredType) -> {}).join();

        key.set("key2");
        service.streamMessages(List.of(new Message.User("hi")), List.of(), (ignoredChunk, ignoredType) -> {}).join();

        assertEquals("key2", service.apiKey());
    }

    @Test
    void streamMessagesReturnsContent() {
        var fake = new FakeModel().withContent("hello world");
        var service = serviceWithFake(fake);

        var result = service.streamMessages(List.of(new Message.User("hi")), List.of(),
                                        (ignoredChunk, ignoredType) -> {}).join();

        assertEquals("hello world", result.content());
    }

    @Test
    void streamMessagesReturnsThinking() {
        var fake = new FakeModel().withThinking("thinking...").withContent("answer");
        var service = serviceWithFake(fake);

        var result = service.streamMessages(List.of(new Message.User("hi")), List.of(),
                                        (ignoredChunk, ignoredType) -> {}).join();

        assertEquals("thinking...", result.thinking());
        assertEquals("answer", result.content());
    }

    @Test
    void streamMessagesReturnsUsage() {
        var fake = new FakeModel().withContent("ok").withUsage(10, 5);
        var service = serviceWithFake(fake);

        var result = service.streamMessages(List.of(new Message.User("hi")), List.of(),
                                        (ignoredChunk, ignoredType) -> {}).join();

        assertNotNull(result.usage());
        assertEquals(10, result.usage().inputTokens());
        assertEquals(5, result.usage().outputTokens());
        assertEquals(10, service.totalInputTokens());
        assertEquals(5, service.totalOutputTokens());
    }

    @Test
    void streamMessagesReturnsToolCalls() {
        var request = ToolExecutionRequest.builder().id("tc1").name("bash").arguments("{}").build();
        var fake = new FakeModel().withToolCall(request);
        var service = serviceWithFake(fake);

        var result = service.streamMessages(List.of(new Message.User("hi")), List.of(),
                                        (ignoredChunk, ignoredType) -> {}).join();

        assertEquals(1, result.toolCalls().size());
        assertEquals("tc1", result.toolCalls().get(0).id());
    }

    @Test
    void streamMessagesPropagatesError() {
        var fake = new FakeModel().withError(new RuntimeException("boom"));
        var service = serviceWithFake(fake);

        var future = service.streamMessages(List.of(new Message.User("hi")), List.of(),
                                        (ignoredChunk, ignoredType) -> {});

        assertTrue(future.isCompletedExceptionally());
        assertThrows(Exception.class, future::join);
    }

    @Test
    void budgetLimitBlocksRequestsWhenOverBudget() {
        var fake = new FakeModel().withContent("ok").withUsage(1000, 1000);
        var service = serviceWithFake(fake);
        // Run one request to accumulate cost.
        service.streamMessages(List.of(new Message.User("hi")), List.of(), (ignoredChunk, ignoredType) -> {}).join();
        // Now set a tiny budget that is already exceeded. Use a priced catalog model so cost actually accrues
        // (cost is computed from the model's per-million rates; unpriced models contribute $0).
        var limited = new LlmService("anthropic", "claude-opus-4-5-20251101", ignored -> "test-key", null,
                                        List.of(new FakeProviderFactory(fake)), 0.0001);
        limited.streamMessages(List.of(new Message.User("hi")), List.of(), (ignoredChunk, ignoredType) -> {}).join();

        var result = limited.streamMessages(List.of(new Message.User("hi")), List.of(),
                                        (ignoredChunk, ignoredType) -> {}).join();
        assertEquals("[Budget limit exceeded]", result.content());
    }

    @Test
    void summarizeReturnsContent() {
        var fake = new FakeModel().withContent("summary");
        var service = serviceWithFake(fake);

        var result = service.summarize(List.of(new Message.User("text")), "summarize", null,
                                        (ignoredChunk, ignoredType) -> {}, null, null);

        assertEquals("summary", result);
    }

    @Test
    void summarizeWithCustomSystemPrompt() {
        var fake = new FakeModel().withContent("custom summary");
        var service = serviceWithFake(fake);

        var result = service.summarize(List.of(new Message.User("text")), "summarize", null,
                                        (ignoredChunk, ignoredType) -> {}, null, "Custom system");

        assertEquals("custom summary", result);
    }

    @Test
    void summarizeWithModelOverrideUsesDifferentModel() {
        var fake = new FakeModel().withContent("override summary");
        var service = serviceWithFake(fake);

        var result = service.summarize(List.of(new Message.User("text")), "summarize", null,
                                        (ignoredChunk, ignoredType) -> {}, "anthropic/claude-opus-4-5-20251101", null);

        assertEquals("override summary", result);
    }

    @Test
    void summarizeReturnsNullOnFailure() {
        var fake = new FakeModel().withError(new RuntimeException("boom"));
        var service = serviceWithFake(fake);

        var result = service.summarize(List.of(new Message.User("text")), "summarize", null,
                                        (ignoredChunk, ignoredType) -> {}, null, null);

        assertNull(result);
    }

    @Test
    void abortDoesNotThrowAndCanBeCalledRepeatedly() {
        var service = serviceWithFake(new FakeModel());
        assertDoesNotThrow(service::abort);
        assertDoesNotThrow(service::abort);
    }

    @Test
    void closeClearsCacheAndAborts() {
        var service = serviceWithFake(new FakeModel());
        service.close();
        assertDoesNotThrow(service::close);
    }

    @Test
    void modelChangeCallbackFiredWhenModelChanges() {
        var fake = new FakeModel().withContent("hi");
        var service = serviceWithFake(fake, "anthropic", "claude-opus-4-5-20251101");
        var calls = new AtomicInteger();
        service.setModelChangeCallback((ignoredOld, ignoredNew) -> calls.incrementAndGet());
        service.setModelName("claude-sonnet-4-5-20250929");
        assertEquals(1, calls.get());
    }

    @Test
    void modelChangeCallbackNotFiredWhenModelUnchanged() {
        var fake = new FakeModel().withContent("hi");
        var service = serviceWithFake(fake, "anthropic", "claude-opus-4-5-20251101");
        var calls = new AtomicInteger();
        service.setModelChangeCallback((ignoredOld, ignoredNew) -> calls.incrementAndGet());
        service.setModelName(service.model());
        assertEquals(0, calls.get());
    }

    @Test
    void toolSpecificationsAreSortedByName() {
        var fake = new FakeModel().withContent("ok");
        var service = serviceWithFake(fake);
        var toolA = new Tool.ToolDefinition("a-tool", "a", Map.of(),
                                        (id, args, cwd) -> CompletableFuture
                                                                        .completedFuture(Tool.ToolResult.success("ok")))
                                        .toTool();
        var toolB = new Tool.ToolDefinition("b-tool", "b", Map.of(),
                                        (id, args, cwd) -> CompletableFuture
                                                                        .completedFuture(Tool.ToolResult.success("ok")))
                                        .toTool();

        service.streamMessages(List.of(new Message.User("hi")), List.of(toolB, toolA),
                                        (ignoredChunk, ignoredType) -> {}).join();

        var request = fake.requests().get(0);
        assertEquals(List.of("a-tool", "b-tool"), request.tools().stream().map(ToolSpecification::name).toList());
    }

    // -- fakes --------------------------------------------------------------

    private record FakeRequest(List<Message> messages, List<ToolSpecification> tools) {
    }

    private static final class FakeModel implements StreamingChatModel {
        private final List<String> contentDeltas = new ArrayList<>();
        private final List<String> thinkingDeltas = new ArrayList<>();
        private final List<ToolExecutionRequest> toolCalls = new ArrayList<>();
        private TokenUsage tokenUsage;
        private RuntimeException error;
        private long prefillMillis; // held before the first token, so time-to-first-token is measurable
        private final List<FakeRequest> requests = new ArrayList<>();

        FakeModel withContent(String content) {
            this.contentDeltas.add(content);
            return this;
        }

        FakeModel withThinking(String thinking) {
            this.thinkingDeltas.add(thinking);
            return this;
        }

        FakeModel withUsage(int input, int output) {
            this.tokenUsage = new TokenUsage(input, output);
            return this;
        }

        FakeModel withUsage(TokenUsage usage) {
            this.tokenUsage = usage;
            return this;
        }

        FakeModel withToolCall(ToolExecutionRequest request) {
            this.toolCalls.add(request);
            return this;
        }

        /** Stalls before the first token, so a test can assert a prefill rate instead of dividing by ~0. */
        FakeModel withPrefillMillis(long millis) {
            this.prefillMillis = millis;
            return this;
        }

        FakeModel withError(RuntimeException error) {
            this.error = error;
            return this;
        }

        List<FakeRequest> requests() {
            return List.copyOf(requests);
        }

        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            requests.add(new FakeRequest(List.of(), request.toolSpecifications()));
            if (error != null) {
                handler.onError(error);
                return;
            }
            if (prefillMillis > 0) {
                try {
                    Thread.sleep(prefillMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            for (String t : thinkingDeltas) {
                handler.onPartialThinking(new PartialThinking(t), null);
            }
            StringBuilder content = new StringBuilder();
            for (String delta : contentDeltas) {
                content.append(delta);
                handler.onPartialResponse(new PartialResponse(content.toString()), null);
            }
            var ai = AiMessage.builder().text(content.toString())
                                            .thinking(thinkingDeltas.isEmpty() ? null : String.join("", thinkingDeltas))
                                            .toolExecutionRequests(toolCalls.isEmpty() ? null : toolCalls).build();
            var builder = ChatResponse.builder().aiMessage(ai);
            if (tokenUsage != null)
                builder.tokenUsage(tokenUsage);
            handler.onCompleteResponse(builder.build());
        }
    }

    @Test
    void usageTrackerPricesAgainstPerModelRates() {
        var tracker = new LlmService.UsageTracker(null);
        tracker.add(new Usage(1_000_000, 1_000_000), new ModelRegistry.Pricing(5.0, 25.0));
        assertEquals(30.0, tracker.totalCost(), 1e-9);
        assertFalse(tracker.hasUnpricedUsage());
    }

    @Test
    void usageTrackerFlagsUnpricedUsageWithoutInventingCost() {
        var tracker = new LlmService.UsageTracker(null);
        tracker.add(new Usage(1000, 1000), null);
        assertEquals(0.0, tracker.totalCost(), 1e-9);
        assertTrue(tracker.hasUnpricedUsage());
        assertEquals(2000, tracker.totalInputTokens() + tracker.totalOutputTokens());
    }

    private record FakeProviderFactory(FakeModel fake) implements ProviderFactory {
        @Override
        public StreamingChatModel create(String baseUrl, String apiKey, String modelName, String providerName,
                                        ThinkingLevel thinkingLevel) {
            return fake;
        }

        @Override
        public boolean supports(String provider) {
            return true;
        }
    }
}
