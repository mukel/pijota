package org.pijota.tui;

import org.pijota.agent.CompactionManager;
import org.pijota.agent.PijotaSession;
import org.pijota.agent.SessionExporter;
import org.pijota.agent.SessionUsage;
import org.pijota.config.SettingsManager;
import org.pijota.llm.ModelRegistry;
import org.pijota.llm.ThinkingLevel;
import org.pijota.messages.Message;
import org.pijota.messages.Usage;
import org.pijota.util.Clipboard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import static org.pijota.util.Util.fmtCount;
import static org.pijota.util.Util.plural;

final class SessionCommands {
    interface Host extends TuiHost {
        PijotaSession session();

        String sessionName();

        void setSessionName(String name);

        String provider();

        String model();

        String cwd();

        ThinkingLevel thinkingLevel();

        SettingsManager settings();

        String tokenStatus();

        void refreshSessionName();

        /** Asks the user a yes/no question; completes with the answer when they respond (false on cancel). */
        CompletableFuture<Boolean> confirm(String question);
    }

    private final Host host;

    SessionCommands(Host host) {
        this.host = host;
    }

    void saveSession(String path) {
        if (path.startsWith("/save "))
            path = path.substring(6).trim();
        else if (path.startsWith("/save"))
            path = path.substring(5).trim();
        if (path.isBlank()) {
            String ts = Instant.now().toString().replace(":", "-").substring(0, 19);
            path = "pijota-session-" + ts + ".jsonl";
        }
        try {
            Path file = Path.of(path);
            host.session().saveMessages(file);
            // Absolute, like /export reports. A bare "pijota-session-....jsonl" does not say which directory it
            // landed in, and the answer is wherever pijota was launched -- not necessarily where the user is looking.
            host.appendPlain(List.of(host.styled("statusText",
                                            "session saved to " + file.toAbsolutePath().normalize())));
        } catch (Exception e) {
            host.appendPlain(List.of(host.styled("toolError", "save failed: " + e.getMessage())));
        }
    }

    void listSessions() {
        var sessions = host.session().listSessions();
        if (sessions.isEmpty()) {
            host.appendPlain(List.of(host.styled("statusText", "no saved sessions")));
            return;
        }
        var lines = new ArrayList<String>();
        lines.add(host.styled("toolLabel", "sessions (" + sessions.size() + ")"));
        for (var item : SessionItem.fromPaths(sessions)) {
            lines.add(host.styled("statusText", "  " + item.description()
                                            + host.styled("commandMeta", "  " + item.detailText())));
        }
        lines.add(host.styled("statusText", "/load <id|path> to resume  /delete <id> to remove"));
        host.appendPlain(lines);
    }

    void deleteSession(String spec) {
        if (spec.startsWith("/delete "))
            spec = spec.substring(8).trim();
        else if (spec.startsWith("/delete"))
            spec = spec.substring(7).trim();
        if (spec.isBlank()) {
            host.appendPlain(List.of(host.styled("statusText", "usage: /delete <id|path>")));
            return;
        }
        // Asked only once there is something to delete. The question used to come first, so a typo'd id was
        // confirmed and only then reported missing -- and "cannot delete active session" arrived the same way.
        String target = spec;
        try {
            host.session().resolveDeletable(target);
        } catch (IOException e) {
            host.appendPlain(List.of(host.styled("toolError", "delete failed: " + e.getMessage())));
            return;
        }
        host.confirm("delete session '" + target + "'? this cannot be undone").thenAccept(confirmed -> {
            if (!confirmed) {
                host.appendPlain(List.of(host.styled("statusText", "delete cancelled")));
                return;
            }
            try {
                boolean trashed = host.session().deleteSession(target);
                host.appendPlain(List.of(host.styled("statusText",
                                                (trashed ? "moved session to trash: " : "deleted session permanently: ")
                                                                                + target)));
            } catch (IOException e) {
                host.appendPlain(List.of(host.styled("toolError", "delete failed: " + e.getMessage())));
            }
        });
    }

    void startNewSession() {
        if (host.refuseWhileBusy("/new"))
            return;
        if (host.session().messages().isEmpty()) {
            createNewSession();
            return;
        }
        host.confirm("start a new session? the current conversation is cleared (it stays saved on disk)")
                                        .thenAccept(confirmed -> {
                                            if (confirmed)
                                                createNewSession();
                                            else
                                                host.appendPlain(List.of(host.styled("statusText",
                                                                                "kept current session")));
                                        });
    }

