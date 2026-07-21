package ai.pijota.acp;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.extensions.ExtensionResult;
import ai.pijota.util.Util;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Gates mutating tool calls behind an ACP {@code session/request_permission} round-trip, so the client (e.g. Zed) shows
 * an approve/deny prompt before pijota writes files or runs shell commands. Registered only in ACP mode; the actual
 * request is issued by {@link AcpFrontend} (it owns the JSON-RPC peer), which this extension calls through the
 * {@link Requester} handle. Read-only tools (read/ls/grep/glob) are never gated.
 */
public final class AcpPermissionExtension implements Extension {

    /** Asks the client to approve a tool call; resolves true to allow, false to block. */
    @FunctionalInterface
    public interface Requester {
        CompletableFuture<Boolean> request(String toolName, String toolCallId, String argsJson);
    }

    // Shell-family tools (bash/tmux_bash/jobs_run) from the canonical set, plus the file-mutating tools.
    private static final Set<String> GATED = gatedTools();

    private static Set<String> gatedTools() {
        var gated = new HashSet<>(Util.SHELL_TOOL_NAMES);
        gated.add("write");
        gated.add("edit");
        gated.add("apply_patch");
        return Set.copyOf(gated);
    }

    private final Requester requester;

    public AcpPermissionExtension(Requester requester) {
        this.requester = requester;
    }

    @Override
    public String name() {
        return "acp-permission";
    }

    @Override
    public String description() {
        return "Requests tool approval from the ACP client before mutating tools run";
    }

    @Override
    public void init(ExtensionAPI api) {
        api.onToolCall(tc -> gate(tc.toolName(), tc.toolCallId(), tc.arguments()));
    }

    /** True if a tool needs client approval before running (mutating shell/file tools). */
    static boolean isGated(String toolName) {
        return GATED.contains(toolName);
    }

    /**
     * The gating decision for one tool call: {@code null} to allow, a {@link ExtensionResult.Block} to reject.
     * Read-only tools pass without asking; mutating tools block only if the client rejects the round-trip. A broken
     * round-trip fails open (allow) so it can't wedge the agent.
     */
    ExtensionResult.Block gate(String toolName, String toolCallId, String argsJson) {
        if (!isGated(toolName))
            return null;
        try {
            boolean allowed = requester.request(toolName, toolCallId, argsJson).join();
            return allowed ? null : new ExtensionResult.Block("Rejected by user");
        } catch (Exception e) {
            return null; // fail-open
        }
    }
}
