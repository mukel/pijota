package org.pijota.tools;

import static org.pijota.testkit.ToolRunner.run;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.pijota.util.ProcessRunner;

class TranscendenceToolTest {

    @TempDir
    Path tempDir;

    // ─── test fixture: a TranscendenceTool whose external deps are stubbed ───

    private static class TestTranscendenceTool extends TranscendenceTool {
        ProcessRunner.Result buildResult = new ProcessRunner.Result(0, "BUILD SUCCESS", "", false, false);
        ProcessRunner.Result testResult = new ProcessRunner.Result(0, "Tests run: 1", "", false, false);
        boolean jarFound = true;
        boolean respawnResult = true;
        boolean canRollbackResult = false;
        boolean backupFails = false;
        boolean rollbackFails = false;
        boolean mvnNotFound = false;
        boolean buildThrows = false;
        Path currentJar = Path.of("target", "pijota-current.jar");

        @Override
        Path findCurrentJar() {
            return currentJar;
        }

        @Override
        void backupJar(Path jar) throws IOException {
            if (backupFails)
                throw new IOException("backup failed");
        }

        @Override
        ProcessRunner.Result buildJar(Path projectRoot) throws IOException {
            if (buildThrows)
                throw new IOException("build exploded");
            if (mvnNotFound)
                throw new IOException("Cannot run program \"mvn\": error=2, No such file or directory");
            return buildResult;
        }

        @Override
        Path findJarIn(Path projectDir) {
            if (!jarFound)
                return null;
            return projectDir.resolve("target").resolve("pijota-test.jar");
        }

        @Override
        ProcessRunner.Result runMvnTest(Path projectRoot) throws IOException {
            if (mvnNotFound)
                throw new IOException("Cannot run program \"mvn\": error=2, No such file or directory");
            return testResult;
        }

        @Override
        boolean requestRespawn(Path jar) {
            return respawnResult;
        }

        @Override
        boolean requestRespawn(Path jar, String mergeBranch, Path mergeWorktree) {
            return respawnResult;
        }

        @Override
        boolean canRollback() {
            return canRollbackResult;
        }

        @Override
        void rollbackJar(Path jar) throws IOException {
            if (rollbackFails)
                throw new IOException("rollback I/O error");
        }
    }

    private Path projectWithPom() throws IOException {
        var dir = Files.createTempDirectory(tempDir, "project");
        Files.writeString(dir.resolve("pom.xml"), "<project></project>");
        var target = Files.createDirectories(dir.resolve("target"));
        Files.writeString(target.resolve("pijota-test.jar"), "dummy");
        return dir;
    }

    // ─── metadata ───

    @Test
    void nameIsTranscendence() {
        assertEquals("transcendence", new TranscendenceTool().name());
    }

    @Test
    void executionModeIsSequential() {
        assertEquals(Tool.ExecutionMode.SEQUENTIAL, new TranscendenceTool().executionMode());
    }

    @Test
    void descriptionDescribesSelfModification() {
        var tool = new TranscendenceTool();
        assertNotNull(tool.description());
        assertTrue(tool.description().toLowerCase().contains("transcend")
                                        || tool.description().toLowerCase().contains("self"),
                                        "description should mention transcendence: " + tool.description());
    }

    @Test
    void schemaHasActionRequired() {
        assertTrue(new TranscendenceTool().schema().get("required") instanceof List);
    }

    @Test
    void defaultEnabledIsTrue() {
        assertTrue(new TranscendenceTool().defaultEnabled());
    }

    // ─── resolveProjectRoot ───

    @Test
    void resolveProjectRootUsesWorktreeWhenProvided() {
        var path = TranscendenceTool.resolveProjectRoot("/custom/worktree", "/ignored/session/cwd");
        assertEquals(Path.of("/custom/worktree").toAbsolutePath().normalize(), path);
    }

