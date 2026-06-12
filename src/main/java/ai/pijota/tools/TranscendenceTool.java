package ai.pijota.tools;

import ai.pijota.util.ProcessRunner;
import ai.pijota.util.SelfRespawn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class TranscendenceTool implements Tool {

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
        return Map.of("type", "object", "properties", Map.of("action", Map.of("type", "string", "description",
                                        "Action: 'build', 'test', 'respawn', or 'rollback'"), "worktree",
                                        Map.of("type", "string", "description",
                                                                        "Optional path to git worktree. If set, operates on that worktree instead of current directory."),
                                        "mergeBranch",
                                        Map.of("type", "string", "description",
                                                                        "Optional branch to merge into the original checkout after successful respawn. Use with action='respawn'.")),
                                        "required", List.of("action"), "additionalProperties", false);
    }

    @Override
    public ExecutionMode executionMode() {
        return ExecutionMode.SEQUENTIAL;
    }

    @Override
    public CompletableFuture<ToolResult> execute(String toolCallId, Map<String, Object> args, String sessionCwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String action = (String) args.get("action");
                if (action == null || action.isBlank())
                    return ToolResult.error("Missing required field: action");

                String worktree = (String) args.get("worktree");
                String mergeBranch = (String) args.get("mergeBranch");
                Path projectRoot = resolveProjectRoot(worktree);

                return switch (action.strip().toLowerCase()) {
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

    static Path resolveProjectRoot(String worktree) {
        if (worktree != null && !worktree.isBlank()) {
            return Path.of(worktree).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.dir"));
    }

    private ToolResult doBuild(Path projectRoot) throws IOException {
        if (!Files.exists(projectRoot.resolve("pom.xml")))
            return ToolResult.error("No pom.xml found at " + projectRoot + "\nRecovery: cd " + projectRoot
                                            + " && mvn package -Pfat -q");

        try {
            Path currentJar = SelfRespawn.findCurrentJar();
            SelfRespawn.backupJar(currentJar);
        } catch (IOException e) {
            // non-fatal — backup is best-effort
        }

        ProcessRunner.Result result;
        try {
            result = SelfRespawn.buildJar(projectRoot);
        } catch (IOException e) {
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

        Path jar = SelfRespawn.findJarIn(projectRoot);
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

    private ToolResult doTest(Path projectRoot) throws IOException {
        if (!Files.exists(projectRoot.resolve("pom.xml")))
            return ToolResult.error("No pom.xml found at " + projectRoot + "\nRecovery: cd " + projectRoot
                                            + " && mvn test");

        ProcessRunner.Result result = ProcessRunner.command("mvn", "test").workDir(projectRoot)
                                        .redirectErrorStream(true).run();
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

    private ToolResult doRespawn(Path projectRoot, String mergeBranch) throws IOException {
        if (!Files.exists(projectRoot.resolve("pom.xml")))
            return ToolResult.error("No pom.xml found at " + projectRoot + "\nRecovery: cd " + projectRoot
                                            + " && mvn package -Pfat -q");

        try {
            Path currentJar = SelfRespawn.findCurrentJar();
            SelfRespawn.backupJar(currentJar);
        } catch (IOException e) {
            // non-fatal — backup is best-effort
        }

        ProcessRunner.Result buildResult;
        try {
            buildResult = SelfRespawn.buildJar(projectRoot);
        } catch (IOException e) {
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

        Path jar = SelfRespawn.findJarIn(projectRoot);
        if (jar == null || !Files.exists(jar)) {
            return ToolResult.error(failure("✓ preserved current build", "✕ compiled new build",
                                            "Build finished, but no jar was found in target/.", log,
                                            "mvn package -Pfat -q"));
        }

        if (!SelfRespawn.requestRespawn(jar, mergeBranch, projectRoot)) {
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

    private ToolResult doRollback(Path projectRoot) {
        if (!SelfRespawn.canRollback())
            return ToolResult.error("No backup jar found. Nothing to rollback to."
                                            + "\nRecovery: mvn package -Pfat -q && retry transcend");

        Path jar = SelfRespawn.findJarIn(projectRoot);
        if (jar == null) {
            jar = SelfRespawn.findCurrentJar();
            if (jar == null)
                return ToolResult.error("Cannot determine target jar for rollback. "
                                                + "Restore manually: cp target/pijota-backup.jar target/<jar>");
        }

        try {
            SelfRespawn.rollback(jar);
        } catch (IOException e) {
            return ToolResult.error("Rollback failed: " + e.getMessage() + "\nRecovery: cp target/pijota-backup.jar "
                                            + jar);
        }

        SelfRespawn.requestRespawn(jar);
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
}
