package org.pijota.agent;

import org.pijota.config.AppConfig;
import org.pijota.messages.Message;
import org.pijota.messages.ToolCall;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

class CompactionManagerTest {
    private static final AppConfig.CompactionConfig TINY_RECENT = new AppConfig.CompactionConfig(true, 100, 1);

    @Test
    void estimateTokensAndCharsApproximateContentLength() {
        var msgs = List.<Message>of(new Message.User("hello world"), new Message.Assistant("hi", List.of(), null));

        assertTrue(CompactionManager.estimateTokens(msgs) > 0);
        assertTrue(CompactionManager.estimateChars(msgs) > 0);
    }

    @Test
    void estimateTextTokensUsesCharDivFourRounding() {
        assertEquals(1, CompactionManager.estimateTextTokens("1234"));
        assertEquals(0, CompactionManager.estimateTextTokens(""));
        assertEquals(0, CompactionManager.estimateTextTokens(null));
    }

    @Test
    void assistantToolCallTokensScaleWithArgumentSize() {
        var small = new Message.Assistant("", List.of(new ToolCall("i", "read", "{}")), null);
        var large = new Message.Assistant("",
                                        List.of(new ToolCall("i", "read", "{\"path\":\"" + "x".repeat(4000) + "\"}")),
                                        null);
        // Tool calls are sized by their serialized name + arguments, not a flat per-call constant.
        assertTrue(CompactionManager.estimateTokens(large) > CompactionManager.estimateTokens(small) + 500);
    }

    @Test
    void compactNowInsertsSummaryAndPreservesSystemPrompt() {
        var summarizer = (CompactionManager.SummaryGenerator) (toSummarize, instructions, prevSummary,
                                        onPartial) -> "compacted-summary";
        var manager = new CompactionManager(128_000, TINY_RECENT, summarizer);

        var messages = new ArrayList<>(List.<Message>of(new Message.System("system prompt"),
                                        new Message.User("old msg 1"),
                                        new Message.Assistant("old reply 1", List.of(), null),
                                        new Message.User("a".repeat(4000))));

        manager.compactNow(messages, "compact it", msgs -> {});

        assertEquals("system prompt", ((Message.System) messages.getFirst()).content());
        assertTrue(messages.size() < 4, "should be compacted, size: " + messages.size());
    }

    @Test
    void compactionResultReportsCompactedState() {
        var summarizer = (CompactionManager.SummaryGenerator) (toSummarize, instructions, prevSummary,
                                        onPartial) -> "summary";
        var manager = new CompactionManager(128_000, TINY_RECENT, summarizer);

        var messages = new ArrayList<>(List.<Message>of(new Message.System("sys"), new Message.User("old msg 1"),
                                        new Message.Assistant("old reply 1", List.of(), null),
                                        new Message.User("a".repeat(4000))));

        var result = manager.compactNow(messages, "compact", msgs -> {});

        assertTrue(result.compacted());
        assertTrue(result.compactedCount() > 0);
    }

    @Test
    void disabledManagerDoesNotCompactEvenWhenOverLimit() {
        var summarizer = new CompactionManager.SummaryGenerator() {
            @Override
            public String generate(List<Message> m, String i, String p, BiConsumer<String, String> o) {
                fail("should not be called");
                return "";
            }
        };
        var manager = new CompactionManager(128_000, TINY_RECENT, summarizer);
        manager.setEnabled(false);

        var messages = new ArrayList<>(List.<Message>of(new Message.System("sys"), new Message.User("a".repeat(1000))));

        var result = manager.checkAndCompact(messages, 0, msgs -> {});

        assertFalse(result.compacted());
    }

    @Test
    void checkAndCompactUnderLimitDoesNotCompact() {
        var summarizer = (CompactionManager.SummaryGenerator) (m, i, p, o) -> {
            fail("should not be called");
            return "";
        };
        var manager = new CompactionManager(128_000, TINY_RECENT, summarizer);

        var messages = new ArrayList<>(List.<Message>of(new Message.System("sys"), new Message.User("hi")));

        var result = manager.checkAndCompact(messages, 0, msgs -> {});

        assertFalse(result.compacted());
        assertEquals(2, messages.size());
    }

    @Test
    void checkAndCompactOverLimitCompacts() {
        var summarizer = (CompactionManager.SummaryGenerator) (m, i, p, o) -> "summary";
        var manager = new CompactionManager(1000, TINY_RECENT, summarizer);

        var messages = new ArrayList<>(List.<Message>of(new Message.System("sys"), new Message.User("a".repeat(4000))));

        var result = manager.checkAndCompact(messages, 0, msgs -> {});

        assertTrue(result.compacted());
    }

