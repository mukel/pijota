package org.pijota.tui;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

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
    private java.util.function.BooleanSupplier completionsAllowed = () -> true;
    private final PromptHistory history = new PromptHistory();
    private final UndoStack undoStack = new UndoStack();
    private final KillRing killRing = new KillRing();
    private String suggestion = "";
    // A suggestion an extension offered for the *next* message, shown only on an empty prompt. Distinct from the
    // command ghost above, which completes what is already typed: the two can never collide, since one needs an
    // empty buffer and the other needs a prefix.
    private String offered = "";
    private boolean reverseSearch; // true while a Ctrl+R reverse-i-search is open; the buffer is the live filter query
    private UndoStack.Snapshot preYank; // buffer state captured before the current yank, for yank-pop replacement
    private boolean yankActive; // true right after a yank/yank-pop, enabling yank-pop to cycle the ring

    public PromptEditor(SlashCommandRegistry commands) {
        this(commands, PromptFileCompletions::complete);
    }

    PromptEditor(SlashCommandRegistry commands, PromptFileCompletionProvider fileCompletionProvider) {
        this.completionPolicy = new PromptCompletionPolicy(commands, fileCompletionProvider);
    }

    /**
     * When the completions popup may open. The editor cannot see the rest of the interface, and it must not: the TUI
     * knows when something else already owns the screen and says so here.
     *
     * <p>
     * A selector filters on the prompt text, so typing reaches the editor while one is open -- and the editor happily
     * opened a second overlay on top. Both then drew their own "Enter select" footer, the status line described a third
     * behaviour, and Enter did nothing at all, leaving Esc as the only way out.
     */
    synchronized void setCompletionsAllowed(java.util.function.BooleanSupplier allowed) {
        this.completionsAllowed = allowed == null ? () -> true : allowed;
    }

    public synchronized Result apply(Key key) {
        boolean yankKey = key == Key.Special.CTRL_Y || key == Key.Special.ALT_Y;
        Result result = switch (key) {
            case Key.Char c -> insert(c.value());
            case Key.Paste p -> {
                insertText(p.text());
                yield Result.didChange();
            }
            case Key.Special s -> applySpecial(s);
        };
        if (!yankKey)
            yankActive = false; // any non-yank key ends a yank-pop cycle
        return result;
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
        return reverseSearch && completions.active();
    }

    public synchronized void setText(String text) {
        setTextInternal(text);
    }

    private Result applySpecial(Key.Special key) {
        return switch (key) {
            case ENTER -> submit();
            case NEWLINE, ALT_ENTER -> newline();
            case BACKSPACE -> backspace();
            case DELETE -> delete();
            case LEFT -> left();
            case RIGHT -> right();
            case UP -> up();
            case DOWN -> down();
            case HOME, CTRL_A -> home();
            case END, CTRL_E -> end();
            case TAB -> acceptOfferOrComplete();
            case CTRL_K -> killToEnd();
            case CTRL_U -> killWholeBuffer();
            case CTRL_R -> reverseHistorySearch();
            case CTRL_W -> deleteWordBeforeCursor();
            case ALT_D -> deleteWordAfterCursor();
            case CTRL_Y -> yank();
            case ALT_Y -> yankPop();
            case CTRL_UNDERSCORE -> undoOp();
            case ALT_UNDERSCORE -> redoOp();
            case CTRL_LEFT -> wordLeft();
            case CTRL_RIGHT -> wordRight();
            case CTRL_C, CTRL_D -> Result.exitResult();
            case CTRL_G, CTRL_SHIFT_P -> Result.unchangedResult();
            default -> Result.unchangedResult();
        };
    }

    private Result insert(char ch) {
        return mutating(UndoStack.Kind.TYPING, () -> buffer.insert(ch));
    }

    private Result newline() {
        return mutating(UndoStack.Kind.OTHER, buffer::newline);
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
        return mutating(UndoStack.Kind.DELETING, buffer::backspace);
    }

    private Result delete() {
        return mutating(UndoStack.Kind.DELETING, buffer::delete);
    }

    private Result left() {
        undoStack.breakRun();
        return applyEffect(buffer.left());
    }

    private Result right() {
        undoStack.breakRun();
        if (canAcceptSuggestion())
            return acceptSuggestion();
        return applyEffect(buffer.right());
    }

    private Result up() {
        undoStack.breakRun();
        return applyEffect(buffer.up());
    }

    private Result down() {
        undoStack.breakRun();
        return applyEffect(buffer.down());
    }

    private Result home() {
        undoStack.breakRun();
        return applyEffect(buffer.home());
    }

    private Result end() {
        undoStack.breakRun();
        if (canAcceptSuggestion())
            return acceptSuggestion();
        return applyEffect(buffer.end());
    }

    // The inline ghost suggestion is accepted by →/End at the very end of the buffer (fish/zsh-autosuggestions feel).
    // A suggestion only exists when the cursor is already at the end, so →/End have no cursor movement left to do here
    // and accepting is the sensible action, including for a command ghost ("/mod" → "/model") while its menu is open,
    // since →/End don't drive the dropdown (Up/Down/Enter/Tab do).
    private boolean canAcceptSuggestion() {
        return !suggestion.isEmpty() && buffer.cursorAtEndOfBuffer();
    }

    private Result acceptSuggestion() {
        String suffix = suggestion;
        return mutating(UndoStack.Kind.OTHER, () -> buffer.insertText(suffix));
    }

    /**
     * Runs a text-mutating buffer op, recording its pre-state for undo (coalesced per {@code kind}) when it changes.
     */
    private Result mutating(UndoStack.Kind kind, Supplier<PromptTextBuffer.Effect> op) {
        // Capture the pre-edit snapshot only when this edit starts a new undo step; a coalesced keystroke (the common
        // case while typing) would discard it, so we avoid the O(n) buffer copy entirely.
        var before = undoStack.beginsNewStep(kind) ? state() : null;
        // Once the user edits, the offer is theirs to ignore: it must not reappear if they type and then delete back
        // to empty, which would read as the suggestion arguing with them.
        offered = "";
        PromptTextBuffer.Effect effect = op.get();
        if (effect != PromptTextBuffer.Effect.UNCHANGED)
            undoStack.record(before, kind);
        return applyEffect(effect);
    }

    /** Like {@link #mutating} but also pushes the removed text onto the kill ring (each kill is its own undo step). */
    private Result killing(Supplier<PromptTextBuffer.Effect> op) {
        var before = state();
        PromptTextBuffer.Effect effect = op.get();
        if (effect != PromptTextBuffer.Effect.UNCHANGED) {
            undoStack.record(before, UndoStack.Kind.OTHER);
            killRing.add(buffer.takeLastKill());
        }
        return applyEffect(effect);
    }

    private Result killWholeBuffer() {
        if (buffer.isEmpty())
            return Result.unchangedResult();
        undoStack.record(state(), UndoStack.Kind.OTHER);
        killRing.add(buffer.text());
        buffer.reset();
        clearCompletions();
        return Result.didChange();
    }

    private Result undoOp() {
        return undoStack.undo(state()).map(snapshot -> {
            restore(snapshot);
            return Result.didChange();
        }).orElseGet(Result::unchangedResult);
    }

    private Result redoOp() {
        return undoStack.redo(state()).map(snapshot -> {
            restore(snapshot);
            return Result.didChange();
        }).orElseGet(Result::unchangedResult);
    }

    private Result yank() {
        if (killRing.isEmpty())
            return Result.unchangedResult();
        preYank = state();
        undoStack.record(preYank, UndoStack.Kind.OTHER);
        buffer.insertText(killRing.yank());
        clearCompletions();
        yankActive = true;
        return Result.didChange();
    }

    private Result yankPop() {
        if (!yankActive)
            return yank(); // first press with no active yank behaves as a plain yank
        String previous = killRing.yankPop();
        if (previous == null)
            return Result.unchangedResult();
        buffer.restore(preYank.text(), preYank.row(), preYank.col());
        buffer.insertText(previous);
        clearCompletions();
        yankActive = true;
        return Result.didChange();
    }

    private UndoStack.Snapshot state() {
        return new UndoStack.Snapshot(buffer.text(), buffer.row(), buffer.col());
    }

    private void restore(UndoStack.Snapshot snapshot) {
        buffer.restore(snapshot.text(), snapshot.row(), snapshot.col());
        clearCompletions();
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
                undoStack.record(state(), UndoStack.Kind.OTHER);
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
        return killing(buffer::killToEnd);
    }

    private Result deleteWordBeforeCursor() {
        return killing(buffer::deleteWordBeforeCursor);
    }

    private Result deleteWordAfterCursor() {
        return killing(buffer::deleteWordAfterCursor);
    }

    private Result wordLeft() {
        undoStack.breakRun();
        return applyEffect(buffer.wordLeft());
    }

    private Result wordRight() {
        undoStack.breakRun();
        return applyEffect(buffer.wordRight());
    }

    private Result submitOrAcceptCompletion() {
        if (isSlashCommandCompletion()) {
            // Submit the highlighted command's *token* only. Its name carries an arg-spec for display
            // (e.g. "/kill <id> [SIGTERM]"); submitting that verbatim would pass "<id>"/"[SIGTERM]" as real args.
            // Preserve any arguments the user already typed after their own command token ("/name foo" → "/name foo").
            String command = PromptCompletionPolicy.commandToken(completions.selectedItem().name());
            String[] typed = buffer.firstLine().split("\\s+", 2);
            if (typed.length > 1 && !typed[1].isBlank())
                command += " " + typed[1].strip();
            reset();
            history.add(command);
            return Result.submitted(command);
        }
        String accepted = completions.prefix() + completions.selectedItem().name();
        undoStack.record(state(), UndoStack.Kind.OTHER);
        buffer.replaceSingleLine(accepted);
        clearCompletions();
        return Result.didChange();
    }

    private boolean isSlashCommandCompletion() {
        return buffer.isSingleLineStartingWith("/");
    }

    private void refreshCompletions() {
        if (!completionsAllowed.getAsBoolean()) {
            completions.clear();
            suggestion = "";
            return;
        }
        if (reverseHistoryActive()) {
            // Reverse-i-search: the buffer text is the live query, narrowing history to matches as the user types
            // (readline feel), instead of a static list. Pre-filter (the dropdown renders the passed list directly);
            // an empty query keeps all history.
            String q = buffer.firstLine().toLowerCase();
            var matches = history.reverseSearchItems().stream().filter(
                                            it -> SlashCommandRegistry.fuzzyMatch(q, it.searchText().toLowerCase()))
                                            .toList();
            completions.filter(matches, "");
            suggestion = "";
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

    /**
     * Only commands are ghosted, never a previous message. Shell autosuggestion works because commands repeat verbatim
     * -- rerunning {@code git status} is the common case -- but an agent prompt is a fresh instruction in prose almost
     * every time, so a prefix match on an earlier message is coincidence rather than intent. It was also loudest when
     * it knew least: one keystroke could fill the line with a whole stale sentence, which → and End then accepted, and
     * both are keys you press while editing. History is still reachable deliberately, with Up/Down and Ctrl+R.
     */
    /**
     * Tab, not Enter. Enter means send, and a reflex Enter must never put a machine's words in the user's mouth; Tab on
     * an empty prompt has nothing to complete today, so nothing existing changes. Accepting only fills the buffer --
     * the user still presses Enter themselves, on text they can see and edit.
     */
    private Result acceptOfferOrComplete() {
        if (!offered.isEmpty() && text().isEmpty()) {
            String text = offered;
            offered = "";
            return mutating(UndoStack.Kind.OTHER, () -> buffer.insertText(text));
        }
        return complete();
    }

    /** An extension offers a next message. Ignored unless the prompt is still empty; any keystroke discards it. */
    public synchronized void offer(String text) {
        offered = text == null || !text().isEmpty() ? "" : text.strip();
        suggestion = computeSuggestion();
    }

    public synchronized void clearOffer() {
        offered = "";
        suggestion = computeSuggestion();
    }

    private String computeSuggestion() {
        if (!buffer.cursorAtEndOfBuffer())
            return "";
        if (text().isEmpty())
            return offered;
        return completionPolicy.commandSuggestion(text());
    }

    private Result reverseHistorySearch() {
        var items = history.reverseSearchItems();
        if (items.isEmpty())
            return Result.unchangedResult();
        reverseSearch = true;
        completions.show(items, "", "");
        return Result.didChange();
    }

    void clearCompletions() {
        completions.clear();
        suggestion = "";
        reverseSearch = false;
    }

    public synchronized void insertText(String text) {
        var before = state();
        PromptTextBuffer.Effect effect = buffer.insertText(text);
        if (effect != PromptTextBuffer.Effect.UNCHANGED)
            undoStack.record(before, UndoStack.Kind.OTHER);
        applyEffect(effect);
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
        if (key == Key.Special.PG_UP) {
            completions.selectPageUp();
            return true;
        }
        if (key == Key.Special.PG_DOWN) {
            completions.selectPageDown();
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

    /** Seed up/down input history (e.g. from a loaded session's user messages), replacing any prior entries. */
    public synchronized void seedHistory(List<String> entries) {
        history.replaceAll(entries);
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
        undoStack.clear(); // history navigation / programmatic set is a context switch, not an undoable edit
    }

    private void reset() {
        buffer.reset();
        clearCompletions();
        undoStack.clear();
    }
}
