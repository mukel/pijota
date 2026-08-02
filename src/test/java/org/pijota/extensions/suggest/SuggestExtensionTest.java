package org.pijota.extensions.suggest;

import static org.junit.jupiter.api.Assertions.*;

import org.pijota.extensions.ExtensionEvent;
import org.pijota.extensions.UiContext;
import org.pijota.extensions.testkit.FakeExtensionAPI;
import org.pijota.extensions.testkit.FakeExtensionRuntime;
import org.pijota.extensions.testkit.FakeUiContext;
import org.pijota.llm.LlmClient;
import org.pijota.llm.StreamResult;
import org.pijota.messages.Message;
import org.pijota.tools.Tool;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

class SuggestExtensionTest {

    /** Counts calls so "did not spend" can be asserted rather than assumed. */
    private static final class CountingLlm implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String provider() {
            return "test";
        }

        @Override
        public String model() {
            return "test-model";
        }

        @Override
        public CompletableFuture<StreamResult> streamMessages(List<Message> messages, List<Tool> tools,
                                        BiConsumer<String, String> onPartial) {
            calls.incrementAndGet();
            var r = new StreamResult();
            r.setContent("run the tests");
            return CompletableFuture.completedFuture(r);
        }

        @Override
        public void abort() {}

        @Override
        public void close() {}
    }

    /** init() then onReady(): registration happens in onReady, once the frontend has reached the runtime. */
    private static void ready(FakeExtensionAPI api) {
        var ext = new SuggestExtension();
        ext.init(api);
        try {
            ext.onReady(api.runtime());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static FakeExtensionAPI apiWith(UiContext ui, CountingLlm llm) {
        var runtime = new FakeExtensionRuntime();
        runtime.setFlag("suggest", "true");
        runtime.setUi(ui);
        runtime.setLlm(llm);
        return new FakeExtensionAPI(runtime);
    }

    /** A frontend with a draft: the capability is honoured and the extension wires itself up. */
    private static final class DraftUi extends FakeUiContext {
        volatile String offered;

        @Override
        public boolean acceptsPromptDrafts() {
            return true;
        }

        @Override
        public void offerSuggestion(String text) {
            offered = text;
        }
    }

    @Test
    void isOffByDefaultBecauseItSpendsTokensNobodyAskedFor() {
        assertFalse(new SuggestExtension().enabledByDefault());
    }

    /**
     * The whole reason the capability is a query rather than a no-op: on a chat frontend there is nowhere to put a
     * draft, so the work must never start. Rendering nothing later would still have paid for the inference.
     */
    @Test
    void aFrontendWithNoDraftCostsNothingAtAll() {
        var llm = new CountingLlm();
        var api = apiWith(new FakeUiContext(), llm); // acceptsPromptDrafts() defaults to false

        ready(api);

        assertEquals(0, llm.calls.get(), "no call may be made for a frontend that cannot show the result");
        assertTrue(api.listeners(ExtensionEvent.AgentEnd.class).isEmpty(),
                                        "and nothing is registered, so none can be made later");
    }

    @Test
    void aHeadlessRuntimeWithNoUiIsAlsoFree() {
        var llm = new CountingLlm();
        var runtime = new FakeExtensionRuntime();
        runtime.setFlag("suggest", "true");
        runtime.setLlm(llm);
        runtime.setUi(null);
        var api = new FakeExtensionAPI(runtime);

        assertDoesNotThrow(() -> ready(api), "a null ui is normal in headless modes");
        assertEquals(0, llm.calls.get());
    }

    @Test
    void aFrontendWithADraftRegistersTheTurnHook() {
        var llm = new CountingLlm();
        var api = apiWith(new DraftUi(), llm);

        ready(api);

        assertFalse(api.listeners(ExtensionEvent.AgentEnd.class).isEmpty(),
                                        "the turn hook is registered when a draft exists");
        assertEquals(0, llm.calls.get(), "but nothing is spent until a turn actually ends");
    }
}