    private void createNewSession() {
        try {
            host.session().newSession();
            host.setSessionName(null);
            host.session().consumeSessionWarning().ifPresent(
                                            warning -> host.appendPlain(List.of(host.styled("toolError", warning))));
        } catch (Exception e) {
            host.appendPlain(List.of(host.styled("toolError", "session creation failed: " + e.getMessage())));
            return;
        }
        host.appendPlain(List.of(host.styled("statusText", "started new session")));
    }

    void showUsage() {
        var session = host.session();
        if (session == null)
            return;
        var totals = new Usage(session.totalInputTokens(), session.totalOutputTokens(), session.totalCacheReadTokens(),
                                        session.totalCacheWriteTokens(), session.totalReasoningTokens());
        host.appendPlain(usageReport(totals, session.totalCost(), session.costBudget(),
                                        rateLine(session.provider(), session.model(), session.hasUnpricedUsage()),
                                        host::styled));
        var breakdown = SessionUsage.breakdown(session.messages(), session.provider(), session.model());
        var summaryBreakdown = List.<SessionUsage.Breakdown>of();
        try {
            summaryBreakdown = SessionUsage.compactionBreakdown(session.branchEntries());
        } catch (IOException ignored) {}
        if (!breakdown.isEmpty() || !summaryBreakdown.isEmpty()) {
            var report = new Report(host::styled, "usage breakdown");
            for (var item : breakdown)
                report.cells(item.key(), fmtCount(item.tokens()), "tokens  " + SessionUsage.formatCost(item.cost()));
            for (var item : summaryBreakdown)
                report.cells(item.key(), fmtCount(item.tokens()), "tokens  " + SessionUsage.formatCost(item.cost()));
            host.appendPlain(report.render());
        }
    }

    /** The {@code /usage} per-model rate line: the catalog's $/1M rates for the active model, or "unknown". */
    static String rateLine(String provider, String model, boolean hasUnpriced) {
        var pricing = ModelRegistry.pricingFor(provider, model);
        if (pricing == null) {
            return "unknown for " + (model != null ? model : "current model");
        }
        // "per MTok" rather than "per 1M": the same unit the model picker prints, so the two rate readouts match.
        String line = String.format(Locale.ROOT, "$%.2f in / $%.2f out per MTok  (%s)", pricing.inputPerMTok(),
                                        pricing.outputPerMTok(), PromptPresenter.modelLabel(provider, model));
        return hasUnpriced ? line + "  · some usage on unpriced models" : line;
    }

    /** Formats the cumulative session-usage block. Pure given the totals and a styler, so it is unit-testable. */
    /**
     * The session's spend, said the way a person would. It used to read {@code ~$0.0000 est} before a single token was
     * spent: four decimals of zero, and two hedges ("~" and "est") for the same uncertainty.
     */
    static String formatSessionCost(double cost) {
        if (cost <= 0)
            return "none yet";
        if (cost < 0.01)
            return "under $0.01";
        return String.format(Locale.ROOT, "~$%.2f", cost);
    }

    /**
     * Takes the {@link Usage} record rather than its five numbers loose.
     *
     * <p>
     * They were five consecutive longs in an order of their own -- input, output, reasoning, cacheRead, cacheWrite --
     * while {@code Usage} orders the same five as input, output, cacheRead, cacheWrite, reasoning. Two orderings of one
     * set of numbers in one codebase is a transposition waiting to be written, and it compiles either way.
     */
    static List<String> usageReport(Usage usage, double cost, Double budget, String rateLine,
                                    BiFunction<String, String, String> styled) {
        long input = usage.inputTokens();
        long output = usage.outputTokens();
        long reasoning = usage.reasoningTokens();
        long cacheRead = usage.cacheReadTokens();
        long cacheWrite = usage.cacheWriteTokens();
        var report = new Report(styled, "usage").chip("this session");
        // Cells, not one string per row: input/output/total are there to be compared with each other, and a ragged
        // left edge is exactly what stops the eye doing that.
        report.cells("input", fmtCount(input), "tokens");
        report.cells("output", fmtCount(output),
                                        "tokens" + (reasoning > 0 ? "  (" + fmtCount(reasoning) + " reasoning)" : ""));
        report.cells("total", fmtCount(input + output), "tokens");
        if (cacheRead > 0 || cacheWrite > 0) {
            long promptTokens = input + cacheRead + cacheWrite; // full prompt input incl. cache
            int hitPct = promptTokens > 0 ? (int) Math.round(100.0 * cacheRead / promptTokens) : 0;
            report.cells("cache", fmtCount(cacheRead),
                                            "read  " + fmtCount(cacheWrite) + " write  (" + hitPct + "% hit)");
        }
        report.row("cost", formatSessionCost(cost), "commandMeta");
        report.row("rate", rateLine, "commandMeta");
        if (budget != null && budget > 0) {
            int pct = (int) Math.round(100.0 * cost / budget);
            report.row("budget", String.format(Locale.ROOT, "$%.2f / $%.2f (%d%%)", cost, budget, pct), "commandMeta");
        }
        var lines = report.render();
        return lines;
    }

