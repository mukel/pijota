package org.pijota.acp;

import org.pijota.tools.ReadTool;
import org.pijota.tools.Tool;
import org.pijota.util.Util;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * Decorates a built-in file tool ({@code read}/{@code write}) so that, when the ACP client advertised the matching
 * filesystem capability, the tool's IO is routed through the client
 * ({@code fs/read_text_file}/{@code fs/write_text_file}) instead of the local disk, letting the editor's view (unsaved
 * buffers) drive reads and letting writes surface in the editor. When the capability is absent it delegates to the
 * original tool, so non-fs clients are unaffected. All metadata (name/schema/rendering) is delegated to the original,
 * so the LLM sees an identical tool.
 */
public final class AcpFileTool implements Tool {

    /** The client-backed execution, run off the caller thread when the capability is on. */
    @FunctionalInterface
    interface ClientExec {
        Tool.ToolResult run(Args args, String cwd) throws Exception;
    }

    private final Tool original;
    private final BooleanSupplier useClient;
    private final ClientExec clientExec;

    /** Package-visible for tests; production code goes through {@link #readOverride}/{@link #writeOverride}. */
    AcpFileTool(Tool original, BooleanSupplier useClient, ClientExec clientExec) {
        this.original = original;
        this.useClient = useClient;
        this.clientExec = clientExec;
    }

    /** {@code read} routed through {@code fs/read_text_file}, reusing {@link ReadTool#format} for identical output. */
    public static Tool readOverride(Tool original, AcpFrontend fe) {
        return new AcpFileTool(original, fe::fsReadEnabled, (args, cwd) -> {
            String path = absolutePath(args, cwd);
            if (path == null)
                return Tool.ToolResult.error("No path provided");
            String content = fe.fsReadTextFile(path).join();
            return ReadTool.format(content.lines().toList(), args.getInt("offset", 1), args.getInt("limit", 2000));
        });
    }

    /** {@code write} routed through {@code fs/write_text_file}. */
    public static Tool writeOverride(Tool original, AcpFrontend fe) {
        return new AcpFileTool(original, fe::fsWriteEnabled, (args, cwd) -> {
            String path = absolutePath(args, cwd);
            if (path == null)
                return Tool.ToolResult.error("No path provided");
            String content = args.getString("content");
            if (content == null)
                content = "";
            fe.fsWriteTextFile(path, content).join();
            long lines = content.lines().count();
            return Tool.ToolResult.success("Wrote " + content.length() + " bytes (" + lines + " lines)",
                                            Map.of("bytes", content.length(), "lines", lines, "path", path));
        });
    }

    private static String absolutePath(Args args, String cwd) {
        String resolved = Util.resolvePath(args.map(), cwd);
        return resolved == null ? null : Path.of(resolved).toAbsolutePath().normalize().toString();
    }

    @Override
    public CompletableFuture<ToolResult> execute(String toolCallId, Args arguments, String sessionCwd) {
        if (!useClient.getAsBoolean())
            return original.execute(toolCallId, arguments, sessionCwd);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return clientExec.run(arguments, sessionCwd);
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                return ToolResult.error("filesystem error: " + cause.getMessage());
            }
        });
    }

    // ---- everything else delegates to the original tool, so the LLM sees an identical tool ----

    @Override
    public String name() {
        return original.name();
    }

    @Override
    public String description() {
        return original.description();
    }

    @Override
    public Map<String, Object> schema() {
        return original.schema();
    }

    @Override
    public String glyph() {
        return original.glyph();
    }

    @Override
    public boolean hasResultView() {
        return original.hasResultView();
    }

    @Override
    public ToolResultView renderResult(Args args, ToolResult result, String cwd) {
        return original.renderResult(args, result, cwd);
    }

    @Override
    public ExecutionMode executionMode() {
        return original.executionMode();
    }

    @Override
    public boolean defaultEnabled() {
        return original.defaultEnabled();
    }

    @Override
    public void abort() {
        original.abort();
    }
}