    @Test
    void resolveProjectRootUsesSessionCwdWhenNoWorktree() {
        assertEquals(Path.of("/some/session/dir"), TranscendenceTool.resolveProjectRoot(null, "/some/session/dir"));
    }

    @Test
    void resolveProjectRootFallsBackWhenNull() {
        assertNotNull(TranscendenceTool.resolveProjectRoot(null, null));
        assertTrue(TranscendenceTool.resolveProjectRoot(null, null).isAbsolute());
    }

    @Test
    void resolveProjectRootFallsBackWhenBlank() {
        assertNotNull(TranscendenceTool.resolveProjectRoot("  ", "  "));
        assertTrue(TranscendenceTool.resolveProjectRoot("  ", "  ").isAbsolute());
    }

    // ─── failure() ───

    @Test
    void failureWithAllArgs() {
        var result = TranscendenceTool.failure("step1", "step2", "error message", Path.of("/tmp/log"));
        assertTrue(result.contains("step1"));
        assertTrue(result.contains("step2"));
        assertTrue(result.contains("error message"));
        assertTrue(result.contains("/tmp/log"));
    }

    @Test
    void failureWithNullStep() {
        var result = TranscendenceTool.failure(null, "step2", "msg", null);
        assertFalse(result.contains("null"));
        assertTrue(result.contains("step2"));
        assertTrue(result.contains("msg"));
        assertFalse(result.contains("log:"));
    }

    @Test
    void failureWithNullStep2() {
        var result = TranscendenceTool.failure("step1", null, "msg", null);
        assertTrue(result.contains("step1"));
        assertTrue(result.contains("msg"));
    }

    @Test
    void failureStartsWithTranscend() {
        assertTrue(TranscendenceTool.failure("a", null, "msg", null).startsWith("→ Transcend"));
    }

    @Test
    void failureWithRecoveryHint() {
        var result = TranscendenceTool.failure("step1", "step2", "error message", Path.of("/tmp/log"),
                                        "mvn package -Pfat -q");
        assertTrue(result.contains("recovery: mvn package -Pfat -q"));
        assertTrue(result.contains("step1"));
    }

    @Test
    void failureWithNullRecoveryOmitsRecoveryLine() {
        var result = TranscendenceTool.failure("step1", "step2", "error message", Path.of("/tmp/log"), null);
        assertFalse(result.contains("recovery:"));
    }

    @Test
    void failureWithBlankRecoveryOmitsRecoveryLine() {
        var result = TranscendenceTool.failure("step1", "step2", "error message", Path.of("/tmp/log"), "  ");
        assertFalse(result.contains("recovery:"));
    }

    @Test
    void failureContainsStillRunningMessage() {
        var result = TranscendenceTool.failure("s1", "s2", "msg", null);
        assertTrue(result.contains("Still running previous build"));
    }

    // ─── execute() action dispatch ───

    @Test
    void executeWithUnknownActionReturnsError() {
        var result = run(new TranscendenceTool(), Map.of("action", "invalid"), "/cwd");
        assertTrue(result.isError());
        assertTrue(result.content().contains("Unknown action"));
    }

    @Test
    void executeWithUppercaseUnknownActionReturnsError() {
        var result = run(new TranscendenceTool(), Map.of("action", "FOO"), "/cwd");
        assertTrue(result.isError());
        assertTrue(result.content().contains("Unknown action"));
    }

    @Test
    void executeWithMissingActionReturnsError() {
        var result = run(new TranscendenceTool(), Map.of(), "/cwd");
        assertTrue(result.isError());
        assertTrue(result.content().contains("Missing required field"));
    }

    @Test
    void executeWithBlankActionReturnsError() {
        var result = run(new TranscendenceTool(), Map.of("action", "  "), "/cwd");
        assertTrue(result.isError());
    }

    @Test
    void executeActionIsCaseInsensitive() {
        var tool = new TestTranscendenceTool();
        tool.jarFound = false;
        var result = run(tool, Map.of("action", "BUILD", "worktree", "/tmp/no-pom"), "/cwd");
        assertTrue(result.isError());
        assertTrue(result.content().contains("No pom.xml"));
    }

