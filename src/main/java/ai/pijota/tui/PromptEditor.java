package ai.pijota.tui;

import java.util.Optional;

public final class PromptEditor {
    public record Result(boolean changed, Optional<String> submitted, boolean exit) {
        static Result didChange() {
            return new Result(true, Optional.empty(), false);
        }

        static Result unchangedResult() {
            return new Result(false, Optional.empty(), false);
        }

        static Result submitted(String text) {
            return new Result(true, Optional.of(text), false);
        }

        static Result exitResult() {
            return new Result(false, Optional.empty(), true);
        }
    }

    private final PromptCompletionPolicy completionPolicy;
    private final PromptTextBuffer buffer = new PromptTextBuffer();
    private final PromptCompletionState completions = new PromptCompletionState();
    private final PromptHistory history = new PromptHistory();
    private String suggestion = "";

    public PromptEditor(SlashCommandRegistry commands) {
        this(commands, PromptFileCompletions::complete);
    }

    PromptEditor(SlashCommandRegistry commands, PromptFileCompletionProvider fileCompletionProvider) {
        this.completionPolicy = new PromptCompletionPolicy(commands, fileCompletionProvider);
    }

    public synchronized Result apply(Key key) {
        return switch (key) {
            case Key.Char c -> insert(c.value());
            case Key.Paste p -> {
                insertText(p.text());
                yield Result.didChange();
            }
            case Key.Special s -> applySpecial(s);
        };
    }

    public synchronized PromptSnapshot snapshot() {
        return new PromptSnapshot(buffer.lines(), buffer.row(), buffer.col(), completions.visibleItems(),
                                        completions.selectedIndex()).withSuggestion(suggestion);
    }

    public synchronized String text() {
        return buffer.text();
    }

    public synchronized String suggestion() {
        return suggestion;
    }

    public synchronized boolean reverseHistoryActive() {
        return completions.active() && history.isReverseSearchItemCount(completions.itemCount());
    }

    public synchronized void setText(String text) {
        setTextInternal(text);
    }

    private Result applySpecial(Key.Special key) {
        return switch (key) {
            case ENTER -> submit();
            case NEWLINE -> newline();
            case BACKSPACE -> backspace();
            case DELETE -> delete();
            case LEFT -> left();
            case RIGHT -> right();
            case UP -> up();
            case DOWN -> down();
            case HOME -> applyEffect(buffer.home());
            case END -> applyEffect(buffer.end());
            case TAB -> complete();
            case CTRL_A -> applyEffect(buffer.home());
            case CTRL_E -> applyEffect(buffer.end());
            case CTRL_K -> killToEnd();
            case CTRL_U -> {
                reset();
                yield Result.didChange();
            }
            case CTRL_R -> reverseHistorySearch();
            case CTRL_W -> deleteWordBeforeCursor();
            case ALT_D -> deleteWordAfterCursor();
            case CTRL_LEFT -> wordLeft();
            case CTRL_RIGHT -> wordRight();
            case CTRL_C, CTRL_D -> Result.exitResult();
            case CTRL_G, CTRL_SHIFT_P -> Result.unchangedResult();
            default -> Result.unchangedResult();
        };
    }

    private Result insert(char ch) {
        return applyEffect(buffer.insert(ch));
    }

    private Result newline() {
        return applyEffect(buffer.newline());
    }

    private Result submit() {
        String text;
        if (completions.total() > 0)
            return submitOrAcceptCompletion();
        text = buffer.text().stripTrailing();
        reset();
        history.add(text);
        return text.isBlank() ? Result.didChange() : Result.submitted(text);
    }

    private Result backspace() {
        return applyEffect(buffer.backspace());
    }

    private Result delete() {
        return applyEffect(buffer.delete());
    }

    private Result left() {
        return applyEffect(buffer.left());
    }

    private Result right() {
        return applyEffect(buffer.right());
    }

    private Result up() {
        return applyEffect(buffer.up());
    }

    private Result down() {
        return applyEffect(buffer.down());
    }

    private Result applyEffect(PromptTextBuffer.Effect effect) {
        return switch (effect) {
            case UNCHANGED -> Result.unchangedResult();
            case REFRESH_COMPLETIONS -> {
                refreshCompletions();
                yield Result.didChange();
            }
            case CLEAR_COMPLETIONS -> {
                clearCompletions();
                yield Result.didChange();
            }
        };
    }

