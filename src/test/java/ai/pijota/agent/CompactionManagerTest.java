package ai.pijota.agent;

import ai.pijota.config.AppConfig;
import ai.pijota.messages.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
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

        var result = manager.checkAndCompact(messages, msgs -> {});

        assertFalse(result.compacted());
    }
}
