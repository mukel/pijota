package org.pijota.agent;

import org.pijota.config.AppConfig;
import org.pijota.llm.SummaryResult;
import org.pijota.messages.Message;
import org.pijota.messages.Usage;
import org.pijota.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class CompactionManager {
    private static final int DEFAULT_CONTEXT_WINDOW = 128_000;
    private static final int DEFAULT_RESERVE_TOKENS = 16_384;
    private static final int DEFAULT_KEEP_RECENT_TOKENS = 20_000;
    private static final Logger log = LoggerFactory.getLogger(CompactionManager.class);

    private volatile int contextWindow;
    private volatile boolean enabled;
    private final int reserveTokens;
    private final int keepRecentTokens;
    private final SummaryGenerator summaryGenerator;
    private volatile CompactionAppender sessionAppender;
    private volatile String previousSummary;

    public CompactionManager(SummaryGenerator summaryGenerator) {
        this(DEFAULT_CONTEXT_WINDOW, new AppConfig.CompactionConfig(), summaryGenerator);
    }

    public CompactionManager(int contextWindow, AppConfig.CompactionConfig config, SummaryGenerator summaryGenerator) {
        var cfg = config != null ? config : new AppConfig.CompactionConfig();
        this.contextWindow = contextWindow;
        this.enabled = cfg.enabled();
        this.reserveTokens = cfg.reserveTokens() > 0 ? cfg.reserveTokens() : DEFAULT_RESERVE_TOKENS;
        this.keepRecentTokens = cfg.keepRecentTokens() > 0 ? cfg.keepRecentTokens() : DEFAULT_KEEP_RECENT_TOKENS;
        this.summaryGenerator = summaryGenerator;
    }

    public void setContextWindow(int contextWindow) {
        if (contextWindow > 0)
            this.contextWindow = contextWindow;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setSessionAppender(CompactionAppender appender) {
        this.sessionAppender = appender;
    }

    public CompactionResult checkAndCompact(List<Message> messages, long realContextTokens,
                                    Consumer<List<Message>> onCompact) {

        int estimatedChars = estimateChars(messages);
        // Trust the provider's real prompt size when available: it captures tool-schema tokens + true tokenization
        // the byte heuristic misses, while the heuristic captures cached message bytes the provider reports
        // separately — so the max of the two is the safe, conservative context-size estimate for the trigger.
        int contextTokens = (int) Math.max(realContextTokens, estimateTokens(messages));
        int limit = Math.max(0, contextWindow - reserveTokens);

        if (!enabled || contextTokens < limit) {
            return new CompactionResult(false, estimatedChars, contextTokens, 0);
        }

        int compactedCount = compact(messages, null, onCompact, null);
        return new CompactionResult(true, estimatedChars, contextTokens, compactedCount);
    }

    public CompactionResult compactNow(List<Message> messages, String instructions, Consumer<List<Message>> onCompact) {
        return compactNow(messages, instructions, onCompact, null);
    }

    public CompactionResult compactNow(List<Message> messages, String instructions, Consumer<List<Message>> onCompact,
                                    BiConsumer<String, String> onPartial) {
        int estimatedChars = estimateChars(messages);
        int estimatedTokens = estimateTokens(messages);
        int compactedCount = compact(messages, instructions, onCompact, onPartial);
        return new CompactionResult(compactedCount > 0, estimatedChars, estimatedTokens, compactedCount);
    }

    private int compact(List<Message> messages, String instructions, Consumer<List<Message>> onCompact,
                                    BiConsumer<String, String> onPartial) {
        int keepFrom = messages.size();
        int recentTokens = 0;

        for (int i = messages.size() - 1; i >= 1; i--) {
            recentTokens += estimateTokens(messages.get(i));
            keepFrom = i;
            if (recentTokens >= keepRecentTokens)
                break;
        }

        keepFrom = ensureToolCallOwners(messages, keepFrom);

        if (keepFrom <= 2)
            return 0;

        // A prior summary at the head is fed via the previousSummary param (the incremental-update prompt), not
        // re-summarized inline — so it's incorporated exactly once, and the update path also works after a reload
        // (when the in-memory previousSummary is null but the loaded history still carries the CompactionSummary).
        int from = 1;
        String priorSummary = previousSummary;
        if (messages.get(1) instanceof Message.CompactionSummary cs) {
            priorSummary = cs.summary();
            from = 2;
        }

        List<Message> toSummarize = sanitizedCopy(messages.subList(from, keepFrom));
        String summary;
        Usage usage = null;
        if (summaryGenerator != null) {
            try {
                var generated = summaryGenerator.generateWithUsage(toSummarize, instructions, priorSummary, onPartial);
                summary = generated != null ? generated.text() : null;
                usage = generated != null ? generated.usage() : null;
            } catch (Exception e) {
                summary = null;
            }
        } else {
            summary = null;
        }
        if (summary == null || summary.isBlank()) {
            summary = "[degraded — LLM summarizer unavailable] " + summarize(toSummarize);
        }
        summary = Util.stripSystemReminders(summary).strip();
        previousSummary = summary;

        var compactedList = new ArrayList<Message>();
        compactedList.add(messages.getFirst()); // keep system prompt
        compactedList.add(new Message.CompactionSummary(summary));
        compactedList.addAll(messages.subList(keepFrom, messages.size()));

        synchronized (messages) {
            messages.clear();
            messages.addAll(compactedList);
        }

        var firstKept = compactedList.size() > 2 ? compactedList.get(2).id().toString() : null;

        if (sessionAppender != null) {
            try {
                sessionAppender.accept(summary, keepFrom - 1, firstKept, usage);
            } catch (Exception e) {
                log.warn("Failed to persist compaction summary", e);
            }
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
        if (referencedIds.isEmpty())
            return keepFrom;

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
            if (msg instanceof Message.User)
                users++;
            else if (msg instanceof Message.Assistant)
                assistants++;
            else if (msg instanceof Message.ToolResult)
                toolResults++;
        }
        return users + " user message(s), " + assistants + " assistant response(s), " + toolResults + " tool result(s)";
    }

    private static List<Message> sanitizedCopy(List<Message> messages) {
        var out = new ArrayList<Message>();
        for (var msg : messages) {
            out.add(sanitize(msg));
        }
        return out;
    }

    private static Message sanitize(Message msg) {
        return switch (msg) {
            case Message.User u -> new Message.User(u.id(), u.timestamp(), strip(u.content()), u.attachments());
            case Message.Assistant a -> new Message.Assistant(a.id(), a.timestamp(), strip(a.content()), a.toolCalls(),
                                            strip(a.thinking()), a.sourceProvider(), a.sourceModel());
            case Message.ToolResult tr -> new Message.ToolResult(tr.id(), tr.timestamp(), tr.toolCallId(),
                                            tr.toolName(), strip(tr.content()), tr.isError(), tr.details());
            case Message.System s -> new Message.System(s.id(), s.timestamp(), strip(s.content()));
            case Message.CompactionSummary cs ->
                new Message.CompactionSummary(cs.id(), cs.timestamp(), strip(cs.summary()));
            case Message.BranchSummary bs -> new Message.BranchSummary(bs.id(), bs.timestamp(), strip(bs.summary()));
            case Message.Custom c -> c;
        };
    }

    private static String strip(String value) {
        return value == null ? null : Util.stripSystemReminders(value);
    }

    public static int estimateChars(List<Message> messages) {
        int total = 0;
        for (var msg : messages)
            total += charCount(msg);
        return total;
    }

    public static int estimateTokens(List<Message> messages) {
        int total = 0;
        for (var msg : messages)
            total += estimateTokens(msg);
        return Math.max(1, total);
    }

    public static int estimateChars(Message msg) {
        return charCount(msg);
    }

    public static int estimateTokens(Message msg) {
        return Math.max(1, tokenCount(msg));
    }

    private static int charCount(Message msg) {
        if (msg instanceof Message.User u)
            return u.content().length();
        if (msg instanceof Message.Assistant a) {
            int chars = a.content() != null ? a.content().length() : 0;
            if (a.thinking() != null)
                chars += a.thinking().length();
            return chars;
        }
        if (msg instanceof Message.ToolResult tr)
            return tr.content().length();
        if (msg instanceof Message.System s)
            return s.content().length();
        if (msg instanceof Message.CompactionSummary cs)
            return cs.summary().length();
        if (msg instanceof Message.BranchSummary bs)
            return bs.summary().length();
        return 0;
    }

    private static int tokenCount(Message msg) {
        if (msg instanceof Message.User u)
            return estimateTextTokens(u.content());
        if (msg instanceof Message.Assistant a) {
            int tokens = estimateTextTokens(a.content());
            if (a.thinking() != null)
                tokens += estimateTextTokens(a.thinking());
            if (a.toolCalls() != null)
                for (var tc : a.toolCalls()) // the serialized call (name + JSON arguments), not a flat per-call guess
                    tokens += estimateTextTokens(tc.name() + (tc.arguments() != null ? tc.arguments() : ""));
            return tokens;
        }
        if (msg instanceof Message.ToolResult tr)
            return estimateTextTokens(tr.content());
        if (msg instanceof Message.System s)
            return estimateTextTokens(s.content());
        if (msg instanceof Message.CompactionSummary cs)
            return estimateTextTokens(cs.summary());
        if (msg instanceof Message.BranchSummary bs)
            return estimateTextTokens(bs.summary());
        return 100;
    }

    static int estimateTextTokens(String text) {
        if (text == null || text.isEmpty())
            return 0;
        return (int) Util.approxTokens(text.length());
    }

    public interface SummaryGenerator {
        String generate(List<Message> toSummarize, String instructions, String previousSummary,
                                        BiConsumer<String, String> onPartial);

        default SummaryResult generateWithUsage(List<Message> toSummarize, String instructions, String previousSummary,
                                        BiConsumer<String, String> onPartial) {
            String text = generate(toSummarize, instructions, previousSummary, onPartial);
            return text != null ? new SummaryResult(text, null) : null;
        }
    }

    @FunctionalInterface
    public interface CompactionAppender {
        void accept(String summary, int compactedCount, String firstKeptEntryId, Usage usage);
    }

    public record CompactionResult(boolean compacted, int estimatedChars, int estimatedTokens, int compactedCount) {
    }
}
