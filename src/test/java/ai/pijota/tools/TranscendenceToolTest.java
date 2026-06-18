package ai.pijota.tools;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static ai.pijota.testkit.ToolRunner.run;
import static org.junit.jupiter.api.Assertions.*;

class TranscendenceToolTest {

    @Test
    void nameIsTranscendence() {
        var tool = new TranscendenceTool();
        assertEquals("transcendence", tool.name());
    }

    @Test
    void executionModeIsSequential() {
        var tool = new TranscendenceTool();
        assertEquals(Tool.ExecutionMode.SEQUENTIAL, tool.executionMode());
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
        var tool = new TranscendenceTool();
        assertTrue(tool.schema().get("required") instanceof java.util.List);
    }

    @Test
    void resolveProjectRootUsesWorktreeWhenProvided() {
        var path = TranscendenceTool.resolveProjectRoot("/custom/worktree");
        assertEquals(Path.of("/custom/worktree").toAbsolutePath().normalize(), path);
    }

    @Test
    void resolveProjectRootFallsBackWhenNull() {
        var path = TranscendenceTool.resolveProjectRoot(null);
        assertNotNull(path);
        assertTrue(path.isAbsolute());
    }

    @Test
    void resolveProjectRootFallsBackWhenBlank() {
        var path = TranscendenceTool.resolveProjectRoot("  ");
        assertNotNull(path);
        assertTrue(path.isAbsolute());
    }

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
        var result = TranscendenceTool.failure("a", null, "msg", null);
        assertTrue(result.startsWith("→ Transcend"));
    }

    @Test
    void executeWithUnknownActionReturnsError() {
        var tool = new TranscendenceTool();
        var result = run(tool, java.util.Map.of("action", "invalid"), "/cwd");
        assertTrue(result.isError());
        assertTrue(result.content().contains("Unknown action"));
    }

    @Test
    void executeWithUppercaseUnknownActionReturnsError() {
        var tool = new TranscendenceTool();
        var result = run(tool, java.util.Map.of("action", "FOO"), "/cwd");
        assertTrue(result.isError());
        assertTrue(result.content().contains("Unknown action"));
    }

    @Test
    void executeWithMissingActionReturnsError() {
        var tool = new TranscendenceTool();
        var result = run(tool, java.util.Map.of(), "/cwd");
        assertTrue(result.isError());
        assertTrue(result.content().contains("Missing required field"));
    }

    @Test
    void executeWithBlankActionReturnsError() {
        var tool = new TranscendenceTool();
        var result = run(tool, java.util.Map.of("action", "  "), "/cwd");
        assertTrue(result.isError());
    }

    @Test
    void buildWithoutPomXmlReturnsError() {
        var tool = new TranscendenceTool();
        var result = run(tool, java.util.Map.of("action", "build", "worktree", "/tmp/no-pom-here"), "/cwd");
        assertTrue(result.isError() || result.content().contains("No pom.xml"));
    }

    @Test
    void failureWithRecoveryHint() {
        var result = TranscendenceTool.failure("step1", "step2", "error message", Path.of("/tmp/log"),
                                        "mvn package -Pfat -q");
        assertTrue(result.contains("recovery: mvn package -Pfat -q"));
        assertTrue(result.contains("step1"));
        assertTrue(result.contains("error message"));
    }

    @Test
    void failureWithNullRecoveryOmitsRecoveryLine() {
        var result = TranscendenceTool.failure("step1", "step2", "error message", Path.of("/tmp/log"), null);
        assertTrue(result.contains("step1"));
        assertTrue(result.contains("error message"));
        assertFalse(result.contains("recovery:"));
    }

    @Test
    void failureWithBlankRecoveryOmitsRecoveryLine() {
        var result = TranscendenceTool.failure("step1", "step2", "error message", Path.of("/tmp/log"), "  ");
        assertFalse(result.contains("recovery:"));
    }

    @Test
    void defaultEnabledIsTrue() {
        assertTrue(new TranscendenceTool().defaultEnabled());
    }
}