    void branchSummary(String instructions) {
        if (host.refuseWhileBusy("/branch-summary"))
            return;
        try {
            String summary = host.session().summarizeCurrentBranch(instructions);
            host.appendPlain(List.of(host.styled("toolLabel", "branch summary"), host.styled("statusText", summary)));
        } catch (Exception e) {
            host.appendPlain(List.of(host.styled("toolError", "branch summary failed: " + e.getMessage())));
        }
    }

    void copyLastResponse() {
        String last = host.session().lastAssistantContent();
        if (last == null || last.isBlank()) {
            host.appendPlain(List.of(host.styled("statusWarn", "no assistant response to copy")));
            return;
        }
        if (Clipboard.copy(last))
            host.appendPlain(List.of(host.styled("statusText", "copied last response to clipboard")));
        else
            host.appendPlain(List.of(host.styled("toolError",
                                            "clipboard unavailable (needs pbcopy on macOS, clip on Windows, or "
                                                                            + "wl-copy/xclip/xsel on Linux)")));
    }

    void exportSession(String input) {
        if (host.session().sessionFile() == null) {
            host.appendPlain(List.of(host.styled("statusWarn", "no active session")));
            return;
        }
        String arg = input.startsWith("/export") ? input.substring("/export".length()).trim() : input.trim();
        // Optional leading format token: "/export md <path>" / "/export html <path>" (the documented [md|html] [path]
        // form). Without it, infer the format from the path's extension, else default to markdown.
        String format = null;
        String path = arg;
        var parts = arg.split("\\s+", 2);
        if (parts[0].equalsIgnoreCase("md") || parts[0].equalsIgnoreCase("html")) {
            format = parts[0].toLowerCase(Locale.ROOT);
            path = parts.length == 2 ? parts[1].trim() : "";
        }
        boolean html = "html".equals(format) || (format == null && path.toLowerCase(Locale.ROOT).endsWith(".html"));
        try {
            String base = host.session().sessionFile().getFileName().toString().replace(".jsonl", "");
            // Resolve against the session's working directory (where pijota was launched; the threaded cwd, not the
            // global user.dir property) so it's consistent with the rest of the app and isolatable in tests. resolve()
            // leaves an absolute path untouched, so an explicit /export /abs/path still lands exactly there.
            String name = path.isBlank() ? base + "-export." + (html ? "html" : "md") : path;
            Path out = Path.of(host.session().cwd()).resolve(name);
            String content = html
                                            ? SessionExporter.toHtml(host.session().sessionFile())
                                            : SessionExporter.toMarkdown(host.session().sessionFile());
            Files.writeString(out, content, StandardCharsets.UTF_8);
            host.appendPlain(List.of(host.styled("statusText", "exported to " + out)));
        } catch (Exception e) {
            host.appendPlain(List.of(host.styled("toolError", "export failed: " + e.getMessage())));
        }
    }

    void importSession(String input) {
        String arg = input.startsWith("/import") ? input.substring("/import".length()).trim() : input.trim();
        if (arg.isBlank()) {
            host.appendPlain(List.of(host.styled("statusText", "usage: /import <session.jsonl>")));
            return;
        }
        if (host.settings() == null) {
            host.appendPlain(List.of(host.styled("toolError", "import unavailable: no settings directory")));
            return;
        }
        try {
            Path source = Path.of(arg).toAbsolutePath().normalize();
            if (!Files.isRegularFile(source))
                throw new IOException("not a file: " + source);
            Path target = uniqueImportTarget(host.settings().sessionDir(), source.getFileName().toString());
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
            int count = host.session().load(target);
            host.refreshSessionName();
            host.appendPlain(List.of(host.styled("statusText", "imported " + count + " messages from " + source)));
        } catch (Exception e) {
            host.appendPlain(List.of(host.styled("toolError", "import failed: " + e.getMessage())));
        }
    }

