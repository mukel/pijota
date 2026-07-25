package ai.pijota.agent;

import ai.pijota.messages.Message;
import ai.pijota.messages.Usage;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionUsageTest {
    @Test
    void groupsAssistantUsageBySourceModel() {
        var a = new Message.Assistant("one", List.of(), null).withSource("anthropic", "claude-opus-4-5-20251101")
                                        .withUsage(new Usage(1_000_000, 1_000_000));

        var breakdown = SessionUsage.breakdown(List.of(a), "openai", "gpt-4");

        assertEquals(1, breakdown.size());
        assertEquals("anthropic/claude-opus-4-5-20251101", breakdown.getFirst().key());
        assertEquals(2_000_000, breakdown.getFirst().tokens());
        assertTrue(breakdown.getFirst().cost() > 0);
    }

    @Test
    void readsCompactionUsageBreakdown() {
        var usage = Map.<String, Object>of("inputTokens", 10, "outputTokens", 5, "cacheReadTokens", 2,
                                        "cacheWriteTokens", 3, "reasoningTokens", 0);

        var breakdown = SessionUsage.compactionBreakdown(List.of(Map.of("type", "compaction", "usage", usage)));

        assertEquals(1, breakdown.size());
        assertEquals("Tools/summaries", breakdown.getFirst().key());
        assertEquals(15, breakdown.getFirst().tokens());
    }
}