    @Test
    void checkAndCompactTriggersOnRealContextTokensWhenByteEstimateIsUnderLimit() {
        var summarizer = (CompactionManager.SummaryGenerator) (m, i, p, o) -> "summary";
        var manager = new CompactionManager(1000, TINY_RECENT, summarizer); // limit = 1000 - 100 = 900
        // A handful of tiny messages: the byte estimate is a few tokens, well under 900 → would NOT trigger alone.
        var messages = new ArrayList<>(List.<Message>of(new Message.System("sys"), new Message.User("a"),
                                        new Message.User("b"), new Message.User("c"), new Message.User("d")));

        // The provider reports a real 2000-token context (tool schemas + cache the byte estimate misses) → must
        // compact.
        var result = manager.checkAndCompact(messages, 2000, msgs -> {});

        assertTrue(result.compacted(), "the provider's real context size should drive the trigger");
    }

    @Test
    void absoluteTriggerFiresBelowTheWindowDerivedLimit() {
        var summarizer = (CompactionManager.SummaryGenerator) (m, i, p, o) -> "summary";
        // A 1M window with the default reserve would only auto-compact near ~1M; an absolute trigger of 1000 must
        // shrink that limit so a ~1200-token context compacts now.
        var cfg = new AppConfig.CompactionConfig(true, 16384, 1, 1000);
        var manager = new CompactionManager(1_000_000, cfg, summarizer);
        var messages = new ArrayList<>(List.<Message>of(new Message.System("sys"), new Message.User("a".repeat(4800))));

        var result = manager.checkAndCompact(messages, 0, msgs -> {});

        assertTrue(result.compacted(), "a 1200-token context exceeds the 1000 absolute trigger despite the huge window");
    }

    @Test
    void noAbsoluteTriggerKeepsTheWindowDerivedLimit() {
        var summarizer = (CompactionManager.SummaryGenerator) (m, i, p, o) -> "summary";
        // trigger_tokens = 0 (default): a 1200-token context is far below the 1M-window limit, so nothing happens.
        var cfg = new AppConfig.CompactionConfig(true, 16384, 1, 0);
        var manager = new CompactionManager(1_000_000, cfg, summarizer);
        var messages = new ArrayList<>(List.<Message>of(new Message.System("sys"), new Message.User("a".repeat(4800))));

        var result = manager.checkAndCompact(messages, 0, msgs -> {});

        assertFalse(result.compacted(), "without an absolute trigger, the near-window limit governs");
    }

    @Test
    void recompactionFeedsPriorSummaryViaParamNotInlineBody() {
        var seenPrev = new AtomicReference<String>();
        var bodyHadSummary = new AtomicReference<>(false);
        var summarizer = (CompactionManager.SummaryGenerator) (toSummarize, instr, prev, onPartial) -> {
            seenPrev.set(prev);
            bodyHadSummary.set(toSummarize.stream().anyMatch(m -> m instanceof Message.CompactionSummary));
            return "new summary";
        };
        var manager = new CompactionManager(128_000, TINY_RECENT, summarizer);
        var messages = new ArrayList<>(List.<Message>of(new Message.System("sys"),
                                        new Message.CompactionSummary("OLD SUMMARY"), new Message.User("u1"),
                                        new Message.User("u2"), new Message.User("recent " + "x".repeat(4000))));

        manager.compactNow(messages, null, msgs -> {});

        assertEquals("OLD SUMMARY", seenPrev.get(), "prior summary should arrive via the previousSummary param");
        assertFalse(bodyHadSummary.get(), "prior summary should not be re-summarized inline in the body");
    }

    @Test
    void compactNowWithNullSummaryGeneratorFallsBackToDegradedSummary() {
        var manager = new CompactionManager(128_000, TINY_RECENT, null);
        var messages = new ArrayList<>(List.<Message>of(new Message.System("sys"), new Message.User("old"),
                                        new Message.User("middle"),
                                        new Message.User("recent big " + "x".repeat(4000))));

        var result = manager.compactNow(messages, "compact", msgs -> {});

        assertTrue(result.compacted());
        assertTrue(messages.stream().anyMatch(m -> m instanceof Message.CompactionSummary cs
                                        && cs.summary().startsWith("[degraded")));
    }

