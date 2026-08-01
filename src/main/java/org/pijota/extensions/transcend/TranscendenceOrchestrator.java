package org.pijota.extensions.transcend;

import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.ExtensionRuntime;
import org.pijota.util.ErrorUtil;
import org.pijota.util.ProcessRunner;
import org.pijota.util.SelfRespawn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;

final class TranscendenceOrchestrator {
    private final ExtensionAPI api;
    private final ExtensionRuntime runtime;

    public TranscendenceOrchestrator(ExtensionAPI api) {
        this.api = api;
        this.runtime = api.runtime();
    }

    // Transcend progress is operation output (build/test/respawn status), not model output, so it goes to the terminal
    // scrollback via the TUI's cursor-bookkept append, never added to the conversation/session message log.
    private String styled(String slot, String text) {
        return runtime.ui().theme().styled(slot, text);
    }

    private void appendPlain(List<String> lines) {
        runtime.ui().appendOutput(lines);
    }

    private boolean busy() {
        return !runtime.isIdle();
    }

    private void beginBusy(String phase) {
        runtime.setStatus("transcend", phase + "\u2026");
    }

    private void endBusy() {
        runtime.clearStatus("transcend");
    }

    private void submitAgent(Runnable task) {
        Thread t = new Thread(task, "transcend");
        t.setDaemon(true);
        t.start();
    }

