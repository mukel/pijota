package ai.pijota.tui;

import ai.pijota.agent.PijotaSession;
import ai.pijota.agent.SessionExporter;
import ai.pijota.config.SettingsManager;
import ai.pijota.llm.ThinkingLevel;
import ai.pijota.messages.Message;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.List;

import static ai.pijota.util.Util.fmtCount;

final class SessionCommands {
    interface Host {
        PijotaSession session();

        String styled(String slot, String text);

        void appendPlain(List<String> lines);

        boolean busy();

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
            String ts = java.time.Instant.now().toString().replace(":", "-").substring(0, 19);
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
                host.session().deleteSession(target);
                host.appendPlain(List.of(host.styled("statusText", "deleted session: " + target)));
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
        host.appendPlain(usageReport(session.totalInputTokens(), session.totalOutputTokens(), session.totalCost(),
                                        session.costBudget(), host::styled));
    }

    /** Formats the cumulative session-usage block. Pure given the totals and a styler, so it is unit-testable. */
    static List<String> usageReport(long input, long output, double cost, Double budget,
                                    java.util.function.BiFunction<String, String, String> styled) {
        var lines = new ArrayList<String>();
        lines.add(styled.apply("toolLabel", "usage  (this session)"));
        lines.add(styled.apply("statusText", "  input   " + fmtCount(input) + " tokens"));
        lines.add(styled.apply("statusText", "  output  " + fmtCount(output) + " tokens"));
        lines.add(styled.apply("statusText", "  total   " + fmtCount(input + output) + " tokens"));
        lines.add(styled.apply("commandMeta", String.format(java.util.Locale.ROOT, "  cost    ~$%.4f est", cost)));
        if (budget != null && budget > 0) {
            int pct = (int) Math.round(100.0 * cost / budget);
            lines.add(styled.apply("commandMeta", String.format(java.util.Locale.ROOT, "  budget  $%.2f / $%.2f (%d%%)",
                                            cost, budget, pct)));
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

    void copyLastResponse() {
        String last = host.session().lastAssistantContent();
        if (last == null || last.isBlank()) {
            host.appendPlain(List.of(host.styled("toolError", "no assistant response to copy")));
            return;
        }
        try {
            var clipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new java.awt.datatransfer.StringSelection(last), null);
            host.appendPlain(List.of(host.styled("statusText", "copied last response to clipboard")));
        } catch (Exception e) {
            host.appendPlain(List.of(host.styled("toolError", "clipboard unavailable: " + e.getMessage())));
        }
    }

    void exportSession() {
        if (host.session().sessionFile() == null) {
            host.appendPlain(List.of(host.styled("toolError", "no active session")));
            return;
        }
        try {
            String md = SessionExporter.toMarkdown(host.session().sessionFile());
            String base = host.session().sessionFile().getFileName().toString().replace(".jsonl", "");
            Path out = Path.of(System.getProperty("user.dir")).resolve(base + "-export.md");
            Files.writeString(out, md, StandardCharsets.UTF_8);
            host.appendPlain(List.of(host.styled("statusText", "exported to " + out)));
        } catch (Exception e) {
            host.appendPlain(List.of(host.styled("toolError", "export failed: " + e.getMessage())));
        }
    }

    void showContext() {
        var msgs = host.session().messages();
        int systems = 0, users = 0, assistants = 0, tools = 0, compactions = 0;
        long sysChars = 0, userChars = 0, assistantChars = 0, toolChars = 0;
        for (var m : msgs) {
            switch (m) {
                case Message.System s -> {
                    systems++;
                    sysChars += s.content().length();
                }
                case Message.User u -> {
                    users++;
                    userChars += u.content().length();
                }
                case Message.Assistant a -> {
                    assistants++;
                    assistantChars += a.content() != null ? a.content().length() : 0;
                    if (a.thinking() != null)
                        assistantChars += a.thinking().length();
                }
                case Message.ToolResult tr -> {
                    tools++;
                    toolChars += tr.content().length();
                }
                case Message.CompactionSummary _ -> compactions++;
                default -> {}
            }
        }
        long totalChars = sysChars + userChars + assistantChars + toolChars;
        int estTokens = (int) Math.ceil(totalChars / 4.0);
        var lines = new ArrayList<String>();
        lines.add(host.styled("toolLabel", "context  " + msgs.size() + " msgs  " + fmtCount(estTokens) + " est tokens  "
                                        + host.tokenStatus()));
        lines.add(host.styled("statusText", "  auto-compact  "
                                        + (host.settings() == null || host.settings().compactionEnabled()
                                                                        ? "on"
                                                                        : "off")));
        if (systems > 0)
            lines.add(host.styled("statusText", "  system     " + systems + " msg  "
                                            + fmtCount((int) Math.ceil(sysChars / 4.0)) + " est tokens"));
        lines.add(host.styled("statusText", "  user       " + users + " msgs  "
                                        + fmtCount((int) Math.ceil(userChars / 4.0)) + " est tokens"));
        lines.add(host.styled("statusText", "  assistant  " + assistants + " msgs  "
                                        + fmtCount((int) Math.ceil(assistantChars / 4.0)) + " est tokens"));
        lines.add(host.styled("statusText", "  tool       " + tools + " msgs  "
                                        + fmtCount((int) Math.ceil(toolChars / 4.0)) + " est tokens"));
        if (compactions > 0) {
            lines.add(host.styled("statusText", "  compactions  " + compactions));
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
