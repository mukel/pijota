package ai.pijota.agent;

import ai.pijota.config.AppConfig;
import ai.pijota.messages.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public final class CompactionManager {
    private static final int DEFAULT_CONTEXT_WINDOW = 128_000;
    private static final int DEFAULT_RESERVE_TOKENS = 16_384;
    private static final int DEFAULT_KEEP_RECENT_TOKENS = 20_000;
    private static final Logger log = LoggerFactory.getLogger(CompactionManager.class);

    private int contextWindow;
    private boolean enabled;
    private final int reserveTokens;
    private final int keepRecentTokens;
    private final SummaryGenerator summaryGenerator;
    private CompactionAppender sessionAppender;
    private String previousSummary;

    public CompactionManager() {
        this(null);
    }

    public CompactionManager(SummaryGenerator summaryGenerator) {
        this(DEFAULT_CONTEXT_WINDOW, new AppConfig.CompactionConfig(), summaryGenerator);
    }

    public CompactionManager(int contextWindow, AppConfig.CompactionConfig config,
                             SummaryGenerator summaryGenerator) {
        var cfg = config != null ? config : new AppConfig.CompactionConfig();
        this.contextWindow = contextWindow;
        this.enabled = cfg.enabled();
        this.reserveTokens = cfg.reserveTokens() > 0 ? cfg.reserveTokens() : DEFAULT_RESERVE_TOKENS;
        this.keepRecentTokens = cfg.keepRecentTokens() > 0 ? cfg.keepRecentTokens() : DEFAULT_KEEP_RECENT_TOKENS;
        this.summaryGenerator = summaryGenerator;
    }

    public void setContextWindow(int contextWindow) {
        if (contextWindow > 0) this.contextWindow = contextWindow;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setSessionAppender(CompactionAppender appender) {
        this.sessionAppender = appender;
    }

    public CompactionResult checkAndCompact(
            List<Message> messages,
            java.util.function.Consumer<List<Message>> onCompact) {

        int totalChars = estimateChars(messages);
        int totalTokens = estimateTokens(messages);
        int limit = Math.max(0, contextWindow - reserveTokens);

        if (!enabled || totalTokens < limit) {
            return new CompactionResult(false, totalChars, 0);
        }

        int compactedCount = compact(messages, null, onCompact);
        return new CompactionResult(true, totalChars, compactedCount);
    }

    public CompactionResult compactNow(
            List<Message> messages,
            String instructions,
            java.util.function.Consumer<List<Message>> onCompact) {
        int totalChars = estimateChars(messages);
        int compactedCount = compact(messages, instructions, onCompact);
        return new CompactionResult(compactedCount > 0, totalChars, compactedCount);
    }

    private int compact(List<Message> messages,
                         String instructions,
                         java.util.function.Consumer<List<Message>> onCompact) {
        int keepFrom = messages.size();
        int recentTokens = 0;

        for (int i = messages.size() - 1; i >= 1; i--) {
            recentTokens += estimateTokens(messages.get(i));
            keepFrom = i;
            if (recentTokens >= keepRecentTokens) break;
        }

        keepFrom = ensureToolCallOwners(messages, keepFrom);

        if (keepFrom <= 2) return 0;

        List<Message> toSummarize = messages.subList(1, keepFrom);
        String summary;
        if (summaryGenerator != null) {
            try {
                summary = summaryGenerator.generate(toSummarize, instructions, previousSummary);
            } catch (Exception e) {
                summary = null;
            }
        } else {
            summary = null;
        }
        if (summary == null || summary.isBlank()) {
            summary = "[degraded — LLM summarizer unavailable] "
                + summarize(toSummarize);
        }
        previousSummary = summary;

        var compactedList = new ArrayList<Message>();
        compactedList.add(messages.getFirst()); // keep system prompt
        compactedList.add(new Message.CompactionSummary(summary));
        compactedList.addAll(messages.subList(keepFrom, messages.size()));

        messages.clear();
        messages.addAll(compactedList);

        var firstKept = compactedList.size() > 2 ? compactedList.get(2).id().toString() : null;

        if (sessionAppender != null) {
            try {
                sessionAppender.accept(summary, keepFrom - 1, firstKept);
            } catch (Exception e) {
                log.warn("Failed to persist compaction summary", e);}
        }

        onCompact.accept(List.copyOf(compactedList));
        return keepFrom;
    }

    private static int ensureToolCallOwners(List<Message> messages, int keepFrom) {
        var kept = messages.subList(keepFrom, messages.size());
        var referencedIds = new HashSet<String>();
        for (var msg : kept) {
            if (msg instanceof Message.ToolResult tr) {
                referencedIds.add(tr.toolCallId());
            }
        }
        if (referencedIds.isEmpty()) return keepFrom;

        for (int i = keepFrom - 1; i >= 1; i--) {
            if (messages.get(i) instanceof Message.Assistant a && a.toolCalls() != null) {
                for (var tc : a.toolCalls()) {
                    if (referencedIds.remove(tc.id())) {
                        keepFrom = i;
                        break;
                    }
                }
            }
        }
        return keepFrom;
    }

    private String summarize(List<Message> toSummarize) {
        int users = 0, assistants = 0, toolResults = 0;
        for (var msg : toSummarize) {
            if (msg instanceof Message.User) users++;
            else if (msg instanceof Message.Assistant) assistants++;
            else if (msg instanceof Message.ToolResult) toolResults++;
        }
        return users + " user message(s), " + assistants + " assistant response(s), "
            + toolResults + " tool result(s)";
    }

    public static int estimateChars(List<Message> messages) {
        int total = 0;
        for (var msg : messages) total += estimateChars(msg);
        return total;
    }

    public static int estimateTokens(List<Message> messages) {
        return Math.max(1, estimateChars(messages) / 4);
    }

    public static int estimateTokens(Message msg) {
        return Math.max(1, estimateChars(msg) / 4);
    }

    public static int estimateChars(Message msg) {
        if (msg instanceof Message.User u) return u.content().length();
        if (msg instanceof Message.Assistant a)
            return (a.content() != null ? a.content().length() : 0) + 200;
        if (msg instanceof Message.ToolResult tr) return tr.content().length();
        if (msg instanceof Message.System s) return s.content().length();
        return 100;
    }

    @FunctionalInterface
    public interface SummaryGenerator {
        String generate(List<Message> toSummarize, String instructions, String previousSummary);
    }

    @FunctionalInterface
    public interface CompactionAppender {
        void accept(String summary, int compactedCount, String firstKeptEntryId);
    }

    public record CompactionResult(boolean compacted, int totalChars, int compactedCount) {}
}