    // Drive the agent on a prompt and block until the turn completes. The extension surface has no future-returning
    // send (only sendUserMessage + isIdle), so this polls; replacing it with an await-turn primitive on the runtime is
    // tracked separately.
    private void promptSession(String prompt) {
        api.sendUserMessage(prompt);
        try {
            for (int i = 0; i < 200 && runtime.isIdle(); i++)
                Thread.sleep(25); // wait for the turn to start
            while (!runtime.isIdle())
                Thread.sleep(100); // wait for it to finish
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Path sessionFile() {
        return runtime.sessionFile();
    }

    private void setStatusOverride(String status) {
        runtime.setStatus("transcend", status);
    }

    private String projectDir() {
        return runtime.projectDir().toString();
    }

    private boolean isGitRepo() {
        return runtime.isGitRepo();
    }

    void handle(String args) {
        if (!isGitRepo()) {
            appendPlain(List.of(styled("toolError", "not a git repository at " + projectDir()), styled("statusText",
                                            "recovery: run from a git repo root, or set project_dir in settings.json"),
                                            styled("statusText", "  e.g. settings include {\"project_dir\": \"/path/to/pijota\"}")));
            return;
        }
        var parsed = parseTranscendArgs(args);
        if (busy()) {
            appendPlain(List.of(styled("toolError",
                                            "/transcend is unavailable while a turn is running (Esc Esc to interrupt)")));
            return;
        }

        if (parsed.goal() != null) {
            submitGoal(parsed.goal(), parsed.worktreePath());
            return;
        }

        if (parsed.rollback()) {
            rollback();
            return;
        }

        if (parsed.mergeBranch() != null && !parsed.mergeBranch().isEmpty()) {
            merge(parsed.mergeBranch());
            return;
        }

        String effectiveWorktree = parsed.worktreePath() != null ? parsed.worktreePath() : projectDir();
        build(effectiveWorktree, parsed.testMode(), true);
    }

    record TranscendArgs(boolean testMode, boolean rollback, String worktreePath, String mergeBranch, String goal) {
    }

    static TranscendArgs parseTranscendArgs(String args) {
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
        return new TranscendArgs(testMode, rollback, worktreePath, mergeBranch, goal);
    }

    private void build(String projectRoot, boolean testMode, boolean showHeader) {
        Path root = Path.of(projectRoot);
        if (!Files.exists(root.resolve("pom.xml"))) {
            appendPlain(List.of(styled("toolError", "no pom.xml found at " + root),
                                            styled("statusText", "recovery: cd " + root + " && mvn package -Pfat -q")));
            return;
        }

        beginBusy("transcending");
        boolean keepBusy = false;
        long buildStart = System.currentTimeMillis();
        try {
            if (showHeader)
                appendHeader();

            try {
                Path currentJar = SelfRespawn.findCurrentJar();
                SelfRespawn.backupJar(currentJar);
            } catch (Exception e) {
                appendFailure(List.of(step("✕", "preserve current build")),
                                                "Cannot preserve current build: " + e.getMessage(), null,
                                                "mvn package -Pfat -q");
                return;
            }

            appendPlain(List.of(styled("statusText", "  ") + stepIcon("✓")
                                            + styled("statusText", " preserved current build")));

            appendPlain(List.of(styled("statusText", "  … compiling new build (mvn package, this can take a minute)")));
            ProcessRunner.Result buildResult;
            try {
                buildResult = SelfRespawn.buildJar(root);
            } catch (Exception e) {
                appendFailure(List.of(step("✓", "preserved current build"), step("✕", "compile new build")),
                                                "Build error: " + e.getMessage(), null, "mvn package -Pfat -q");
                return;
            }

            Path log = writeLog("build", buildResult.stdout());

            if (buildResult.exitCode() != 0 || buildResult.timedOut()) {
                appendFailure(List.of(step("✓", "preserved current build"), step("✕", "compile new build")),
                                                buildResult.timedOut()
                                                                                ? "Build timed out."
                                                                                : "Build exited " + buildResult.exitCode()
                                                                                                                + ".",
                                                log, "mvn package -Pfat -q");
                return;
            }

            Path jar = SelfRespawn.findJarIn(root);
            if (jar == null || !Files.exists(jar)) {
                appendFailure(List.of(step("✓", "preserved current build"), step("✕", "compile new build")),
                                                "Build finished, but no jar was found in " + root.resolve("target")
                                                                                + ".",
                                                log, "mvn package -Pfat -q");
                return;
            }

            double buildSec = (System.currentTimeMillis() - buildStart) / 1000.0;
            appendPlain(List.of(styled("statusText", "  ") + stepIcon("✓") + styled("statusText", " compiled ")
                                            + styled("statusText", jar.getFileName().toString())
                                            + styled("commandMeta", "  (" + String.format(Locale.ROOT, "%.1f", buildSec)
                                                                            + "s)")));

            Path sessionFile = sessionFile();

            if (testMode) {
                appendPlain(List.of(styled("statusText", "  … launching test instance")));

                // The test instance is a full interactive child that takes the terminal. Hand it over (resume()
                // restores raw mode and repaints the prompt afterwards).
                runtime.ui().suspend();
                try {
                    int ec = SelfRespawn.runInteractive(jar, sessionFile, true);
                    runtime.ui().resume();
                    appendPlain(List.of(styled("statusText", "  ") + stepIcon("✓")
                                                    + styled("statusText", " test instance exited  code " + ec), "",
                                                    styled("toolLabel", "✓ Pijota test build is ready"),
                                                    styled("statusText", "  rollback: /transcend --rollback")));
                } catch (Exception e) {
                    runtime.ui().resume();
                    appendFailure(List.of(step("✓", "preserved current build"), step("✓", "compiled new build"),
                                                    step("✕", "launch test instance")),
                                                    "Test instance error: " + e.getMessage(), log);
                }
                return;
            }

            appendPlain(List.of(styled("statusText", "  → restarting")));
            SelfRespawn.respawn(jar, sessionFile, true);
            String err = SelfRespawn.lastRespawnError();
            if (err != null) {
                appendFailure(List.of(step("✓", "preserved current build"), step("✓", "compiled new build"),
                                                step("✕", "restart into new build")), err, log,
                                                "java -jar " + jar.getFileName());
            } else {
                keepBusy = true;
            }
        } finally {
            if (!keepBusy) {
                endBusy();
            }
        }
    }

    private String stepIcon(String icon) {
        String slot = icon.equals("✓") ? "diffAdd" : "toolError";
        return styled(slot, icon + " ");
    }

    static String step(String icon, String label) {
        return icon + " " + label;
    }

    private void appendHeader() {
        appendPlain(List.of(styled("toolLabel", "→ Transcend")));
    }

    private void appendFailure(List<String> steps, String message, Path log, String recovery) {
        var lines = new ArrayList<String>();
        for (String step : steps) {
            String slot = step.startsWith("✕") ? "toolError" : "statusText";
            lines.add(styled(slot, "  " + step));
        }
        lines.add("");
        lines.add(styled("toolError", "Still running previous build."));
        lines.add(styled("toolError", message));
        if (log != null)
            lines.add(styled("statusText", "log: " + log));
        if (recovery != null && !recovery.isBlank())
            lines.add(styled("statusText", "recovery: " + recovery));
        appendPlain(lines);
    }

    private void appendFailure(List<String> steps, String message, Path log) {
        appendFailure(steps, message, log, null);
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

        appendPlain(List.of("/transcend " + goal));
        beginBusy("transcending");

        String finalWorktree = resolvedWorktree;
        String finalBranch = branchName;
        submitAgent(() -> {
            try {
                if (finalBranch != null) {
                    try {
                        ProcessRunner.Result wtResult = ProcessRunner
                                                        .command("git", "worktree", "add", "-b", finalBranch,
                                                                                        finalWorktree)
                                                        .redirectErrorStream(true).run();
                        if (wtResult.exitCode() != 0) {
                            appendPlain(List.of(styled("toolError", "worktree creation failed:"),
                                                            styled("toolError", wtResult.stdout().strip()),
                                                            styled("statusText", "recovery: rm -rf " + finalWorktree
                                                                                            + " && /transcend "
                                                                                            + goal)));
                            endBusy();
                            return;
                        }
                    } catch (IOException e) {
                        appendPlain(List.of(styled("toolError", "worktree creation error: " + e.getMessage()),
                                                        styled("statusText", "recovery: rm -rf " + finalWorktree
                                                                                        + " && /transcend " + goal)));
                        endBusy();
                        return;
                    }
                }

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
                promptSession(fullPrompt);
                finalizeGoal(finalWorktree, finalBranch);
            } catch (CompletionException e) {
                setStatusOverride("Error: " + ErrorUtil.message(e.getCause()));
            } finally {
                if (!SelfRespawn.isRespawnInProgress()) {
                    endBusy();
                }
            }
        });
    }

    private void finalizeGoal(String worktree, String branch) {
        if (SelfRespawn.isPending() || SelfRespawn.isRespawnInProgress())
            return;

        Path root = Path.of(worktree);
        appendPlain(List.of(styled("toolLabel", "→ Transcend"), styled("statusText", "  … finalizing worktree build")));

        ProcessRunner.Result buildResult;
        try {
            buildResult = SelfRespawn.buildJar(root);
        } catch (Exception e) {
            appendFailure(List.of("✕ compile final build"), "Build error: " + e.getMessage(), null,
                                            "mvn package -Pfat -q");
            return;
        }
        Path buildLog = writeLog("final-build", buildResult.stdout());
        if (buildResult.exitCode() != 0 || buildResult.timedOut()) {
            appendFailure(List.of("✕ compile final build"),
                                            buildResult.timedOut()
                                                                            ? "Build timed out."
                                                                            : "Build exited " + buildResult.exitCode()
                                                                                                            + ".",
                                            buildLog, "mvn package -Pfat -q");
            return;
        }
        appendPlain(List.of(styled("statusText", "  ✓ compiled final build")));

        appendPlain(List.of(styled("statusText", "  … running tests (mvn test)")));
        ProcessRunner.Result testResult;
        try {
            testResult = ProcessRunner.command("mvn", "test").workDir(root).redirectErrorStream(true).run();
        } catch (Exception e) {
            appendFailure(List.of("✓ compiled final build", "✕ run final tests"), "Test error: " + e.getMessage(), null,
                                            "mvn test");
            return;
        }
        Path testLog = writeLog("final-test", testResult.stdout());
        if (testResult.exitCode() != 0 || testResult.timedOut()) {
            appendFailure(List.of("✓ compiled final build", "✕ run final tests"), testResult.timedOut()
                                            ? "Tests timed out."
                                            : "Tests exited " + testResult.exitCode() + ".", testLog, "mvn test");
            return;
        }
        appendPlain(List.of(styled("statusText", "  ✓ final tests passed")));

        Path jar = SelfRespawn.findJarIn(root);
        if (jar == null || !Files.exists(jar)) {
            appendFailure(List.of("✓ compiled final build", "✓ final tests passed", "✕ find final jar"),
                                            "Jar not found in " + root.resolve("target") + ".", buildLog,
                                            "mvn package -Pfat -q");
            return;
        }

        try {
            SelfRespawn.backupJar(SelfRespawn.findCurrentJar());
        } catch (Exception ignored) {}

        if (!SelfRespawn.requestRespawn(jar, branch, root)) {
            appendPlain(List.of(styled("statusWarn", "  … restart already pending")));
            return;
        }

        appendPlain(List.of(styled("statusText", "  → restarting")));
        SelfRespawn.maybeRespawn();
    }

    private void rollback() {
        if (!SelfRespawn.canRollback()) {
            appendPlain(List.of(styled("toolError", "no backup jar - nothing to rollback to"),
                                            styled("statusText", "recovery: mvn package -Pfat -q && /transcend")));
            return;
        }

        Path jar = SelfRespawn.findCurrentJar();
        if (jar == null) {
            jar = SelfRespawn.findJarIn(Path.of(projectDir()));
        }
        if (jar == null) {
            appendPlain(List.of(styled("toolError", "cannot determine target jar for rollback"), styled("statusText",
                                            "recovery: cp target/pijota-backup.jar target/pijota-$(date +%s).jar")));
            return;
        }

        try {
            SelfRespawn.rollback(jar);
        } catch (Exception e) {
            appendPlain(List.of(styled("toolError", "rollback failed: " + e.getMessage()),
                                            styled("statusText", "recovery: cp target/pijota-backup.jar " + jar)));
            return;
        }

        appendPlain(List.of(styled("toolLabel", "→ Transcend"), styled("statusText", "  ✓ restored previous build"),
                                        styled("statusText", "  → restarting")));
        SelfRespawn.respawn(jar, sessionFile(), true);
        String err = SelfRespawn.lastRespawnError();
        if (err != null)
            appendFailure(List.of("✓ restored previous build", "✕ restart"), err, null, "mvn package -Pfat -q");
    }

    private void merge(String branch) {
        appendPlain(List.of(styled("toolLabel", "→ Transcend"), styled("statusText", "  … merging " + branch)));

        ProcessRunner.Result mergeResult;
        try {
            mergeResult = SelfRespawn.mergeBranch(branch);
        } catch (Exception e) {
            appendPlain(List.of(styled("toolError", "merge error: " + e.getMessage()), styled("statusText",
                                            "recovery: git merge --abort && /transcend --merge " + branch)));
            return;
        }

        if (mergeResult.exitCode() != 0) {
            String output = mergeResult.stdout().isBlank() ? "[no output]" : mergeResult.stdout().strip();
            appendPlain(List.of(styled("toolError", "  ✕ merge failed  exit " + mergeResult.exitCode()),
                                            styled("toolError", output)));
            return;
        }

        appendPlain(List.of(styled("statusText", "  ✓ merge complete")));
        build(projectDir(), false, false);
    }

    static String generateBranch(String goal) {
        String slug = goal.toLowerCase().replaceAll("[^a-z0-9\\s-]", "").replaceAll("\\s+", "-")
                                        .replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        if (slug.length() > 30)
            slug = slug.substring(0, 30).replaceAll("-$", "");
        if (slug.isBlank())
            slug = "update";
        String ts = String.format(Locale.ROOT, "%tH%<tM%<tS", System.currentTimeMillis());
        return "feat/" + slug + "-" + ts;
    }

    static String stripWrappingQuotes(String value) {
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
