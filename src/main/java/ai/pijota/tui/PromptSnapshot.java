package ai.pijota.tui;

import java.util.List;

record PromptSnapshot(
    List<String> lines,
    int cursorRow,
    int cursorCol,
    List<CompletionItem> completions,
    int selectedCompletion,
    String cwd,
    int contextPct,
    String status,
    String warning,
    List<String> pendingMessages,
    List<String> extensionLines
) {
    public PromptSnapshot(List<String> lines, int cursorRow, int cursorCol,
                          List<CompletionItem> completions, int selectedCompletion) {
        this(lines, cursorRow, cursorCol, completions, selectedCompletion, null, 0, null, null, List.of(), List.of());
    }

    public PromptSnapshot(List<String> lines, int cursorRow, int cursorCol, List<CompletionItem> completions) {
        this(lines, cursorRow, cursorCol, completions, -1, null, 0, null, null, List.of(), List.of());
    }

    public PromptSnapshot withCwd(String cwd) {
        return new PromptSnapshot(lines, cursorRow, cursorCol, completions, selectedCompletion, cwd, contextPct, status, warning, pendingMessages, extensionLines);
    }

    public PromptSnapshot withContextPct(int contextPct) {
        return new PromptSnapshot(lines, cursorRow, cursorCol, completions, selectedCompletion, cwd, contextPct, status, warning, pendingMessages, extensionLines);
    }

    public PromptSnapshot withStatus(String status) {
        return new PromptSnapshot(lines, cursorRow, cursorCol, completions, selectedCompletion, cwd, contextPct, status, warning, pendingMessages, extensionLines);
    }

    public PromptSnapshot withWarning(String warning) {
        return new PromptSnapshot(lines, cursorRow, cursorCol, completions, selectedCompletion, cwd, contextPct, status, warning, pendingMessages, extensionLines);
    }

    public PromptSnapshot withPendingMessages(List<String> pendingMessages) {
        return new PromptSnapshot(lines, cursorRow, cursorCol, completions, selectedCompletion, cwd, contextPct, status, warning, pendingMessages, extensionLines);
    }

    public PromptSnapshot withExtensionLines(List<String> extensionLines) {
        return new PromptSnapshot(lines, cursorRow, cursorCol, completions, selectedCompletion, cwd, contextPct, status, warning, pendingMessages, extensionLines);
    }
}
