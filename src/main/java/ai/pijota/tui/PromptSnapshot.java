package ai.pijota.tui;

import java.util.List;

record PromptSnapshot(List<String> lines, int cursorRow, int cursorCol, List<CompletionItem> completions,
                                int selectedCompletion, String cwd, int contextPct, String status, String warning,
                                List<String> pendingMessages, List<String> extensionLines, PromptState promptState,
                                String suggestion, List<String> helpOverlay) {
    enum PromptState {
        IDLE, BUSY, ERROR, CONTINUATION
    }

    public PromptSnapshot(List<String> lines, int cursorRow, int cursorCol, List<CompletionItem> completions,
                                    int selectedCompletion) {
        this(lines, cursorRow, cursorCol, completions, selectedCompletion, null, 0, null, null, List.of(), List.of(),
                                        PromptState.IDLE, null, List.of());
    }

    public PromptSnapshot(List<String> lines, int cursorRow, int cursorCol, List<CompletionItem> completions) {
        this(lines, cursorRow, cursorCol, completions, -1, null, 0, null, null, List.of(), List.of(), PromptState.IDLE,
                                        null, List.of());
    }

    public PromptSnapshot withCwd(String cwd) {
        return copy(cwd, contextPct, status, warning, pendingMessages, extensionLines, promptState, suggestion,
                                        helpOverlay);
    }

    public PromptSnapshot withContextPct(int contextPct) {
        return copy(cwd, contextPct, status, warning, pendingMessages, extensionLines, promptState, suggestion,
                                        helpOverlay);
    }

    public PromptSnapshot withStatus(String status) {
        return copy(cwd, contextPct, status, warning, pendingMessages, extensionLines, promptState, suggestion,
                                        helpOverlay);
    }

    public PromptSnapshot withWarning(String warning) {
        return copy(cwd, contextPct, status, warning, pendingMessages, extensionLines, promptState, suggestion,
                                        helpOverlay);
    }

    public PromptSnapshot withPendingMessages(List<String> pendingMessages) {
        return copy(cwd, contextPct, status, warning, pendingMessages, extensionLines, promptState, suggestion,
                                        helpOverlay);
    }

    public PromptSnapshot withExtensionLines(List<String> extensionLines) {
        return copy(cwd, contextPct, status, warning, pendingMessages, extensionLines, promptState, suggestion,
                                        helpOverlay);
    }

    public PromptSnapshot withPromptState(PromptState promptState) {
        return copy(cwd, contextPct, status, warning, pendingMessages, extensionLines, promptState, suggestion,
                                        helpOverlay);
    }

    public PromptSnapshot withSuggestion(String suggestion) {
        return copy(cwd, contextPct, status, warning, pendingMessages, extensionLines, promptState, suggestion,
                                        helpOverlay);
    }

    public PromptSnapshot withHelpOverlay(List<String> helpOverlay) {
        return copy(cwd, contextPct, status, warning, pendingMessages, extensionLines, promptState, suggestion,
                                        helpOverlay);
    }

    private PromptSnapshot copy(String cwd, int contextPct, String status, String warning, List<String> pendingMessages,
                                    List<String> extensionLines, PromptState promptState, String suggestion,
                                    List<String> helpOverlay) {
        return new PromptSnapshot(lines, cursorRow, cursorCol, completions, selectedCompletion, cwd, contextPct, status,
                                        warning, pendingMessages, extensionLines, promptState, suggestion, helpOverlay);
    }
}