    @Test
    void compactNowWithThrowingSummaryGeneratorFallsBackToDegradedSummary() {
        var summarizer = (CompactionManager.SummaryGenerator) (m, i, p, o) -> {
            throw new RuntimeException("llm down");
        };
        var manager = new CompactionManager(128_000, TINY_RECENT, summarizer);
        var messages = new ArrayList<>(List.<Message>of(new Message.System("sys"), new Message.User("old"),
                                        new Message.User("middle"),
                                        new Message.User("recent big " + "x".repeat(4000))));

        var result = manager.compactNow(messages, "compact", msgs -> {});

        assertTrue(result.compacted());
        assertTrue(messages.stream().anyMatch(m -> m instanceof Message.CompactionSummary cs
                                        && cs.summary().startsWith("[degraded")));
    }

    @Test
    void ensureToolCallOwnersKeepsOwningAssistantInContext() {
        var summarizer = (CompactionManager.SummaryGenerator) (m, i, p, o) -> "summary";
        var manager = new CompactionManager(128_000, TINY_RECENT, summarizer);
        var tc = new ToolCall("tc1", "read", "{}");
        var messages = new ArrayList<>(List.<Message>of(new Message.System("sys"), new Message.User("first"),
                                        new Message.User("second"), new Message.Assistant("call", List.of(tc), null),
                                        new Message.User("a".repeat(4000)),
                                        new Message.ToolResult("tc1", "read", "ok", false)));

        manager.compactNow(messages, "compact", msgs -> {});

        assertTrue(messages.stream().anyMatch(m -> m instanceof Message.Assistant));
        assertTrue(messages.stream().anyMatch(m -> m instanceof Message.ToolResult));
    }

    @Test
    void sanitizeStripsSystemRemindersFromSummaryInput() {
        var summarizer = (CompactionManager.SummaryGenerator) (m, i, p, o) -> {
            for (var msg : m) {
                if (msg instanceof Message.User u && u.content().contains("<system-reminder>"))
                    fail("system reminders should be stripped before summarization");
            }
            return "summary";
        };
        var manager = new CompactionManager(128_000, TINY_RECENT, summarizer);
        var messages = new ArrayList<>(List.<Message>of(new Message.System("sys"),
                                        new Message.User("hello <system-reminder>secret</system-reminder> world"),
                                        new Message.User("middle"),
                                        new Message.User("recent big " + "x".repeat(4000))));

        manager.compactNow(messages, "compact", msgs -> {});
    }

    @Test
    void previousSummaryIsPassedToSummarizer() {
        var capturedPrev = new AtomicReference<String>();
        var summarizer = (CompactionManager.SummaryGenerator) (m, i, prev, o) -> {
            capturedPrev.set(prev);
            return "new summary";
        };
        var manager = new CompactionManager(128_000, TINY_RECENT, summarizer);
        var messages = new ArrayList<>(List.<Message>of(new Message.System("sys"), new Message.User("old"),
                                        new Message.User("middle"),
                                        new Message.User("recent big " + "x".repeat(4000))));
        manager.compactNow(messages, "first", msgs -> {});

        var messages2 = new ArrayList<>(List.<Message>of(new Message.System("sys"), new Message.User("middle"),
                                        new Message.User("another"),
                                        new Message.User("recent big " + "x".repeat(4000))));
        manager.compactNow(messages2, "second", msgs -> {});

        assertEquals("new summary", capturedPrev.get());
    }

    @Test
    void sessionAppenderIsInvokedAndExceptionsAreSwallowed() {
        var summarizer = (CompactionManager.SummaryGenerator) (m, i, p, o) -> "summary";
        var manager = new CompactionManager(128_000, TINY_RECENT, summarizer);
        var callCount = new AtomicInteger(0);
        manager.setSessionAppender((summary, count, firstKeptId, usage) -> {
            callCount.incrementAndGet();
            throw new RuntimeException("persist failed");
        });
        var messages = new ArrayList<>(List.<Message>of(new Message.System("sys"), new Message.User("old"),
                                        new Message.User("middle"),
                                        new Message.User("recent big " + "x".repeat(4000))));

        assertDoesNotThrow(() -> manager.compactNow(messages, "compact", msgs -> {}));
        assertEquals(1, callCount.get());
    }

    @Test
    void onPartialCallbackReceivesSummaryStream() {
        var summarizer = (CompactionManager.SummaryGenerator) (m, i, p, o) -> {
            o.accept("partial", "thinking");
            return "final";
        };
        var manager = new CompactionManager(128_000, TINY_RECENT, summarizer);
        var content = new AtomicReference<String>();
        var thinking = new AtomicReference<String>();
        var messages = new ArrayList<>(List.<Message>of(new Message.System("sys"), new Message.User("old"),
                                        new Message.User("middle"),
                                        new Message.User("recent big " + "x".repeat(4000))));

        manager.compactNow(messages, "compact", msgs -> {}, (c, t) -> {
            content.set(c);
            thinking.set(t);
        });

        assertEquals("partial", content.get());
        assertEquals("thinking", thinking.get());
    }

