package ai.pijota.testkit;

import ai.pijota.tools.Tool;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a {@link Tool} and returns its unwrapped {@link Tool.ToolResult}, hiding the dummy call-id and the future-unwrap
 * ceremony that every tool test would otherwise repeat. A tool that does not complete within a generous deadline fails
 * the test rather than hanging it.
 */
public final class ToolRunner {
    private static final long TIMEOUT_SECONDS = 10;

    private ToolRunner() {}

    public static Tool.ToolResult run(Tool tool, Map<String, Object> args, String cwd) {
        try {
            return tool.execute("test-call", args == null ? Map.of() : args, cwd).get(TIMEOUT_SECONDS,
                                            TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw cause instanceof RuntimeException re ? re : new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (TimeoutException e) {
            throw new AssertionError("tool '" + tool.name() + "' did not complete within " + TIMEOUT_SECONDS + "s", e);
        }
    }

    public static Tool.ToolResult run(Tool tool, Map<String, Object> args, Path cwd) {
        return run(tool, args, cwd == null ? null : cwd.toString());
    }

    public static Tool.ToolResult run(Tool tool, Map<String, Object> args) {
        return run(tool, args, (String) null);
    }
}