    @Test
    void executeExceptionReturnsError() {
        var tool = new TranscendenceTool() {
            @Override
            Path findCurrentJar() {
                throw new RuntimeException("unexpected");
            }
        };
        var result = run(tool, Map.of("action", "build", "worktree", "/tmp/nonexistent-pom"), "/cwd");
        assertTrue(result.isError());
    }

    // ─── doBuild ───

    @Test
    void buildWithoutPomXmlReturnsError() throws Exception {
        var tool = new TestTranscendenceTool();
        var result = tool.doBuild(tempDir);
        assertTrue(result.isError());
        assertTrue(result.content().contains("No pom.xml"));
    }

    @Test
    void buildSuccessReturnsSuccessWithJarName() throws Exception {
        var tool = new TestTranscendenceTool();
        var result = tool.doBuild(projectWithPom());
        assertFalse(result.isError());
        assertTrue(result.content().contains("compiled new build"));
        assertTrue(result.content().contains("pijota-test.jar"));
        assertEquals(0, result.details().get("exitCode"));
    }

    @Test
    void buildFailureReturnsErrorWithExitCode() throws Exception {
        var tool = new TestTranscendenceTool();
        tool.buildResult = new ProcessRunner.Result(1, "BUILD FAILURE", "", false, false);
        var result = tool.doBuild(projectWithPom());
        assertTrue(result.isError());
        assertTrue(result.content().contains("Build exited 1"));
    }

    @Test
    void buildTimeoutReturnsError() throws Exception {
        var tool = new TestTranscendenceTool();
        tool.buildResult = new ProcessRunner.Result(0, "", "", true, false);
        var result = tool.doBuild(projectWithPom());
        assertTrue(result.isError());
        assertTrue(result.content().contains("timed out"));
    }

    @Test
    void buildSucceedsButNoJarReturnsError() throws Exception {
        var tool = new TestTranscendenceTool();
        tool.jarFound = false;
        var result = tool.doBuild(projectWithPom());
        assertTrue(result.isError());
        assertTrue(result.content().contains("no jar was found"));
    }

    @Test
    void buildMvnNotFoundReturnsSpecificError() throws Exception {
        var tool = new TestTranscendenceTool();
        tool.mvnNotFound = true;
        var result = tool.doBuild(projectWithPom());
        assertTrue(result.isError());
        assertTrue(result.content().contains("Maven (mvn) not found"));
    }

    @Test
    void buildIOExceptionReturnsError() throws Exception {
        var tool = new TestTranscendenceTool();
        tool.buildThrows = true;
        var result = tool.doBuild(projectWithPom());
        assertTrue(result.isError());
        assertTrue(result.content().contains("Build error"));
        assertTrue(result.content().contains("build exploded"));
    }

    @Test
    void buildBackupFailureIsNonFatal() throws Exception {
        var tool = new TestTranscendenceTool();
        tool.backupFails = true;
        var result = tool.doBuild(projectWithPom());
        assertFalse(result.isError());
        assertTrue(result.content().contains("compiled new build"));
    }

    @Test
    void buildSuccessDetailsIncludeLog() throws Exception {
        var tool = new TestTranscendenceTool();
        tool.buildResult = new ProcessRunner.Result(0, "BUILD SUCCESS output", "", false, false);
        var result = tool.doBuild(projectWithPom());
        assertFalse(result.isError());
        assertNotNull(result.details().get("log"));
    }

    // ─── doTest ───

    @Test
    void testWithoutPomXmlReturnsError() throws Exception {
        var tool = new TestTranscendenceTool();
        var result = tool.doTest(tempDir);
        assertTrue(result.isError());
        assertTrue(result.content().contains("No pom.xml"));
    }

