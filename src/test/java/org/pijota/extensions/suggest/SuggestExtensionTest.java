package org.pijota.extensions.suggest;

import static org.junit.jupiter.api.Assertions.*;

import org.pijota.extensions.ExtensionEvent;
import org.pijota.extensions.UiContext;
import org.pijota.extensions.testkit.FakeExtensionAPI;
import org.pijota.extensions.testkit.FakeExtensionRuntime;
import org.pijota.extensions.testkit.FakeUiContext;
import org.pijota.llm.LlmClient;
import org.pijota.llm.StreamResult;
import org.pijota.llm.ThinkingLevel;
import org.pijota.messages.Message;
import org.pijota.tools.Tool;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuggestExtensionTest {

    /** Counts calls so "did not spend" can be asserted rather than assumed. */
    private static final class CountingLlm implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();
        volatile String modelUsed;
        volatile ThinkingLevel effortUsed;

        @Override
        public ThinkingLevel thinkingLevel() {
            return ThinkingLevel.MAX; // the session's setting, which the suggestion must not inherit
        }

        @Override
        public CompletableFuture<StreamResult> streamMessages(List<Message> messages, List<Tool> tools,
                                        BiConsumer<String, String> onPartial, ThinkingLevel thinking) {
            effortUsed = thinking;
            return streamMessages(messages, tools, onPartial);
        }

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
            modelUsed = model(); // whatever this client is, that is the model the suggestion used
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

    private static FakeExtensionRuntime lastRuntime;

    private static FakeExtensionAPI apiWith(UiContext ui, CountingLlm llm) {
        var runtime = new FakeExtensionRuntime();
        lastRuntime = runtime;
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
    // ---- customising the prompt ---------------------------------------------------------------------------------

    private static SuggestExtension withFlag(String value) {
        var runtime = new FakeExtensionRuntime();
        runtime.setFlag("suggest", value);
        var ext = new SuggestExtension();
        ext.init(new FakeExtensionAPI(runtime));
        return ext;
    }

    @Test
    void theBuiltInGuidanceIsUsedWhenNothingIsConfigured() {
        String instruction = withFlag("true").instruction();
        assertTrue(instruction.contains("most likely to type next"), instruction);
        assertTrue(instruction.contains("2 to 12 words"), "the format rules are always present");
    }

    @Test
    void aCustomPromptReplacesTheGuidance() {
        String instruction = withFlag("Only ever suggest running tests.").instruction();
        assertTrue(instruction.startsWith("Only ever suggest running tests."), instruction);
        assertFalse(instruction.contains("most likely to type next"), "the built-in guidance is replaced");
    }

    /**
     * The format rules survive a custom prompt on purpose: SuggestionText rejects a reply that breaks them, so a prompt
     * without them would produce output that is silently discarded -- which reads as the setting not working.
     */
    @Test
    void theFormatRulesSurviveACustomPrompt() {
        String instruction = withFlag("Say whatever you like, at length.").instruction();
        assertTrue(instruction.contains("2 to 12 words"), instruction);
        assertTrue(instruction.contains("nothing at all"), "including the say-nothing escape");
    }

    @Test
    void aValueNamingAFileIsReadFromIt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("SUGGEST.md");
        Files.writeString(file, "Suggest only git operations.");

        String instruction = withFlag(file.toString()).instruction();

        assertTrue(instruction.startsWith("Suggest only git operations."), "a long prompt can live in a file");
        assertTrue(instruction.contains("2 to 12 words"));
    }

    @Test
    void aValueThatIsNotAFileIsTakenLiterally() {
        String instruction = withFlag("/definitely/not/a/real/path/anywhere").instruction();
        assertTrue(instruction.startsWith("/definitely/not/a/real/path/anywhere"), "no crash, no empty prompt");
    }

    @Test
    void theBareOnSwitchIsNotMistakenForAPrompt() {
        for (String on : new String[] {"true", "TRUE", "  ", ""})
            assertTrue(withFlag(on).instruction().contains("most likely to type next"),
                                            "\"" + on + "\" is the on-switch, not guidance");
    }

    /**
     * A suggestion must be answered by the same model that answered the user. Not by convention: the extension holds
     * the session's own client, and {@code LlmClient.streamMessages} takes no model override, so there is no path by
     * which a different model could be used. This pins that -- if an override is ever added, it must be a deliberate
     * change here rather than a quiet one.
     *
     * <p>
     * The same model, but never the same reasoning effort. That one <em>is</em> overridden deliberately: reasoning is
     * the entire delay before a suggestion appears, and no amount of it improves a five-word sentence.
     */
    @Test
    void aSuggestionIsAnsweredByTheSessionsOwnModel() throws Exception {
        var llm = new CountingLlm();
        var ui = new DraftUi();
        var api = apiWith(ui, llm);
        lastRuntime.seedMessage(new Message.User(java.util.UUID.randomUUID(), java.time.Instant.now(),
                                        "add a zero guard", java.util.List.of()));
        ready(api);

        api.fire(new ExtensionEvent.AgentEnd());
        for (int i = 0; i < 100 && ui.offered == null; i++)
            Thread.sleep(20);

        assertEquals(1, llm.calls.get(), "exactly one call per turn");
        assertEquals("test-model", llm.modelUsed, "the session's model, never a substitute");
        assertEquals(ThinkingLevel.OFF, llm.effortUsed,
                                        "but never the session's effort (MAX here): thinking is the whole delay");
        assertEquals("run the tests", ui.offered, "and its reply became the offer");
    }

}
