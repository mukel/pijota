package ai.pijota.tui;

import ai.pijota.messages.Message;
import ai.pijota.util.ErrorUtil;
import ai.pijota.util.ProcessRunner;
import ai.pijota.util.SelfRespawn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

final class TranscendenceOrchestrator {
    interface Host {
        boolean busy();

        void appendPlain(List<String> lines);

        List<String> promptEcho(String text);

        String styled(String slot, String text);

        long beginBusy(String phase);

        void endBusy(long generation);

        void redrawPrompt();

        void resetStreamState();

        void showSessionWarning();

        void suspendTui();

        void resumeTui();

        void flushTerminal();

        void submitAgent(Runnable task);

        CompletableFuture<List<Message>> promptSession(String prompt);

        Path sessionFile();

        void setStatusOverride(String status);
    }

    private final Host host;

    TranscendenceOrchestrator(Host host) {
        this.host = host;
    }

    void handle(String args) {
        boolean testMode = false;
        boolean rollback = false;
        String worktreePath = null;
        String mergeBranch = null;
        var goalParts = new ArrayList<String>();

        var tokens = args.split("\\s+");
        int i = 0;
        while (i < tokens.length) {
            if (tokens[i].isEmpty()) {
                i++;
                continue;
            }
            switch (tokens[i]) {
                case "--test" -> testMode = true;
                case "--rollback" -> rollback = true;
                case "--worktree" -> {
                    if (i + 1 < tokens.length)
                        worktreePath = tokens[++i];
                }
                case "--merge" -> {
                    if (i + 1 < tokens.length)
                        mergeBranch = tokens[++i];
                }
                default -> goalParts.add(tokens[i]);
            }
            i++;
        }

        String goal = goalParts.isEmpty() ? null : stripWrappingQuotes(String.join(" ", goalParts));

        if (host.busy()) {
            host.appendPlain(List.of(host.styled("toolError",
                                            "/transcend is unavailable while a turn is running (Esc Esc to interrupt)")));
            return;
        }

        if (goal != null) {
            submitGoal(goal, worktreePath);
            return;
        }

        if (rollback) {
            rollback();
            return;
        }

        if (mergeBranch != null && !mergeBranch.isEmpty()) {
            merge(mergeBranch);
            return;
        }

        String effectiveWorktree = worktreePath != null ? worktreePath : System.getProperty("user.dir");
        build(effectiveWorktree, testMode, true);
    }