    @Test
    void testSuccessReturnsPassedMessage() throws Exception {
        var tool = new TestTranscendenceTool();
        var result = tool.doTest(projectWithPom());
        assertFalse(result.isError());
        assertTrue(result.content().contains("tests passed"));
        assertTrue(result.content().contains("respawn"));
        assertEquals(0, result.details().get("exitCode"));
    }

    @Test
    void testFailureReturnsErrorWithExitCode() throws Exception {
        var tool = new TestTranscendenceTool();
        tool.testResult = new ProcessRunner.Result(1, "Tests failed", "", false, false);
        var result = tool.doTest(projectWithPom());
        assertTrue(result.isError());
        assertTrue(result.content().contains("Tests exited 1"));
    }

    @Test
    void testTimeoutReturnsError() throws Exception {
        var tool = new TestTranscendenceTool();
        tool.testResult = new ProcessRunner.Result(0, "", "", true, false);
        var result = tool.doTest(projectWithPom());
        assertTrue(result.isError());
        assertTrue(result.content().contains("timed out"));
    }

    @Test
    void testMvnNotFoundReturnsSpecificError() throws Exception {
        var tool = new TestTranscendenceTool();
        tool.mvnNotFound = true;
        var result = tool.doTest(projectWithPom());
        assertTrue(result.isError());
        assertTrue(result.content().contains("Maven (mvn) not found"));
    }

    // ─── doRespawn ───

    @Test
    void respawnWithoutPomXmlReturnsError() throws Exception {
        var tool = new TestTranscendenceTool();
        var result = tool.doRespawn(tempDir, null);
        assertTrue(result.isError());
        assertTrue(result.content().contains("No pom.xml"));
    }

    @Test
    void respawnSuccessReturnsRestartQueued() throws Exception {
        var tool = new TestTranscendenceTool();
        var result = tool.doRespawn(projectWithPom(), null);
        assertFalse(result.isError());
        assertTrue(result.content().contains("restart queued"));
        assertTrue(result.content().contains("pijota-test.jar"));
        assertEquals("pijota-test.jar", result.details().get("jar"));
    }

    @Test
    void respawnBuildFailureReturnsError() throws Exception {
        var tool = new TestTranscendenceTool();
        tool.buildResult = new ProcessRunner.Result(1, "BUILD FAILURE", "", false, false);
        var result = tool.doRespawn(projectWithPom(), null);
        assertTrue(result.isError());
        assertTrue(result.content().contains("Build exited 1"));
    }

    @Test
    void respawnBuildTimeoutReturnsError() throws Exception {
        var tool = new TestTranscendenceTool();
        tool.buildResult = new ProcessRunner.Result(0, "", "", true, false);
        var result = tool.doRespawn(projectWithPom(), null);
        assertTrue(result.isError());
        assertTrue(result.content().contains("timed out"));
    }

    @Test
    void respawnBuildSucceedsButNoJarReturnsError() throws Exception {
        var tool = new TestTranscendenceTool();
        tool.jarFound = false;
        var result = tool.doRespawn(projectWithPom(), null);
        assertTrue(result.isError());
        assertTrue(result.content().contains("no jar was found"));
    }

    @Test
    void respawnMvnNotFoundReturnsSpecificError() throws Exception {
        var tool = new TestTranscendenceTool();
        tool.mvnNotFound = true;
        var result = tool.doRespawn(projectWithPom(), null);
        assertTrue(result.isError());
        assertTrue(result.content().contains("Maven (mvn) not found"));
    }

    @Test
    void respawnAlreadyPendingReturnsWarning() throws Exception {
        var tool = new TestTranscendenceTool();
        tool.respawnResult = false;
        var result = tool.doRespawn(projectWithPom(), null);
        assertFalse(result.isError());
        assertTrue(result.content().contains("restart already pending"));
        assertEquals("respawn already pending", result.details().get("warning"));
    }

