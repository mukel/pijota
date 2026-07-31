package org.pijota.extensions;

import org.pijota.llm.LlmClient;
import org.pijota.messages.Message;
import org.pijota.tools.Tool;
import org.pijota.tools.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Live session state and control surface for extensions. Obtain via {@link ExtensionAPI#runtime()} during {@code init};
 * the handle is stable for the session lifetime. Exposes the LLM client, tool registry, message snapshots,
 * filesystem, status lines, extension flags, process execution, and TUI suspend/resume.
 */
public interface ExtensionRuntime {
    /** Direct model calls, bypassing the agent loop. Supports summarization, side queries, etc. */
    LlmClient llm();

    Path cwd();

    /** The active session's on-disk transcript path (for relaunching a child on the same session), or null. */
    default Path sessionFile() {
        return null;
    }

    /** The project root (a configured project dir, else {@link #cwd()}). */
    default Path projectDir() {
        return cwd();
    }

    /** Whether {@link #projectDir()} is inside a git repository. */
    default boolean isGitRepo() {
        return projectDir() != null && Files.isDirectory(projectDir().resolve(".git"));
    }

    String systemPrompt();

    /** Snapshot of the current conversation. Mutate session state through the explicit runtime/API methods. */
    List<Message> messages();

    /** The live tool registry (register/deregister/enable/disable). */
    ToolRegistry tools();

    /** Tools currently presented to the model. */
    List<Tool.ToolInfo> activeTools();

    /** Replace the active tool set for subsequent turns. Policy-denied tools stay unavailable. */
    void setActiveTools(List<String> toolNames);

    /** Tools allowed by policy, whether or not they are currently active. */
    List<Tool.ToolInfo> availableTools();

    /** Human-readable session name (shown in {@code /sessions} listings). */
    String sessionName();

    void setSessionName(String name);

    boolean isIdle();

    boolean isClosed();

    /** Abort the current turn, keep session alive. */
    void abort();

    /** Trigger immediate compaction (shrinks context window). */
    void compact(String instructions);

    /** Set the main status bar text (overwritten by the next set). */
    void setStatus(String message);

    /** Repaint live UI chrome (status lines, footer) after an async state change that produced no output. */
    default void refresh() {}

    /** Set a named status entry, appears alongside keyless status. */
    void setStatus(String key, String message);

    /** Remove a named status entry. */
    void clearStatus(String key);

    /** Check a boolean flag passed via CLI or config, with a default. */
    boolean getFlag(String name, boolean defaultValue);

    /** Read a flag's raw string value. */
    Optional<String> getFlag(String name);

    /** Whether a raw flag value is truthy ({@code true}/{@code 1}/{@code yes}, case-insensitive). */
    static boolean truthy(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    /** Run a command in the session's cwd; returns exit code, stdout, stderr. */
    ExecResult exec(List<String> command);

    /** The UI context, null in headless/non-interactive modes. Terminal handover/output live here ({@code ui()}). */
    UiContext ui();

    /** Output of a command run via {@link #exec}. */
    record ExecResult(int exitCode, String stdout, String stderr) {
        public boolean success() {
            return exitCode == 0;
        }
    }
}