    private void build(String projectRoot, boolean testMode, boolean showHeader) {
        Path root = Path.of(projectRoot);
        if (!Files.exists(root.resolve("pom.xml"))) {
            host.appendPlain(List.of(host.styled("toolError", "no pom.xml found at " + root)));
            return;
        }

        long gen = host.beginBusy("transcending");
        boolean keepBusy = false;
        long buildStart = System.currentTimeMillis();
        try {
            if (showHeader)
                appendHeader();
            host.redrawPrompt();

            try {
                Path currentJar = SelfRespawn.findCurrentJar();
                SelfRespawn.backupJar(currentJar);
            } catch (Exception e) {
                appendFailure(List.of(step("✕", "preserve current build")),
                                                "Cannot preserve current build: " + e.getMessage(), null);
                return;
            }

            host.appendPlain(List.of(host.styled("statusText", "  ") + stepIcon("✓")
                                            + host.styled("statusText", " preserved current build")));
            host.redrawPrompt();

            ProcessRunner.Result buildResult;
            try {
                buildResult = SelfRespawn.buildJar(root);
            } catch (Exception e) {
                appendFailure(List.of(step("✓", "preserved current build"), step("✕", "compile new build")),
                                                "Build error: " + e.getMessage(), null);
                return;
            }

            Path log = writeLog("build", buildResult.stdout());

            if (buildResult.exitCode() != 0 || buildResult.timedOut()) {
                appendFailure(List.of(step("✓", "preserved current build"), step("✕", "compile new build")),
                                                buildResult.timedOut()
                                                                                ? "Build timed out."
                                                                                : "Build exited " + buildResult.exitCode()
                                                                                                                + ".",
                                                log);
                return;
            }

            Path jar = SelfRespawn.findJarIn(root);
            if (jar == null || !Files.exists(jar)) {
                appendFailure(List.of(step("✓", "preserved current build"), step("✕", "compile new build")),
                                                "Build finished, but no jar was found in " + root.resolve("target")
                                                                                + ".",
                                                log);
                return;
            }

            double buildSec = (System.currentTimeMillis() - buildStart) / 1000.0;
            host.appendPlain(List.of(host.styled("statusText", "  ") + stepIcon("✓")
                                            + host.styled("statusText", " compiled ")
                                            + host.styled("statusText", jar.getFileName().toString())
                                            + host.styled("commandMeta", "  (" + String.format("%.1f", buildSec)
                                                                            + "s)")));
            host.redrawPrompt();

            Path sessionFile = host.sessionFile();

            if (testMode) {
                host.appendPlain(List.of(host.styled("statusText", "  … launching test instance")));
                host.redrawPrompt();

                host.suspendTui();
                try {
                    int ec = SelfRespawn.runInteractive(jar, sessionFile, true);
                    host.resumeTui();
                    host.appendPlain(List.of(host.styled("statusText", "  ") + stepIcon("✓")
                                                    + host.styled("statusText", " test instance exited  code " + ec),
                                                    "", host.styled("toolLabel", "✓ Pijota test build is ready"),
                                                    host.styled("statusText", "  rollback: /transcend --rollback")));
                } catch (Exception e) {
                    host.resumeTui();
                    appendFailure(List.of(step("✓", "preserved current build"), step("✓", "compiled new build"),
                                                    step("✕", "launch test instance")),
                                                    "Test instance error: " + e.getMessage(), log);
                }
                host.redrawPrompt();
                return;
            }

            host.appendPlain(List.of(host.styled("statusText", "  → restarting")));
            host.redrawPrompt();
            host.flushTerminal();
            SelfRespawn.respawn(jar, sessionFile, true);
            host.resumeTui();
            String err = SelfRespawn.lastRespawnError();
            if (err != null) {
                appendFailure(List.of(step("✓", "preserved current build"), step("✓", "compiled new build"),
                                                step("✕", "restart into new build")), err, log);
            } else {
                keepBusy = true;
            }
            host.redrawPrompt();
        } finally {
            if (!keepBusy) {
                host.endBusy(gen);
                host.redrawPrompt();
            }
        }
    }

    private String stepIcon(String icon) {
        String slot = icon.equals("✓") ? "diffAdd" : "toolError";
        return host.styled(slot, icon + " ");
    }

    private static String step(String icon, String label) {
        return icon + " " + label;
    }

    private void appendHeader() {
        host.appendPlain(List.of(host.styled("toolLabel", "→ Transcend")));
    }

    private void appendFailure(List<String> steps, String message, Path log) {
        var lines = new ArrayList<String>();
        for (String step : steps) {
            String slot = step.startsWith("✕") ? "toolError" : "statusText";
            lines.add(host.styled(slot, "  " + step));
        }
        lines.add("");
        lines.add(host.styled("toolError", "Still running previous build."));
        lines.add(host.styled("toolError", message));
        if (log != null)
            lines.add(host.styled("statusText", "log: " + log));
        host.appendPlain(lines);
    }