    @Test
    void respawnWithMergeBranchIncludesItInDetails() throws Exception {
        var tool = new TestTranscendenceTool();
        var result = tool.doRespawn(projectWithPom(), "feature-branch");
        assertFalse(result.isError());
        assertEquals("feature-branch", result.details().get("mergeBranch"));
    }

    @Test
    void respawnWithBlankMergeBranchOmitsIt() throws Exception {
        var tool = new TestTranscendenceTool();
        var result = tool.doRespawn(projectWithPom(), "  ");
        assertFalse(result.isError());
        assertNull(result.details().get("mergeBranch"));
    }

    // ─── doRollback ───

    @Test
    void rollbackWithoutBackupReturnsError() {
        var tool = new TestTranscendenceTool();
        tool.canRollbackResult = false;
        var result = tool.doRollback(Path.of("/tmp/project"));
        assertTrue(result.isError());
        assertTrue(result.content().contains("No backup jar found"));
    }

    @Test
    void rollbackSuccessReturnsRestoredMessage() {
        var tool = new TestTranscendenceTool();
        tool.canRollbackResult = true;
        var result = tool.doRollback(Path.of("/tmp/project"));
        assertFalse(result.isError());
        assertTrue(result.content().contains("restored previous build"));
        assertTrue(result.content().contains("restart queued"));
    }

    @Test
    void rollbackFailureReturnsErrorWithRecovery() {
        var tool = new TestTranscendenceTool();
        tool.canRollbackResult = true;
        tool.rollbackFails = true;
        var result = tool.doRollback(Path.of("/tmp/project"));
        assertTrue(result.isError());
        assertTrue(result.content().contains("Rollback failed"));
        assertTrue(result.content().contains("rollback I/O error"));
    }

    @Test
    void rollbackNoJarFoundAndNoCurrentJarReturnsError() {
        var tool = new TestTranscendenceTool() {
            @Override
            Path findCurrentJar() {
                return null;
            }
        };
        tool.canRollbackResult = true;
        tool.jarFound = false;
        var result = tool.doRollback(Path.of("/tmp/project"));
        assertTrue(result.isError());
        assertTrue(result.content().contains("Cannot determine target jar"));
    }

    @Test
    void rollbackFallsBackToCurrentJarWhenNoJarInProject() {
        var tool = new TestTranscendenceTool();
        tool.canRollbackResult = true;
        tool.jarFound = false;
        tool.currentJar = Path.of("target", "pijota-fallback.jar");
        var result = tool.doRollback(Path.of("/tmp/project"));
        assertFalse(result.isError());
        assertTrue(result.content().contains("restored previous build"));
    }

    // ─── execute() integration with worktree ───

    @Test
    void executeBuildWithPomViaWorktree() throws Exception {
        var project = projectWithPom();
        var tool = new TestTranscendenceTool();
        var result = run(tool, Map.of("action", "build", "worktree", project.toString()), "/cwd");
        assertFalse(result.isError());
        assertTrue(result.content().contains("compiled new build"));
    }

    @Test
    void executeTestWithPomViaWorktree() throws Exception {
        var project = projectWithPom();
        var tool = new TestTranscendenceTool();
        var result = run(tool, Map.of("action", "test", "worktree", project.toString()), "/cwd");
        assertFalse(result.isError());
        assertTrue(result.content().contains("tests passed"));
    }

    @Test
    void executeRespawnWithPomViaWorktree() throws Exception {
        var project = projectWithPom();
        var tool = new TestTranscendenceTool();
        var result = run(tool, Map.of("action", "respawn", "worktree", project.toString()), "/cwd");
        assertFalse(result.isError());
        assertTrue(result.content().contains("restart queued"));
    }

    @Test
    void executeRollbackWithBackup() {
        var tool = new TestTranscendenceTool();
        tool.canRollbackResult = true;
        var result = run(tool, Map.of("action", "rollback"), "/cwd");
        assertFalse(result.isError());
        assertTrue(result.content().contains("restored"));
    }
}
