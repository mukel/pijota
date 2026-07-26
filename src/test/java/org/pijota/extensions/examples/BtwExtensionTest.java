package org.pijota.extensions.examples;

import org.pijota.extensions.testkit.FakeExtensionAPI;
import org.pijota.messages.Message;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BtwExtensionTest {

    private static final Executor INLINE = Runnable::run; // run the side task synchronously so asserts are
                                                          // deterministic

    /** A fake side-agent runner that records the task it was given and returns a canned answer (or throws). */
    private static final class FakeRunner implements BtwExtension.Runner {
        final AtomicReference<String> lastTask = new AtomicReference<>();
        volatile String answer = "ANSWER";
        volatile RuntimeException error;

        @Override
        public String run(String task) {
            lastTask.set(task);
            if (error != null)
                throw error;
            return answer;
        }
    }

    private FakeExtensionAPI initWith(FakeRunner runner) {
        var api = new FakeExtensionAPI();
        new BtwExtension(runner, INLINE).init(api);
        return api;
    }

    @Test
    void registersTheThreeSlashCommands() {
        var api = initWith(new FakeRunner());
        assertNotNull(api.slashCommand("btw"));
        assertNotNull(api.slashCommand("btw:tangent"));
        assertNotNull(api.slashCommand("btw:inject"));
    }

    @Test
    void btwRunsTheSideAgentAndPostsTheAnswerAsAHiddenCustomMessage() {
        var runner = new FakeRunner();
        runner.answer = "the file is Router.java";
        var api = initWith(runner);

        api.slashCommand("btw").handler().accept("which file defines the route?");

        // last custom message is the answer (custom = displayed but never added to the main agent's context)
        Message.Custom last = api.sentMessages().getLast();
        assertEquals("btw", last.customType());
        assertTrue(last.content().contains("the file is Router.java"), last.content());
        assertTrue(api.sentUserMessages().isEmpty(), "must NOT touch the main conversation");
    }

    @Test
    void contextualBtwSeedsTheRecentMainSessionTurns() {
        var runner = new FakeRunner();
        var api = new FakeExtensionAPI();
        api.seedMessage(new Message.User("we are refactoring the parser"));
        api.seedMessage(new Message.Assistant("done, parser.java updated", List.of(), null));
        new BtwExtension(runner, INLINE).init(api);

        api.slashCommand("btw").handler().accept("what changed?");

        String task = runner.lastTask.get();
        assertTrue(task.contains("Context from the current session"), task);
        assertTrue(task.contains("we are refactoring the parser"), task);
        assertTrue(task.contains("Side question: what changed?"), task);
    }

    @Test
    void tangentBtwOmitsTheContext() {
        var runner = new FakeRunner();
        var api = new FakeExtensionAPI();
        api.seedMessage(new Message.User("unrelated main work"));
        new BtwExtension(runner, INLINE).init(api);

        api.slashCommand("btw:tangent").handler().accept("brainstorm a cache design");

        String task = runner.lastTask.get();
        assertFalse(task.contains("Context from the current session"), task);
        assertFalse(task.contains("unrelated main work"), task);
        assertTrue(task.startsWith("Side question: brainstorm a cache design"), task);
    }

    @Test
    void injectSendsTheLastAnswerIntoTheMainAgent() {
        var runner = new FakeRunner();
        runner.answer = "use a bounded LRU";
        var api = initWith(runner);

        api.slashCommand("btw").handler().accept("which cache?");
        api.slashCommand("btw:inject").handler().accept("");

        String injected = api.sentUserMessages().getLast();
        assertTrue(injected.contains("[from /btw]"), injected);
        assertTrue(injected.contains("use a bounded LRU"), injected);
    }

    @Test
    void injectWithNoPriorAnswerIsANoOp() {
        var api = initWith(new FakeRunner());
        api.slashCommand("btw:inject").handler().accept("");
        assertTrue(api.sentUserMessages().isEmpty());
        assertTrue(api.lastSentMessage().toLowerCase().contains("nothing to inject"));
    }

    @Test
    void emptyQuestionShowsUsage() {
        var api = initWith(new FakeRunner());
        api.slashCommand("btw").handler().accept("   ");
        assertTrue(api.lastSentMessage().contains("Usage"));
        assertTrue(api.sentUserMessages().isEmpty());
    }

    @Test
    void aRunnerFailureBecomesANote() {
        var runner = new FakeRunner();
        runner.error = new RuntimeException("provider unavailable");
        var api = initWith(runner);

        api.slashCommand("btw").handler().accept("anything");

        assertTrue(api.lastSentMessage().contains("btw failed"), api.lastSentMessage());
        assertTrue(api.lastSentMessage().contains("provider unavailable"), api.lastSentMessage());
    }
}