    private Path writeLog(String label, String output) {
        if (output == null || output.isBlank())
            return null;
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "pijota");
            Files.createDirectories(dir);
            Path file = Files.createTempFile(dir, "transcend-" + label + "-", ".log");
            Files.writeString(file, output, StandardCharsets.UTF_8);
            return file;
        } catch (IOException ignored) {
            return null;
        }
    }

    private void submitGoal(String goal, String worktreePath) {
        String resolvedWorktree = worktreePath;
        String branchName = null;

        if (resolvedWorktree == null) {
            branchName = generateBranch(goal);
            resolvedWorktree = "../pijota-" + branchName.replace('/', '-');
        }

        host.appendPlain(host.promptEcho("/transcend " + goal));
        long gen = host.beginBusy("transcending");
        host.redrawPrompt();

        String finalWorktree = resolvedWorktree;
        String finalBranch = branchName;
        host.submitAgent(() -> {
            boolean promptCompleted = false;
            try {
                if (finalBranch != null) {
                    try {
                        ProcessRunner.Result wtResult = ProcessRunner
                                                        .command("git", "worktree", "add", "-b", finalBranch,
                                                                                        finalWorktree)
                                                        .redirectErrorStream(true).run();
                        if (wtResult.exitCode() != 0) {
                            host.appendPlain(List.of(host.styled("toolError", "worktree creation failed:"),
                                                            host.styled("toolError", wtResult.stdout().strip())));
                            host.endBusy(gen);
                            host.redrawPrompt();
                            return;
                        }
                    } catch (IOException e) {
                        host.appendPlain(List.of(host.styled("toolError",
                                                        "worktree creation error: " + e.getMessage())));
                        host.endBusy(gen);
                        host.redrawPrompt();
                        return;
                    }
                }

                host.resetStreamState();
                var fullPrompt = "TRANSCENDENCE GOAL: " + goal + "\n\n"
                                                + "Implement this goal by modifying pijota's source code. "
                                                + "A git worktree has been prepared at " + finalWorktree
                                                + (finalBranch != null ? " on branch " + finalBranch : "")
                                                + ". Use worktree=\"" + finalWorktree
                                                + "\" for ALL transcendence actions " + "(build, test, respawn).\n\n"
                                                + "Workflow:\n" + "1. Edit source files under " + finalWorktree
                                                + "/src/main/java/ai/pijota/\n"
                                                + "2. transcendence action=\"build\" worktree=\"" + finalWorktree
                                                + "\"\n" + "3. transcendence action=\"test\" worktree=\""
                                                + finalWorktree + "\"\n"
                                                + "4. Verify only with the worktree jar, never with a bare `pijota` command. "
                                                + "Use `java -jar " + finalWorktree
                                                + "/target/<built-jar> ...` for smoke checks. "
                                                + "If the feature is TUI-only and cannot be automated, inspect the command dispatch code and continue.\n"
                                                + "5. Respawn is mandatory after build/test pass. Do not end the turn before calling: "
                                                + "transcendence action=\"respawn\" worktree=\"" + finalWorktree + "\""
                                                + (finalBranch != null ? " mergeBranch=\"" + finalBranch + "\"" : "")
                                                + "\n"
                                                + "6. Do not ask the user to merge; the replacement process will merge automatically.\n"
                                                + "7. Ignore inconclusive verification from the wrong executable; fix real failures only.\n\n"
                                                + "Work autonomously - do not ask for permission at each step.";
                host.promptSession(fullPrompt).join();
                promptCompleted = true;
                finalizeGoal(finalWorktree, finalBranch);
            } catch (CompletionException e) {
                host.setStatusOverride("Error: " + ErrorUtil.message(e.getCause()));
            } finally {
                if (!SelfRespawn.isRespawnInProgress()) {
                    if (!promptCompleted)
                        host.showSessionWarning();
                    host.endBusy(gen);
                    host.redrawPrompt();
                }
            }
        });
    }

    private void finalizeGoal(String worktree, String branch) {
        if (SelfRespawn.isPending() || SelfRespawn.isRespawnInProgress())
            return;

        Path root = Path.of(worktree);
        host.appendPlain(List.of(host.styled("toolLabel", "→ Transcend"),
                                        host.styled("statusText", "  … finalizing worktree build")));
        host.redrawPrompt();

        ProcessRunner.Result buildResult;
        try {
            buildResult = SelfRespawn.buildJar(root);
        } catch (Exception e) {
            appendFailure(List.of("✕ compile final build"), "Build error: " + e.getMessage(), null);
            return;
        }
        Path buildLog = writeLog("final-build", buildResult.stdout());
        if (buildResult.exitCode() != 0 || buildResult.timedOut()) {
            appendFailure(List.of("✕ compile final build"), buildResult.timedOut()
                                            ? "Build timed out."
                                            : "Build exited " + buildResult.exitCode() + ".", buildLog);
            return;
        }
        host.appendPlain(List.of(host.styled("statusText", "  ✓ compiled final build")));

        ProcessRunner.Result testResult;
        try {
            testResult = ProcessRunner.command("mvn", "test").workDir(root).redirectErrorStream(true).run();
        } catch (Exception e) {
            appendFailure(List.of("✓ compiled final build", "✕ run final tests"), "Test error: " + e.getMessage(),
                                            null);
            return;
        }
        Path testLog = writeLog("final-test", testResult.stdout());
        if (testResult.exitCode() != 0 || testResult.timedOut()) {
            appendFailure(List.of("✓ compiled final build", "✕ run final tests"), testResult.timedOut()
                                            ? "Tests timed out."
                                            : "Tests exited " + testResult.exitCode() + ".", testLog);
            return;
        }
        host.appendPlain(List.of(host.styled("statusText", "  ✓ final tests passed")));

        Path jar = SelfRespawn.findJarIn(root);
        if (jar == null || !Files.exists(jar)) {
            appendFailure(List.of("✓ compiled final build", "✓ final tests passed", "✕ find final jar"),
                                            "Jar not found in " + root.resolve("target") + ".", buildLog);
            return;
        }

        try {
            SelfRespawn.backupJar(SelfRespawn.findCurrentJar());
        } catch (Exception ignored) {}

        if (!SelfRespawn.requestRespawn(jar, branch, root)) {
            host.appendPlain(List.of(host.styled("statusWarn", "  … restart already pending")));
            return;
        }

        host.appendPlain(List.of(host.styled("statusText", "  → restarting")));
        host.redrawPrompt();
        host.flushTerminal();
        SelfRespawn.maybeRespawn();
    }

    private void rollback() {
        if (!SelfRespawn.canRollback()) {
            host.appendPlain(List.of(host.styled("toolError", "no backup jar - nothing to rollback to")));
            return;
        }

        Path jar = SelfRespawn.findCurrentJar();
        if (jar == null) {
            jar = SelfRespawn.findJarIn(Path.of(System.getProperty("user.dir")));
        }
        if (jar == null) {
            host.appendPlain(List.of(host.styled("toolError", "cannot determine target jar for rollback")));
            return;
        }

        try {
            SelfRespawn.rollback(jar);
        } catch (Exception e) {
            host.appendPlain(List.of(host.styled("toolError", "rollback failed: " + e.getMessage())));
            return;
        }

        host.appendPlain(List.of(host.styled("toolLabel", "→ Transcend"),
                                        host.styled("statusText", "  ✓ restored previous build"),
                                        host.styled("statusText", "  → restarting")));
        host.redrawPrompt();
        host.flushTerminal();
        SelfRespawn.respawn(jar, host.sessionFile(), true);
        host.resumeTui();
        String err = SelfRespawn.lastRespawnError();
        if (err != null)
            appendFailure(List.of("✓ restored previous build", "✕ restart"), err, null);
        host.redrawPrompt();
    }

    private void merge(String branch) {
        host.appendPlain(List.of(host.styled("toolLabel", "→ Transcend"),
                                        host.styled("statusText", "  … merging " + branch)));
        host.redrawPrompt();

        ProcessRunner.Result mergeResult;
        try {
            mergeResult = SelfRespawn.mergeBranch(branch);
        } catch (Exception e) {
            host.appendPlain(List.of(host.styled("toolError", "merge error: " + e.getMessage())));
            return;
        }

        if (mergeResult.exitCode() != 0) {
            String output = mergeResult.stdout().isBlank() ? "[no output]" : mergeResult.stdout().strip();
            host.appendPlain(List.of(host.styled("toolError", "  ✕ merge failed  exit " + mergeResult.exitCode()),
                                            host.styled("toolError", output)));
            return;
        }

        host.appendPlain(List.of(host.styled("statusText", "  ✓ merge complete")));
        host.redrawPrompt();
        build(System.getProperty("user.dir"), false, false);
    }

    private static String generateBranch(String goal) {
        String slug = goal.toLowerCase().replaceAll("[^a-z0-9\\s-]", "").replaceAll("\\s+", "-")
                                        .replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        if (slug.length() > 30)
            slug = slug.substring(0, 30).replaceAll("-$", "");
        if (slug.isBlank())
            slug = "update";
        String ts = String.format("%tH%<tM%<tS", System.currentTimeMillis());
        return "feat/" + slug + "-" + ts;
    }

    private static String stripWrappingQuotes(String value) {
        if (value == null || value.length() < 2)
            return value;
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