    private static Path uniqueImportTarget(Path dir, String fileName) {
        Path target = dir.resolve(fileName);
        if (!Files.exists(target))
            return target;
        String base = fileName.endsWith(".jsonl") ? fileName.substring(0, fileName.length() - 6) : fileName;
        String ext = fileName.endsWith(".jsonl") ? ".jsonl" : "";
        for (int i = 1;; i++) {
            target = dir.resolve(base + "-import-" + i + ext);
            if (!Files.exists(target))
                return target;
        }
    }

    /**
     * Messages of one kind and what they are estimated to cost. A count and its token sum move together or the
     * breakdown lies; as ten separate locals in five implicit pairs, incrementing one and forgetting the other was a
     * single missed line away.
     */
    private record Bucket(int msgs, long tokens) {
        static final Bucket EMPTY = new Bucket(0, 0);

        Bucket plus(long more) {
            return new Bucket(msgs + 1, tokens + more);
        }
    }

    private record ContextTally(Bucket system, Bucket user, Bucket assistant, Bucket tool, Bucket summary) {
        long messageTokens() {
            return system.tokens() + user.tokens() + assistant.tokens() + tool.tokens() + summary.tokens();
        }
    }

    /** Counts and sizes the context by kind, using the same per-message estimate the status line sums. */
    private static ContextTally tally(List<Message> msgs) {
        var tally = new ContextTally(Bucket.EMPTY, Bucket.EMPTY, Bucket.EMPTY, Bucket.EMPTY, Bucket.EMPTY);
        for (var m : msgs) {
            long t = CompactionManager.estimateTokens(m);
            tally = switch (m) {
                case Message.System ignored -> new ContextTally(tally.system().plus(t), tally.user(), tally.assistant(),
                                                tally.tool(), tally.summary());
                case Message.User ignored -> new ContextTally(tally.system(), tally.user().plus(t), tally.assistant(),
                                                tally.tool(), tally.summary());
                case Message.Assistant ignored -> new ContextTally(tally.system(), tally.user(),
                                                tally.assistant().plus(t), tally.tool(), tally.summary());
                case Message.ToolResult ignored -> new ContextTally(tally.system(), tally.user(), tally.assistant(),
                                                tally.tool().plus(t), tally.summary());
                case Message.CompactionSummary ignored -> new ContextTally(tally.system(), tally.user(),
                                                tally.assistant(), tally.tool(), tally.summary().plus(t));
                default -> tally;
            };
        }
        return tally;
    }

    void showContext() {
        var msgs = host.session().messages();
        var tally = tally(msgs);
        // The status line's total is this message estimate plus the provider overhead the estimate can't see (system
        // prompt + tool schemas + tokenizer diff). Give that overhead its own row so the breakdown adds up to the one
        // reported total, instead of a second, smaller "est tokens" number that never matched it.
        long overhead = Math.max(0, host.session().estimatedContextTokens() - tally.messageTokens());

        // "estimated" is said once in the header rather than on every row: the counts are what the eye is comparing,
        // and repeating the qualifier five times pushed them out of any column they could have been compared in.
        var report = new Report(host::styled, "context").chip(plural(msgs.size(), "msg")).chip(host.tokenStatus())
                                        .chip("estimated");
        report.row("auto-compact", host.settings() == null || host.settings().compactionEnabled() ? "on" : "off");
        if (tally.system().msgs() > 0)
            row(report, "system", tally.system());
        row(report, "user", tally.user());
        row(report, "assistant", tally.assistant());
        row(report, "tool", tally.tool());
        if (tally.summary().msgs() > 0)
            row(report, "summaries", tally.summary());
        if (overhead > 0)
            report.cells("overhead", "", fmtCount((int) overhead), "tokens  system prompt + tools");
        var lines = report.render();
        if (tally.summary().msgs() > 0)
            appendLastSummary(lines, msgs);
        host.appendPlain(lines);
    }

    private static void row(Report report, String label, Bucket bucket) {
        report.cells(label, plural(bucket.msgs(), "msg"), fmtCount((int) bucket.tokens()), "tokens");
    }

    private void appendLastSummary(List<String> lines, List<Message> msgs) {
        for (var m : msgs) {
            if (m instanceof Message.CompactionSummary cs) {
                lines.add(host.styled("hr", "─".repeat(40)));
                lines.add(host.styled("statusText", "  last summary:"));
                for (String line : cs.summary().lines().toList())
                    lines.add(host.styled("statusText", "  " + line));
            }
        }
    }

}
