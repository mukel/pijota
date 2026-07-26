package org.pijota.acp;

import org.pijota.tools.Tool;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the fs decorator: capability-gated routing to the client, local fallback, and metadata delegation. */
class AcpFileToolTest {

    /** A stub built-in tool whose execute tags its result "LOCAL" so we can tell which path ran. */
    private static Tool stub(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "desc-" + name;
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of("k", "v");
            }

            @Override
            public CompletableFuture<ToolResult> execute(String toolCallId, Args args, String cwd) {
                return CompletableFuture.completedFuture(ToolResult.success("LOCAL:" + args.getString("path")));
            }
        };
    }

    private static Tool.Args args(Map<String, Object> map) {
        return new Tool.Args(map);
    }

    @Test
    void delegatesToOriginalWhenCapabilityOff() {
        var tool = new AcpFileTool(stub("read"), () -> false, (a, cwd) -> Tool.ToolResult.success("CLIENT"));
        var result = tool.execute("id", args(Map.of("path", "/x")), "/cwd").join();
        assertEquals("LOCAL:/x", result.content());
    }

    @Test
    void routesToClientWhenCapabilityOn() {
        var tool = new AcpFileTool(stub("read"), () -> true,
                                        (a, cwd) -> Tool.ToolResult.success("CLIENT:" + a.getString("path")));
        var result = tool.execute("id", args(Map.of("path", "/x")), "/cwd").join();
        assertEquals("CLIENT:/x", result.content());
    }

    @Test
    void clientExecFailureBecomesAnErrorResult() {
        var tool = new AcpFileTool(stub("read"), () -> true, (a, cwd) -> {
            throw new RuntimeException("kaboom");
        });
        var result = tool.execute("id", args(Map.of()), "/cwd").join();
        assertTrue(result.isError());
        assertTrue(result.content().contains("kaboom"));
    }

    @Test
    void metadataDelegatesToTheOriginalTool() {
        var tool = new AcpFileTool(stub("write"), () -> true, (a, cwd) -> Tool.ToolResult.success(""));
        assertEquals("write", tool.name());
        assertEquals("desc-write", tool.description());
        assertEquals(Map.of("k", "v"), tool.schema());
    }
}
