package org.pijota.tui;

import java.util.List;

record PromptSnapshot(List<String> lines, int cursorRow, int cursorCol, List<CompletionItem> completions,
                                int selectedCompletion, String cwd, int contextPct, String status, String warning,
                                List<String> pendingMessages, List<String> extensionLines, PromptState promptState,
                                String suggestion, String streamTail) {
    /**
     * What posture the input box is in. Two readers derive from it, {@code caretGlyph} and {@code promptPrefixSlot}, so
     * a state added here has to be given to both: one is a switch the compiler checks, the other an if-chain it does
     * not. {@code AWAITING_ANSWER} deliberately covers every kind of dialog -- confirm, prompt, question -- since the
     * caret says the same "answer me" for all three and which keys to press is the affordance's job. Splitting it per
     * kind is what produced a caret and a colour that disagreed.
     */
    enum PromptState {
        IDLE, BUSY, ERROR, CONTINUATION, AWAITING_ANSWER
    }

    public PromptSnapshot(List<String> lines, int cursorRow, int cursorCol, List<CompletionItem> completions,
                                    int selectedCompletion) {
        this(lines, cursorRow, cursorCol, completions, selectedCompletion, null, 0, null, null, List.of(), List.of(),
                                        PromptState.IDLE, null, null);
    }

    public PromptSnapshot(List<String> lines, int cursorRow, int cursorCol, List<CompletionItem> completions) {
        this(lines, cursorRow, cursorCol, completions, -1, null, 0, null, null, List.of(), List.of(), PromptState.IDLE,
                                        null, null);
    }

    public PromptSnapshot withCwd(String cwd) {
        return copy(cwd, contextPct, status, warning, pendingMessages, extensionLines, promptState, suggestion,
                                        streamTail);
    }

    public PromptSnapshot withContextPct(int contextPct) {
        return copy(cwd, contextPct, status, warning, pendingMessages, extensionLines, promptState, suggestion,
                                        streamTail);
    }

    public PromptSnapshot withStatus(String status) {
        return copy(cwd, contextPct, status, warning, pendingMessages, extensionLines, promptState, suggestion,
                                        streamTail);
    }

    public PromptSnapshot withWarning(String warning) {
        return copy(cwd, contextPct, status, warning, pendingMessages, extensionLines, promptState, suggestion,
                                        streamTail);
    }

    public PromptSnapshot withPendingMessages(List<String> pendingMessages) {
        return copy(cwd, contextPct, status, warning, pendingMessages, extensionLines, promptState, suggestion,
                                        streamTail);
    }

    public PromptSnapshot withExtensionLines(List<String> extensionLines) {
        return copy(cwd, contextPct, status, warning, pendingMessages, extensionLines, promptState, suggestion,
                                        streamTail);
    }

    public PromptSnapshot withPromptState(PromptState promptState) {
        return copy(cwd, contextPct, status, warning, pendingMessages, extensionLines, promptState, suggestion,
                                        streamTail);
    }

    public PromptSnapshot withSuggestion(String suggestion) {
        return copy(cwd, contextPct, status, warning, pendingMessages, extensionLines, promptState, suggestion,
                                        streamTail);
    }

    /** The model's in-progress line, previewed live below the scrollback and above the prompt separator. */
    public PromptSnapshot withStreamTail(String streamTail) {
        return copy(cwd, contextPct, status, warning, pendingMessages, extensionLines, promptState, suggestion,
                                        streamTail);
    }

    private PromptSnapshot copy(String cwd, int contextPct, String status, String warning, List<String> pendingMessages,
                                    List<String> extensionLines, PromptState promptState, String suggestion,
                                    String streamTail) {
        return new PromptSnapshot(lines, cursorRow, cursorCol, completions, selectedCompletion, cwd, contextPct, status,
                                        warning, pendingMessages, extensionLines, promptState, suggestion, streamTail);
    }
}
