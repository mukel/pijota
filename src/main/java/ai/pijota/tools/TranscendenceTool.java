package ai.pijota.tools;

import ai.pijota.llm.ToolSchema;
import ai.pijota.util.ProcessRunner;
import ai.pijota.util.SelfRespawn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class TranscendenceTool implements Tool {

    private static final String MVN_NOT_FOUND = "✕ Transcend failed\n\nMaven (mvn) not found on PATH — "
                                    + "install Maven (https://maven.apache.org) or add it to PATH.";

    @Override
    public String name() {
        return "transcendence";
    }

    @Override
    public String description() {
        return "Self-modification tool. Edit pijota's own source code, then use this tool to build, test, or respawn. "
                                        + "Actions: 'build' (mvn package), 'test' (mvn test), "
                                        + "'respawn' (build + restart with new jar), 'rollback' (restore backup jar + restart). "
                                        + "Use 'worktree' param to operate on a git worktree path. "
                                        + "Always build and test before respawning. After build/test pass, respawn is mandatory; do not stop "
                                        + "because a manual verification command was inconclusive or used the wrong executable. If respawn fails, use rollback to revert.";
    }

    @Override
    public Map<String, Object> schema() {
        return ToolSchema.object().str("action", "Action: 'build', 'test', 'respawn', or 'rollback'", true).str(
                                        "worktree",
                                        "Optional path to git worktree. If set, operates on that worktree instead of current directory.")
                                        .str("mergeBranch", "Optional branch to merge into the original checkout after successful respawn. Use with action='respawn'.")
                                        .build();
    }

    @Override
    public ExecutionMode executionMode() {
        return ExecutionMode.SEQUENTIAL;
    }

    @Override
    public CompletableFuture<ToolResult> execute(String toolCallId, Args args, String sessionCwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String action = args.getString("action");
                if (action == null || action.isBlank())
                    return ToolResult.error("Missing required field: action");

                String worktree = args.getString("worktree");
                String mergeBranch = args.getString("mergeBranch");
                Path projectRoot = resolveProjectRoot(worktree, sessionCwd);

                return switch (action.strip().toLowerCase(Locale.ROOT)) {
                    case "build" -> doBuild(projectRoot);
                    case "test" -> doTest(projectRoot);
                    case "respawn" -> doRespawn(projectRoot, mergeBranch);
                    case "rollback" -> doRollback(projectRoot);
                    default ->
                        ToolResult.error("Unknown action: " + action + ". Valid: build, test, respawn, rollback");
                };
            } catch (Exception e) {
                return ToolResult.error("✕ Transcend failed\n\n" + e.getMessage());
            }
        });
    }

    static Path resolveProjectRoot(String worktree, String sessionCwd) {
        if (worktree != null && !worktree.isBlank()) {
            return Path.of(worktree).toAbsolutePath().normalize();
        }
        // Prefer the agent's threaded cwd over the global process cwd; user.dir only as a last resort.
        String dir = sessionCwd != null && !sessionCwd.isBlank() ? sessionCwd : System.getProperty("user.dir");
        return Path.of(dir);
    }

    ToolResult doBuild(Path projectRoot) throws IOException {
        if (!Files.exists(projectRoot.resolve("pom.xml")))
            return ToolResult.error("No pom.xml found at " + projectRoot + "\nRecovery: cd " + projectRoot
                                            + " && mvn package -Pfat -q");

        try {
            Path currentJar = findCurrentJar();
            backupJar(currentJar);
        } catch (IOException e) {
            // non-fatal — backup is best-effort
        }

        ProcessRunner.Result result;
        try {
            result = buildJar(projectRoot);
        } catch (IOException e) {
            if (ProcessRunner.isCommandNotFound(e))
                return ToolResult.error(MVN_NOT_FOUND);
            return ToolResult.error("✕ Transcend failed\n\nBuild error: " + e.getMessage() + "\n\nRecovery: cd "
                                            + projectRoot + " && mvn package -Pfat -q");
        }
        Path log = writeTranscendLog("build", result.stdout());

        if (result.timedOut()) {
            return ToolResult.error(failure("✓ preserved current build", "✕ compiled new build", "Build timed out.",
                                            log, "mvn package -Pfat -q"));
        }

        var details = new LinkedHashMap<String, Object>();
        details.put("exitCode", result.exitCode());
        if (log != null)
            details.put("log", log.toString());

        if (result.exitCode() != 0) {
            return ToolResult.error(failure("✓ preserved current build", "✕ compiled new build",
                                            "Build exited " + result.exitCode() + ".", log, "mvn package -Pfat -q"));
        }

        Path jar = findJarIn(projectRoot);
        if (jar != null && Files.exists(jar)) {
            details.put("jar", jar.getFileName().toString());
        } else {
            return ToolResult.error(failure("✓ preserved current build", "✕ compiled new build",
                                            "Build finished, but no jar was found in target/.", log,
                                            "mvn package -Pfat -q"));
        }

        return ToolResult.success("→ Transcend\n  ✓ preserved current build\n  ✓ compiled new build  "
                                        + jar.getFileName(), details);
    }

    ToolResult doTest(Path projectRoot) throws IOException {
        if (!Files.exists(projectRoot.resolve("pom.xml")))
            return ToolResult.error("No pom.xml found at " + projectRoot + "\nRecovery: cd " + projectRoot
                                            + " && mvn test");

        ProcessRunner.Result result;
        try {
            result = runMvnTest(projectRoot);
        } catch (IOException e) {
            if (ProcessRunner.isCommandNotFound(e))
                return ToolResult.error(MVN_NOT_FOUND);
            throw e;
        }
        Path log = writeTranscendLog("test", result.stdout());

        if (result.timedOut()) {
            return ToolResult.error(failure("✕ tests failed", null, "Tests timed out.", log, "mvn test"));
        }

        var details = new LinkedHashMap<String, Object>();
        details.put("exitCode", result.exitCode());
        if (log != null)
            details.put("log", log.toString());

        if (result.exitCode() != 0) {
            return ToolResult.error(failure("✕ tests failed", null, "Tests exited " + result.exitCode() + ".", log,
                                            "mvn test"));
        }

        return ToolResult.success("→ Transcend\n  ✓ tests passed\n\nNext: transcendence action=\"respawn\" with the same worktree.",
                                        details);
    }

    ToolResult doRespawn(Path projectRoot, String mergeBranch) throws IOException {
        if (!Files.exists(projectRoot.resolve("pom.xml")))
            return ToolResult.error("No pom.xml found at " + projectRoot + "\nRecovery: cd " + projectRoot
                                            + " && mvn package -Pfat -q");

        try {
            Path currentJar = findCurrentJar();
            backupJar(currentJar);
        } catch (IOException e) {
            // non-fatal — backup is best-effort
        }

        ProcessRunner.Result buildResult;
        try {
            buildResult = buildJar(projectRoot);
        } catch (IOException e) {
            if (ProcessRunner.isCommandNotFound(e))
                return ToolResult.error(MVN_NOT_FOUND);
            return ToolResult.error("✕ Transcend failed\n\nBuild error: " + e.getMessage() + "\n\nRecovery: cd "
                                            + projectRoot + " && mvn package -Pfat -q");
        }
        Path log = writeTranscendLog("build", buildResult.stdout());

        if (buildResult.exitCode() != 0 || buildResult.timedOut()) {
            return ToolResult.error(failure("✓ preserved current build", "✕ compiled new build",
                                            buildResult.timedOut()
                                                                            ? "Build timed out."
                                                                            : "Build exited " + buildResult.exitCode()
                                                                                                            + ".",
                                            log, "mvn package -Pfat -q"));
        }

        Path jar = findJarIn(projectRoot);
        if (jar == null || !Files.exists(jar)) {
            return ToolResult.error(failure("✓ preserved current build", "✕ compiled new build",
                                            "Build finished, but no jar was found in target/.", log,
                                            "mvn package -Pfat -q"));
        }

        if (!requestRespawn(jar, mergeBranch, projectRoot)) {
            return ToolResult.success("→ Transcend\n  ✓ preserved current build\n  ✓ compiled new build  "
                                            + jar.getFileName() + "\n  … restart already pending",
                                            Map.of("jar", jar.getFileName().toString(), "warning",
                                                                            "respawn already pending"));
        }

        var details = new LinkedHashMap<String, Object>();
        details.put("jar", jar.getFileName().toString());
        if (log != null)
            details.put("log", log.toString());
        if (mergeBranch != null && !mergeBranch.isBlank())
            details.put("mergeBranch", mergeBranch.strip());

        return ToolResult.success("→ Transcend\n  ✓ preserved current build\n  ✓ compiled new build  "
                                        + jar.getFileName()
                                        + "\n  ✓ restart queued\n\n✓ Pijota will restart into the new build after this turn.",
                                        details);
    }

    ToolResult doRollback(Path projectRoot) {
        if (!canRollback())
            return ToolResult.error("No backup jar found. Nothing to rollback to."
                                            + "\nRecovery: mvn package -Pfat -q && retry transcend");

        Path jar = findJarIn(projectRoot);
        if (jar == null) {
            jar = findCurrentJar();
            if (jar == null)
                return ToolResult.error("Cannot determine target jar for rollback. "
                                                + "Restore manually: cp target/pijota-backup.jar target/<jar>");
        }

        try {
            rollbackJar(jar);
        } catch (IOException e) {
            return ToolResult.error("Rollback failed: " + e.getMessage() + "\nRecovery: cp target/pijota-backup.jar "
                                            + jar);
        }

        requestRespawn(jar);
        return ToolResult.success("→ Transcend\n  ✓ restored previous build\n  ✓ restart queued");
    }

    static String failure(String step1, String step2, String message, Path log) {
        return failure(step1, step2, message, log, null);
    }

    static String failure(String step1, String step2, String message, Path log, String recovery) {
        var sb = new StringBuilder("→ Transcend\n");
        if (step1 != null)
            sb.append("  ").append(step1).append('\n');
        if (step2 != null)
            sb.append("  ").append(step2).append('\n');
        sb.append("\nStill running previous build.\n").append(message);
        if (log != null)
            sb.append("\nlog: ").append(log);
        if (recovery != null && !recovery.isBlank())
            sb.append("\nrecovery: ").append(recovery);
        return sb.toString();
    }

    private static Path writeTranscendLog(String label, String output) {
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

    Path findCurrentJar() {
        return SelfRespawn.findCurrentJar();
    }

    void backupJar(Path jar) throws IOException {
        SelfRespawn.backupJar(jar);
    }

    ProcessRunner.Result buildJar(Path projectRoot) throws IOException {
        return SelfRespawn.buildJar(projectRoot);
    }

    Path findJarIn(Path projectDir) {
        return SelfRespawn.findJarIn(projectDir);
    }

    ProcessRunner.Result runMvnTest(Path projectRoot) throws IOException {
        return ProcessRunner.command("mvn", "test").workDir(projectRoot).redirectErrorStream(true).run();
    }

    boolean requestRespawn(Path jar) {
        return SelfRespawn.requestRespawn(jar);
    }

    boolean requestRespawn(Path jar, String mergeBranch, Path mergeWorktree) {
        return SelfRespawn.requestRespawn(jar, mergeBranch, mergeWorktree);
    }

    boolean canRollback() {
        return SelfRespawn.canRollback();
    }

    void rollbackJar(Path jar) throws IOException {
        SelfRespawn.rollback(jar);
    }
}
