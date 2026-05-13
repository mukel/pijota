package ai.pijota.tools;

import ai.pijota.util.ProcessRunner;
import ai.pijota.util.SelfRespawn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class TranscendentalTool implements Tool {

    @Override public String name() { return "transcendental"; }

    @Override
    public String description() {
        return "Self-modification tool. Edit pijota's own source code, then use this tool to build, test, or respawn. "
            + "Actions: 'build' (mvn package), 'test' (mvn test), "
            + "'respawn' (build + restart with new jar), 'rollback' (restore backup jar + restart). "
            + "Use 'worktree' param to operate on a git worktree path. "
            + "Always build and test before respawning. If respawn fails, use rollback to revert.";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "action", Map.of(
                    "type", "string",
                    "description", "Action: 'build', 'test', 'respawn', or 'rollback'"
                ),
                "worktree", Map.of(
                    "type", "string",
                    "description", "Optional path to git worktree. If set, operates on that worktree instead of current directory."
                )
            ),
            "required", List.of("action"),
            "additionalProperties", false
        );
    }

    @Override public ExecutionMode executionMode() { return ExecutionMode.SEQUENTIAL; }

    @Override
    public CompletableFuture<ToolResult> execute(
            String toolCallId, Map<String, Object> args, String sessionCwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String action = (String) args.get("action");
                if (action == null)
                    return ToolResult.error("Missing required field: action");

                String worktree = (String) args.get("worktree");
                Path projectRoot = worktree != null && !worktree.isBlank()
                    ? Path.of(worktree)
                    : Path.of(System.getProperty("user.dir"));

                return switch (action.toLowerCase()) {
                    case "build" -> doBuild(projectRoot);
                    case "test" -> doTest(projectRoot);
                    case "respawn" -> doRespawn(projectRoot);
                    case "rollback" -> doRollback(projectRoot);
                    default -> ToolResult.error("Unknown action: " + action
                        + ". Valid: build, test, respawn, rollback");
                };
            } catch (Exception e) {
                return ToolResult.error("Transcendental error: " + e.getMessage());
            }
        });
    }

    private ToolResult doBuild(Path projectRoot) throws IOException {
        if (!Files.exists(projectRoot.resolve("pom.xml")))
            return ToolResult.error("No pom.xml found at " + projectRoot);

        try {
            Path currentJar = SelfRespawn.findCurrentJar();
            SelfRespawn.backupJar(currentJar);
        } catch (IOException e) {
            // non-fatal
        }

        var sb = new StringBuilder();
        sb.append("[build] ").append(projectRoot).append("\n");

        ProcessRunner.Result result;
        try {
            result = SelfRespawn.buildJar(projectRoot);
        } catch (IOException e) {
            return ToolResult.error("[build] error: " + e.getMessage());
        }

        sb.append(result.stdout());

        if (result.timedOut()) {
            sb.append("\n[Build timed out]");
            return ToolResult.error(sb.toString());
        }

        var details = new LinkedHashMap<String, Object>();
        details.put("exitCode", result.exitCode());

        if (result.exitCode() != 0) {
            sb.append("\n[Build FAILED — exit ").append(result.exitCode()).append("]");
            sb.append("\n[Backup jar preserved. Fix errors and rebuild.]");
            return ToolResult.error(sb.toString());
        }

        Path jar = SelfRespawn.findJarIn(projectRoot);
        if (jar != null && Files.exists(jar)) {
            sb.append("\n[Build OK — ").append(jar.getFileName()).append("]");
            details.put("jar", jar.getFileName().toString());
        } else {
            sb.append("\n[Build completed but jar not found in target/]");
            return ToolResult.error(sb.toString());
        }

        return ToolResult.success(sb.toString(), details);
    }

    private ToolResult doTest(Path projectRoot) throws IOException {
        if (!Files.exists(projectRoot.resolve("pom.xml")))
            return ToolResult.error("No pom.xml found at " + projectRoot);

        var sb = new StringBuilder();
        sb.append("[test] ").append(projectRoot).append("\n");

        ProcessRunner.Result result = ProcessRunner.command("mvn", "test")
            .workDir(projectRoot)
            .redirectErrorStream(true)
            .run();

        sb.append(result.stdout());

        if (result.timedOut()) {
            sb.append("\n[Tests timed out]");
            return ToolResult.error(sb.toString());
        }

        var details = new LinkedHashMap<String, Object>();
        details.put("exitCode", result.exitCode());

        if (result.exitCode() != 0) {
            sb.append("\n[Tests FAILED — exit ").append(result.exitCode()).append("]");
            return ToolResult.error(sb.toString());
        }

        sb.append("\n[All tests passed]");
        return ToolResult.success(sb.toString(), details);
    }

    private ToolResult doRespawn(Path projectRoot) throws IOException {
        if (!Files.exists(projectRoot.resolve("pom.xml")))
            return ToolResult.error("No pom.xml found at " + projectRoot);

        try {
            Path currentJar = SelfRespawn.findCurrentJar();
            SelfRespawn.backupJar(currentJar);
        } catch (IOException e) {
            // non-fatal
        }

        var sb = new StringBuilder();
        sb.append("[respawn] building ").append(projectRoot).append("...\n");

        ProcessRunner.Result buildResult;
        try {
            buildResult = SelfRespawn.buildJar(projectRoot);
        } catch (IOException e) {
            return ToolResult.error("[respawn] build error: " + e.getMessage());
        }

        sb.append(buildResult.stdout());

        if (buildResult.exitCode() != 0 || buildResult.timedOut()) {
            sb.append("\n[Build FAILED — respawn aborted]");
            sb.append("\n[Backup jar preserved. Fix errors and try again.]");
            return ToolResult.error(sb.toString());
        }

        Path jar = SelfRespawn.findJarIn(projectRoot);
        if (jar == null || !Files.exists(jar)) {
            sb.append("\n[Jar not found after build — respawn aborted]");
            return ToolResult.error(sb.toString());
        }

        SelfRespawn.requestRespawn(jar);

        var details = new LinkedHashMap<String, Object>();
        details.put("jar", jar.getFileName().toString());

        sb.append("\n[Build OK — ").append(jar.getFileName()).append("]");
        sb.append("\n[Respawn queued. Process will restart after this turn completes.]");
        sb.append("\n[If the new version is broken, you can recover by running:]");
        sb.append("\n  java -jar target/pijota-backup.jar --continue");
        return ToolResult.success(sb.toString(), details);
    }

    private ToolResult doRollback(Path projectRoot) {
        if (!SelfRespawn.canRollback())
            return ToolResult.error("No backup jar found. Nothing to rollback to.");

        Path jar = SelfRespawn.findJarIn(projectRoot);
        try {
            SelfRespawn.rollback(jar);
        } catch (IOException e) {
            return ToolResult.error("Rollback failed: " + e.getMessage());
        }

        SelfRespawn.requestRespawn(jar);
        return ToolResult.success("Rolled back to backup jar. Respawn queued — restarting after this turn.");
    }
}