    private Result complete() {
        if (reverseHistoryActive())
            return Result.unchangedResult();
        if (!buffer.isAtEndOfFirstLine())
            return Result.unchangedResult();
        String text = buffer.firstLine();
        completions.prefix("");

        var decision = completionPolicy.complete(text);
        return switch (decision.kind()) {
            case NONE -> Result.unchangedResult();
            case REPLACE -> {
                buffer.replaceSingleLine(decision.replacement());
                completions.clear();
                if (decision.refreshAfterReplace())
                    refreshCompletions();
                else
                    suggestion = "";
                yield Result.didChange();
            }
            case SHOW -> {
                completions.show(decision.items(), text, decision.prefix());
                yield Result.didChange();
            }
        };
    }

    private Result killToEnd() {
        return applyEffect(buffer.killToEnd());
    }

    private Result deleteWordBeforeCursor() {
        return applyEffect(buffer.deleteWordBeforeCursor());
    }

    private Result deleteWordAfterCursor() {
        return applyEffect(buffer.deleteWordAfterCursor());
    }

    private Result wordLeft() {
        return applyEffect(buffer.wordLeft());
    }

    private Result wordRight() {
        return applyEffect(buffer.wordRight());
    }

    private Result submitOrAcceptCompletion() {
        String accepted = completions.prefix() + completions.selectedItem().name();
        if (isSlashCommandCompletion()) {
            reset();
            history.add(accepted);
            return Result.submitted(accepted);
        }
        buffer.replaceSingleLine(accepted);
        clearCompletions();
        return Result.didChange();
    }

    private boolean isSlashCommandCompletion() {
        return buffer.isSingleLineStartingWith("/");
    }

    private void refreshCompletions() {
        if (reverseHistoryActive()) {
            suggestion = computeSuggestion();
            return;
        }
        if (buffer.isAtEndOfFirstLine()) {
            String text = buffer.firstLine();
            completions.prefix("");
            var matches = completionPolicy.refreshCommandCompletions(text);
            if (!matches.isEmpty()) {
                completions.filter(matches, text);
                suggestion = computeSuggestion();
                return;
            }
            clearCompletions();
        } else {
            clearCompletions();
        }
        suggestion = computeSuggestion();
    }

    private String computeSuggestion() {
        if (!buffer.cursorAtEndOfBuffer())
            return "";
        String text = text();
        String commandSuggestion = completionPolicy.commandSuggestion(text);
        if (!commandSuggestion.isEmpty())
            return commandSuggestion;
        return history.suggestionFor(text);
    }

    private Result reverseHistorySearch() {
        var items = history.reverseSearchItems();
        if (items.isEmpty())
            return Result.unchangedResult();
        completions.show(items, "", "");
        return Result.didChange();
    }

    void clearCompletions() {
        completions.clear();
        suggestion = "";
    }

    public void insertText(String text) {
        applyEffect(buffer.insertText(text));
    }

    public boolean hasCompletions() {
        return completions.total() > 0;
    }

    /** Handles Up/Down within an open completion list. Returns true if the key was consumed. */
    public boolean handleCompletionKey(Key key) {
        if (!hasCompletions())
            return false;
        if (key == Key.Special.UP) {
            selectPrev();
            return true;
        }
        if (key == Key.Special.DOWN) {
            selectNext();
            return true;
        }
        return false;
    }

    public void selectNext() {
        completions.selectNext();
    }

    public void selectPrev() {
        completions.selectPrev();
    }

    public boolean isEmpty() {
        return buffer.isEmpty();
    }

    public boolean isInHistory() {
        return history.isNavigating();
    }

    public synchronized boolean historyPrev() {
        return history.previous(buffer.text()).map(text -> {
            setTextInternal(text);
            return true;
        }).orElse(false);
    }

    public synchronized void historyNext() {
        history.next().ifPresent(this::setTextInternal);
    }

    private void setTextInternal(String text) {
        buffer.setText(text);
        clearCompletions();
    }

    private void reset() {
        buffer.reset();
        clearCompletions();
    }
}
