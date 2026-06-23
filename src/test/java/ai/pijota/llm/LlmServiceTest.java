package ai.pijota.llm;

import static org.junit.jupiter.api.Assertions.*;

import ai.pijota.messages.Message;
import ai.pijota.tools.Tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class LlmServiceTest {

    private static LlmService serviceWithFake(FakeModel fake) {
        return new LlmService("openai", "gpt-4o", _ -> "test-key", null, List.of(new FakeProviderFactory(fake)), null);
    }

    private static LlmService serviceWithFake(FakeModel fake, String provider, String model) {
        return new LlmService(provider, model, _ -> "test-key", null, List.of(new FakeProviderFactory(fake)), null);
    }

    private static LlmService serviceWithKeyResolver(java.util.function.Function<String, String> resolver) {
        return new LlmService("openai", "gpt-4o", resolver, null,
                                        List.of(new FakeProviderFactory(new FakeModel().withContent("ok"))), null);
    }

    @Test
    void constructorDefaultsProviderFromOpenAiKey() {
        var service = new LlmService(null, null, _ -> "sk-openai", null,
                                        List.of(new FakeProviderFactory(new FakeModel())), null);
        assertEquals("openai", service.provider());
    }

    @Test
    void constructorDefaultsProviderFromAnthropicKey() {
        var service = new LlmService(null, null, _ -> "sk-ant-xxx", null,
                                        List.of(new FakeProviderFactory(new FakeModel())), null);
        assertEquals("anthropic", service.provider());
    }

    @Test
    void constructorFallsBackToOpenAiWhenNoKey() {
        var service = new LlmService(null, null, _ -> null, null, List.of(new FakeProviderFactory(new FakeModel())),
                                        null);
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
        // as-is — the setter must not silently substitute the provider's default catalog model, or the live model
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
        var service = serviceWithKeyResolver(_ -> key.get());

        service.streamMessages(List.of(new Message.User("hi")), List.of(), (_c, _t) -> {}).join();
        service.streamMessages(List.of(new Message.User("hi")), List.of(), (_c, _t) -> {}).join();

        key.set("key2");
        service.streamMessages(List.of(new Message.User("hi")), List.of(), (_c, _t) -> {}).join();

        assertEquals("key2", service.apiKey());
    }

    @Test
    void streamMessagesReturnsContent() {
        var fake = new FakeModel().withContent("hello world");
        var service = serviceWithFake(fake);

        var result = service.streamMessages(List.of(new Message.User("hi")), List.of(), (_c, _t) -> {}).join();

        assertEquals("hello world", result.content());
    }

    @Test
    void streamMessagesReturnsThinking() {
        var fake = new FakeModel().withThinking("thinking...").withContent("answer");
        var service = serviceWithFake(fake);

        var result = service.streamMessages(List.of(new Message.User("hi")), List.of(), (_c, _t) -> {}).join();

        assertEquals("thinking...", result.thinking());
        assertEquals("answer", result.content());
    }

    @Test
    void streamMessagesReturnsUsage() {
        var fake = new FakeModel().withContent("ok").withUsage(10, 5);
        var service = serviceWithFake(fake);

        var result = service.streamMessages(List.of(new Message.User("hi")), List.of(), (_c, _t) -> {}).join();

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

        var result = service.streamMessages(List.of(new Message.User("hi")), List.of(), (_c, _t) -> {}).join();

        assertEquals(1, result.toolCalls().size());
        assertEquals("tc1", result.toolCalls().get(0).id());
    }

    @Test
    void streamMessagesPropagatesError() {
        var fake = new FakeModel().withError(new RuntimeException("boom"));
        var service = serviceWithFake(fake);

        var future = service.streamMessages(List.of(new Message.User("hi")), List.of(), (_c, _t) -> {});

        assertTrue(future.isCompletedExceptionally());
        assertThrows(Exception.class, future::join);
    }

    @Test
    void budgetLimitBlocksRequestsWhenOverBudget() {
        var fake = new FakeModel().withContent("ok").withUsage(1000, 1000);
        var service = serviceWithFake(fake);
        // Run one request to accumulate cost.
        service.streamMessages(List.of(new Message.User("hi")), List.of(), (_c, _t) -> {}).join();
        // Now set a tiny budget that is already exceeded. Use a priced catalog model so cost actually accrues
        // (cost is computed from the model's per-million rates; unpriced models contribute $0).
        var limited = new LlmService("anthropic", "claude-opus-4-5-20251101", _ -> "test-key", null,
                                        List.of(new FakeProviderFactory(fake)), 0.0001);
        limited.streamMessages(List.of(new Message.User("hi")), List.of(), (_c, _t) -> {}).join();

        var result = limited.streamMessages(List.of(new Message.User("hi")), List.of(), (_c, _t) -> {}).join();
        assertEquals("[Budget limit exceeded]", result.content());
    }

    @Test
    void summarizeReturnsContent() {
        var fake = new FakeModel().withContent("summary");
        var service = serviceWithFake(fake);

        var result = service.summarize(List.of(new Message.User("text")), "summarize", null, (_c, _t) -> {}, null,
                                        null);

        assertEquals("summary", result);
    }

    @Test
    void summarizeWithCustomSystemPrompt() {
        var fake = new FakeModel().withContent("custom summary");
        var service = serviceWithFake(fake);

        var result = service.summarize(List.of(new Message.User("text")), "summarize", null, (_c, _t) -> {}, null,
                                        "Custom system");

        assertEquals("custom summary", result);
    }

    @Test
    void summarizeWithModelOverrideUsesDifferentModel() {
        var fake = new FakeModel().withContent("override summary");
        var service = serviceWithFake(fake);

        var result = service.summarize(List.of(new Message.User("text")), "summarize", null, (_c, _t) -> {},
                                        "anthropic/claude-opus-4-5-20251101", null);

        assertEquals("override summary", result);
    }

    @Test
    void summarizeReturnsNullOnFailure() {
        var fake = new FakeModel().withError(new RuntimeException("boom"));
        var service = serviceWithFake(fake);

        var result = service.summarize(List.of(new Message.User("text")), "summarize", null, (_c, _t) -> {}, null,
                                        null);

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
        service.setModelChangeCallback((_old, _new) -> calls.incrementAndGet());
        service.setModelName("claude-sonnet-4-5-20250929");
        assertEquals(1, calls.get());
    }

    @Test
    void modelChangeCallbackNotFiredWhenModelUnchanged() {
        var fake = new FakeModel().withContent("hi");
        var service = serviceWithFake(fake, "anthropic", "claude-opus-4-5-20251101");
        var calls = new AtomicInteger();
        service.setModelChangeCallback((_old, _new) -> calls.incrementAndGet());
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

        service.streamMessages(List.of(new Message.User("hi")), List.of(toolB, toolA), (_c, _t) -> {}).join();

        var request = fake.requests().get(0);
        assertEquals(List.of("a-tool", "b-tool"), request.tools().stream()
                                        .map(dev.langchain4j.agent.tool.ToolSpecification::name).toList());
    }

    // -- fakes --------------------------------------------------------------

    private record FakeRequest(List<Message> messages, List<dev.langchain4j.agent.tool.ToolSpecification> tools) {
    }

    private static final class FakeModel implements StreamingChatModel {
        private final List<String> contentDeltas = new ArrayList<>();
        private final List<String> thinkingDeltas = new ArrayList<>();
        private final List<ToolExecutionRequest> toolCalls = new ArrayList<>();
        private TokenUsage tokenUsage;
        private RuntimeException error;
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

        FakeModel withToolCall(ToolExecutionRequest request) {
            this.toolCalls.add(request);
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
            for (String t : thinkingDeltas) {
                handler.onPartialThinking(new PartialThinking(t), null);
            }
            StringBuilder content = new StringBuilder();
            for (String delta : contentDeltas) {
                content.append(delta);
                handler.onPartialResponse(new PartialResponse(content.toString()), null);
            }
            var ai = dev.langchain4j.data.message.AiMessage.builder().text(content.toString())
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
        tracker.add(new ai.pijota.messages.Usage(1_000_000, 1_000_000), new ModelRegistry.Pricing(5.0, 25.0));
        assertEquals(30.0, tracker.totalCost(), 1e-9);
        assertFalse(tracker.hasUnpricedUsage());
    }

    @Test
    void usageTrackerFlagsUnpricedUsageWithoutInventingCost() {
        var tracker = new LlmService.UsageTracker(null);
        tracker.add(new ai.pijota.messages.Usage(1000, 1000), null);
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