    @Test
    void setContextWindowIgnoresNonPositiveValues() {
        var manager = new CompactionManager(128_000, TINY_RECENT, (m, i, p, o) -> "summary");
        manager.setContextWindow(-1);
        manager.setContextWindow(0);
        // compact should still work after invalid setContextWindow calls
        var messages = new ArrayList<>(List.<Message>of(new Message.System("sys"), new Message.User("old"),
                                        new Message.User("middle"),
                                        new Message.User("recent big " + "x".repeat(4000))));
        assertTrue(manager.compactNow(messages, "compact", msgs -> {}).compacted());
    }

    @Test
    void compactNowWithTooFewMessagesReturnsZero() {
        var summarizer = (CompactionManager.SummaryGenerator) (m, i, p, o) -> {
            fail("should not be called");
            return "";
        };
        var manager = new CompactionManager(128_000, TINY_RECENT, summarizer);
        var messages = new ArrayList<>(List.<Message>of(new Message.System("sys"), new Message.User("only")));

        var result = manager.compactNow(messages, "compact", msgs -> {});

        assertFalse(result.compacted());
        assertEquals(0, result.compactedCount());
    }

    @Test
    void estimateTokensPerMessageType() {
        var user = new Message.User("1234");
        var assistant = new Message.Assistant("1234", List.of(new ToolCall("id", "name", "{}")), "think");
        var toolResult = new Message.ToolResult("id", "name", "1234", false);
        var system = new Message.System("1234");
        var compactionSummary = new Message.CompactionSummary("1234");
        var branchSummary = new Message.BranchSummary("1234");
        var custom = new Message.Custom("type", "1234");

        assertEquals(1, CompactionManager.estimateTokens(user));
        assertTrue(CompactionManager.estimateTokens(assistant) > 1);
        assertEquals(1, CompactionManager.estimateTokens(toolResult));
        assertEquals(1, CompactionManager.estimateTokens(system));
        assertEquals(1, CompactionManager.estimateTokens(compactionSummary));
        assertEquals(1, CompactionManager.estimateTokens(branchSummary));
        assertEquals(100, CompactionManager.estimateTokens(custom));
    }

    @Test
    void estimateCharsPerMessageType() {
        var assistant = new Message.Assistant("1234", List.of(), "5678");
        var custom = new Message.Custom("type", "1234");

        assertEquals(8, CompactionManager.estimateChars(assistant));
        assertEquals(0, CompactionManager.estimateChars(custom));
    }

    /**
     * The two compaction budgets had no test: only {@code enabled} did. Both decide when a conversation is rewritten,
     * so a settings file that changes them and is ignored means a user asked for less compaction and did not get it.
     * Same conversation, two budgets, opposite outcomes.
     */
    @Test
    void reserveTokensDecidesWhenTheWindowCountsAsFull() {
        var summarizer = (CompactionManager.SummaryGenerator) (m, i, p, o) -> "summary";
        var messages = List.<Message>of(new Message.System("sys"), new Message.User("a".repeat(4000)));

        var roomy = new CompactionManager(4000, new AppConfig.CompactionConfig(true, 1, 200), summarizer);
        var greedy = new CompactionManager(4000, new AppConfig.CompactionConfig(true, 3800, 200), summarizer);

        assertFalse(roomy.checkAndCompact(new ArrayList<>(messages), 0, msgs -> {}).compacted(),
                                        "reserving one token leaves the window nearly all usable");
        assertTrue(greedy.checkAndCompact(new ArrayList<>(messages), 0, msgs -> {}).compacted(),
                                        "reserving almost the whole window makes the same conversation too big");
    }

    /** A zero or negative budget means "use the default", not "reserve nothing" -- which would compact forever. */
    @Test
    void nonPositiveBudgetsFallBackToTheDefaults() {
        var summarizer = (CompactionManager.SummaryGenerator) (m, i, p, o) -> "summary";
        var messages = List.<Message>of(new Message.System("sys"), new Message.User("hi"));

        for (var config : List.of(new AppConfig.CompactionConfig(true, 0, 0),
                                        new AppConfig.CompactionConfig(true, -1, -1)))
            assertFalse(new CompactionManager(128_000, config, summarizer)
                                            .checkAndCompact(new ArrayList<>(messages), 0, msgs -> {}).compacted(),
                                            "a tiny conversation must not compact: " + config);
    }

}
