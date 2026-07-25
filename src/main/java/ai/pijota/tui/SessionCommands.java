package ai.pijota.tui;

import ai.pijota.agent.CompactionManager;
import ai.pijota.agent.PijotaSession;
import ai.pijota.agent.SessionExporter;
import ai.pijota.agent.SessionUsage;
import ai.pijota.config.SettingsManager;
import ai.pijota.llm.ModelRegistry;
import ai.pijota.llm.ThinkingLevel;
import ai.pijota.messages.Message;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;

import static ai.pijota.util.Util.fmtCount;

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
            host.session().saveMessages(Path.of(path));
            host.appendPlain(List.of(host.styled("statusText", "session saved to " + path)));
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
            host.appendPlain(List.of(host.styled("toolError", "usage: /delete <id|path>")));
            return;
        }
        String target = spec;
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
        if (host.busy()) {
            host.appendPlain(List.of(host.styled("toolError", "/new is unavailable while a turn is running")));
            return;
        }
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
        host.appendPlain(usageReport(session.totalInputTokens(), session.totalOutputTokens(),
                                        session.totalReasoningTokens(), session.totalCacheReadTokens(),
                                        session.totalCacheWriteTokens(), session.totalCost(), session.costBudget(),
                                        rateLine(session.provider(), session.model(), session.hasUnpricedUsage()),
                                        host::styled));
        var breakdown = SessionUsage.breakdown(session.messages(), session.provider(), session.model());
        var summaryBreakdown = List.<SessionUsage.Breakdown>of();
        try {
            summaryBreakdown = SessionUsage.compactionBreakdown(session.branchEntries());
        } catch (IOException ignored) {}
        if (!breakdown.isEmpty() || !summaryBreakdown.isEmpty()) {
            var lines = new ArrayList<String>();
            lines.add(host.styled("toolLabel", "usage breakdown"));
            for (var item : breakdown) {
                lines.add(host.styled("statusText", "  " + item.key() + "  " + fmtCount(item.tokens()) + " tokens  "
                                                + SessionUsage.formatCost(item.cost())));
            }
            for (var item : summaryBreakdown) {
                lines.add(host.styled("statusText", "  " + item.key() + "  " + fmtCount(item.tokens()) + " tokens  "
                                                + SessionUsage.formatCost(item.cost())));
            }
            host.appendPlain(lines);
        }
    }

    /** The {@code /usage} per-model rate line: the catalog's $/1M rates for the active model, or "unknown". */
    static String rateLine(String provider, String model, boolean hasUnpriced) {
        var pricing = ModelRegistry.pricingFor(provider, model);
        if (pricing == null) {
            return "  rate    unknown for " + (model != null ? model : "current model");
        }
        String line = String.format(Locale.ROOT, "  rate    $%.2f in / $%.2f out per 1M  (%s)", pricing.inputPerMTok(),
                                        pricing.outputPerMTok(), model);
        return hasUnpriced ? line + " — some usage on unpriced models" : line;
    }

    /** Formats the cumulative session-usage block. Pure given the totals and a styler, so it is unit-testable. */
    static List<String> usageReport(long input, long output, long reasoning, long cacheRead, long cacheWrite,
                                    double cost, Double budget, String rateLine,
                                    BiFunction<String, String, String> styled) {
        var lines = new ArrayList<String>();
        lines.add(styled.apply("toolLabel", "usage  (this session)"));
        lines.add(styled.apply("statusText", "  input   " + fmtCount(input) + " tokens"));
        lines.add(styled.apply("statusText", "  output  " + fmtCount(output) + " tokens"
                                        + (reasoning > 0 ? "  (" + fmtCount(reasoning) + " reasoning)" : "")));
        lines.add(styled.apply("statusText", "  total   " + fmtCount(input + output) + " tokens"));
        if (cacheRead > 0 || cacheWrite > 0) {
            long promptTokens = input + cacheRead + cacheWrite; // full prompt input incl. cache
            int hitPct = promptTokens > 0 ? (int) Math.round(100.0 * cacheRead / promptTokens) : 0;
            lines.add(styled.apply("statusText", "  cache   " + fmtCount(cacheRead) + " read  " + fmtCount(cacheWrite)
                                            + " write  (" + hitPct + "% hit)"));
        }
        lines.add(styled.apply("commandMeta", String.format(Locale.ROOT, "  cost    ~$%.4f est", cost)));
        lines.add(styled.apply("commandMeta", rateLine));
        if (budget != null && budget > 0) {
            int pct = (int) Math.round(100.0 * cost / budget);
            lines.add(styled.apply("commandMeta", String.format(Locale.ROOT, "  budget  $%.2f / $%.2f (%d%%)", cost,
                                            budget, pct)));
        }
        return lines;
    }

    void cloneSession() {
        if (host.busy()) {
            host.appendPlain(List.of(host.styled("toolError", "/clone is unavailable while a turn is running")));
            return;
        }
        if (host.session().sessionFile() == null) {
            host.appendPlain(List.of(host.styled("toolError", "no active session")));
            return;
        }
        try {
            Path newFile = host.session().cloneCurrentBranch();
            host.appendPlain(List.of(host.styled("statusText", "cloned session to " + newFile.getFileName())));
        } catch (Exception e) {
            host.appendPlain(List.of(host.styled("toolError", "clone failed: " + e.getMessage())));
        }
    }

    void branchSummary(String instructions) {
        if (host.busy()) {
            host.appendPlain(List.of(host.styled("toolError", "/branch-summary is unavailable while a turn is running")));
            return;
        }
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
            host.appendPlain(List.of(host.styled("toolError", "no assistant response to copy")));
            return;
        }
        try {
            var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new StringSelection(last), null);
            host.appendPlain(List.of(host.styled("statusText", "copied last response to clipboard")));
        } catch (Exception e) {
            host.appendPlain(List.of(host.styled("toolError", "clipboard unavailable: " + e.getMessage())));
        }
    }

    void exportSession(String input) {
        if (host.session().sessionFile() == null) {
            host.appendPlain(List.of(host.styled("toolError", "no active session")));
            return;
        }
        String arg = input.startsWith("/export") ? input.substring("/export".length()).trim() : input.trim();
        boolean html = "html".equalsIgnoreCase(arg) || arg.toLowerCase(Locale.ROOT).endsWith(".html");
        try {
            String base = host.session().sessionFile().getFileName().toString().replace(".jsonl", "");
            // Write into the session's working directory (where pijota was launched) — the threaded cwd, not the global
            // user.dir property, so it's consistent with the rest of the app and isolatable in tests.
            Path out = arg.isBlank() || "html".equalsIgnoreCase(arg)
                                            ? Path.of(host.session().cwd()).resolve(base + "-export."
                                                                            + (html ? "html" : "md"))
                                            : Path.of(arg);
            String content = html ? SessionExporter.toHtml(host.session().sessionFile())
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
            host.appendPlain(List.of(host.styled("toolError", "usage: /import <session.jsonl>")));
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

    void showContext() {
        var msgs = host.session().messages();
        int systems = 0, users = 0, assistants = 0, tools = 0, compactions = 0;
        long systemTok = 0, userTok = 0, assistantTok = 0, toolTok = 0, summaryTok = 0;
        for (var m : msgs) {
            long t = CompactionManager.estimateTokens(m); // the same per-message estimate the status line sums
            switch (m) {
                case Message.System s -> {
                    systems++;
                    systemTok += t;
                }
                case Message.User u -> {
                    users++;
                    userTok += t;
                }
                case Message.Assistant a -> {
                    assistants++;
                    assistantTok += t;
                }
                case Message.ToolResult tr -> {
                    tools++;
                    toolTok += t;
                }
                case Message.CompactionSummary cs -> {
                    compactions++;
                    summaryTok += t;
                }
                default -> {}
            }
        }
        // The status line's total is this message estimate plus the provider overhead the estimate can't see (system
        // prompt + tool schemas + tokenizer diff). Give that overhead its own row so the breakdown adds up to the one
        // reported total, instead of the old second, smaller "est tokens" number that never matched it.
        long messageTok = systemTok + userTok + assistantTok + toolTok + summaryTok;
        long overhead = Math.max(0, host.session().estimatedContextTokens() - messageTok);

        var lines = new ArrayList<String>();
        lines.add(host.styled("toolLabel", "context  " + msgs.size() + " msgs  " + host.tokenStatus()));
        lines.add(host.styled("statusText", "  auto-compact  "
                                        + (host.settings() == null || host.settings().compactionEnabled()
                                                                        ? "on"
                                                                        : "off")));
        if (overhead > 0)
            lines.add(host.styled("statusText", "  overhead   system prompt + tools  " + fmtCount((int) overhead)
                                            + " est tokens"));
        if (systems > 0)
            lines.add(host.styled("statusText", "  system     " + systems + " msg  " + fmtCount((int) systemTok)
                                            + " est tokens"));
        lines.add(host.styled("statusText",
                                        "  user       " + users + " msgs  " + fmtCount((int) userTok) + " est tokens"));
        lines.add(host.styled("statusText", "  assistant  " + assistants + " msgs  " + fmtCount((int) assistantTok)
                                        + " est tokens"));
        lines.add(host.styled("statusText",
                                        "  tool       " + tools + " msgs  " + fmtCount((int) toolTok) + " est tokens"));
        if (compactions > 0) {
            lines.add(host.styled("statusText", "  summaries  " + compactions + " msg  " + fmtCount((int) summaryTok)
                                            + " est tokens"));
            for (var m : msgs) {
                if (m instanceof Message.CompactionSummary cs) {
                    lines.add(host.styled("hr", "─".repeat(40)));
                    lines.add(host.styled("statusText", "  last summary:"));
                    for (String line : cs.summary().lines().toList()) {
                        lines.add(host.styled("statusText", "  " + line));
                    }
                }
            }
        }
        host.appendPlain(lines);
    }

    void showSessionInfo() {
        host.refreshSessionName();
        host.appendPlain(List.of(host.styled("toolLabel", "session"),
                                        host.styled("statusText", "provider  " + host.provider()),
                                        host.styled("statusText", "model    " + host.model()),
                                        host.styled("statusText", "cwd      " + host.cwd()),
                                        host.styled("statusText", "tokens   " + host.tokenStatus()),
                                        host.styled("statusText", "thinking " + host.thinkingLevel().label()),
                                        host.styled("statusText", "compact  " + (host.settings() == null
                                                                        || host.settings().compactionEnabled()
                                                                                                        ? "auto"
                                                                                                        : "off"))));
        if (host.sessionName() != null)
            host.appendPlain(List.of(host.styled("statusText", "name     " + host.sessionName())));
    }
}
