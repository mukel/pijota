package ai.pijota.tui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class PromptEditor {
    public record Result(boolean changed, Optional<String> submitted, boolean exit) {
        static Result didChange() { return new Result(true, Optional.empty(), false); }
        static Result unchangedResult() { return new Result(false, Optional.empty(), false); }
        static Result submitted(String text) { return new Result(true, Optional.of(text), false); }
        static Result exitResult() { return new Result(false, Optional.empty(), true); }
    }

    private final SlashCommandRegistry commands;
    private final List<String> lines = new ArrayList<>();
    private int row;
    private int col;
    private List<CompletionItem> completions = List.of();
    private int selectedCompletion = -1;
    private final Selector<CompletionItem> selector = new Selector<>(List.of());
    private final List<String> history = new ArrayList<>();
    private int historyIndex = -1;
    private String preHistoryText = "";
    private String completionPrefix = "";

    public PromptEditor(SlashCommandRegistry commands) {
        this.commands = commands;
        lines.add("");
    }

    public synchronized Result apply(Key key) {
        return switch (key) {
            case Key.Char c -> insert(c.value());
            case Key.Paste p -> { insertText(p.text()); yield Result.didChange(); }
            case Key.Special s -> applySpecial(s);
        };
    }

    public synchronized PromptSnapshot snapshot() {
        return new PromptSnapshot(List.copyOf(lines), row, col, completions, selectedCompletion);
    }

    public synchronized String text() {
        return String.join("\n", lines);
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
            case HOME -> { col = 0; clearCompletions(); yield Result.didChange(); }
            case END -> { col = lines.get(row).length(); clearCompletions(); yield Result.didChange(); }
            case TAB -> complete();
            case CTRL_A -> { col = 0; clearCompletions(); yield Result.didChange(); }
            case CTRL_E -> { col = lines.get(row).length(); clearCompletions(); yield Result.didChange(); }
            case CTRL_K -> killToEnd();
            case CTRL_U -> { reset(); yield Result.didChange(); }
            case CTRL_W -> deleteWordBeforeCursor();
            case CTRL_LEFT -> wordLeft();
            case CTRL_RIGHT -> wordRight();
            case CTRL_C, CTRL_D -> Result.exitResult();
            case CTRL_G, CTRL_SHIFT_P -> Result.unchangedResult();
            default -> Result.unchangedResult();
        };
    }

    private Result insert(char ch) {
        String line = lines.get(row);
        lines.set(row, line.substring(0, col) + ch + line.substring(col));
        col++;
        refreshCompletions();
        return Result.didChange();
    }

    private Result newline() {
        String line = lines.get(row);
        lines.set(row, line.substring(0, col));
        lines.add(row + 1, line.substring(col));
        row++;
        col = 0;
        clearCompletions();
        return Result.didChange();
    }

    private Result submit() {
        String text;
        if (selector.total() > 0) {
            text = completionPrefix + selector.selectedItem().name();
        } else {
            text = String.join("\n", lines).stripTrailing();
        }
        reset();
        if (!text.isBlank()) {
            history.remove(text);
            history.add(text);
        }
        return text.isBlank() ? Result.didChange() : Result.submitted(text);
    }

    private Result backspace() {
        if (col > 0) {
            String line = lines.get(row);
            lines.set(row, line.substring(0, col - 1) + line.substring(col));
            col--;
            refreshCompletions();
            return Result.didChange();
        }
        if (row == 0) return Result.unchangedResult();
        return mergeWithPrevious();
    }

    private Result delete() {
        String line = lines.get(row);
        if (col < line.length()) {
            lines.set(row, line.substring(0, col) + line.substring(col + 1));
            refreshCompletions();
            return Result.didChange();
        }
        if (row >= lines.size() - 1) return Result.unchangedResult();
        lines.set(row, line + lines.remove(row + 1));
        clearCompletions();
        return Result.didChange();
    }

    private Result left() {
        if (col > 0) col--;
        else if (row > 0) { row--; col = lines.get(row).length(); }
        else return Result.unchangedResult();
        clearCompletions();
        return Result.didChange();
    }

    private Result right() {
        if (col < lines.get(row).length()) col++;
        else if (row < lines.size() - 1) { row++; col = 0; }
        else return Result.unchangedResult();
        clearCompletions();
        return Result.didChange();
    }

    private Result up() {
        if (row == 0) return Result.unchangedResult();
        row--;
        col = Math.min(col, lines.get(row).length());
        clearCompletions();
        return Result.didChange();
    }

    private Result down() {
        if (row >= lines.size() - 1) return Result.unchangedResult();
        row++;
        col = Math.min(col, lines.get(row).length());
        clearCompletions();
        return Result.didChange();
    }

    private Result complete() {
        if (row != 0 || col != lines.getFirst().length()) return Result.unchangedResult();
        String text = lines.getFirst();
        completionPrefix = "";

        if (text.startsWith("/")) {
            var matches = commands.completeWithDescriptions(text);
            if (!matches.isEmpty()) {
                if (matches.size() == 1) {
                    lines.set(0, matches.getFirst().name());
                    col = lines.getFirst().length();
                    selector.deactivate();
                    completions = List.of();
                    selectedCompletion = -1;
                    return Result.didChange();
                }
                selector.setItems(matches);
                selector.activate(text);
                completions = matches;
                selectedCompletion = selector.selectionIndex();
                return Result.didChange();
            }
        }

        var paths = fileCompletions(text);
        if (!paths.isEmpty()) {
            selector.setItems(paths);
            selector.activate(text);
            completions = paths;
            selectedCompletion = selector.selectionIndex();
            return Result.didChange();
        }

        return Result.unchangedResult();
    }

    private List<CompletionItem> fileCompletions(String text) {
        int lastSpace = text.lastIndexOf(' ');
        String prefix = lastSpace >= 0 ? text.substring(lastSpace + 1) : text;
        if (prefix.isBlank()) return List.of();
        completionPrefix = lastSpace >= 0 ? text.substring(0, lastSpace + 1) : "";
        try {
            Path dir;
            String filePrefix;
            int lastSep = Math.max(prefix.lastIndexOf('/'), prefix.lastIndexOf(File.separatorChar));
            if (lastSep >= 0) {
                String dirStr = prefix.substring(0, lastSep + 1);
                dir = Path.of(dirStr);
                if (!dir.isAbsolute()) dir = Path.of("").toAbsolutePath().resolve(dir);
                filePrefix = prefix.substring(lastSep + 1);
            } else {
                dir = Path.of("").toAbsolutePath();
                filePrefix = prefix;
            }
            if (!Files.isDirectory(dir)) return List.of();
            String fPrefix = filePrefix.toLowerCase();
            try (var stream = Files.list(dir)) {
                return stream
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return !name.startsWith(".") && name.toLowerCase().contains(fPrefix);
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                    .limit(15)
                    .map(p -> {
                        String name = p.getFileName().toString();
                        String display = lastSep >= 0
                            ? prefix.substring(0, lastSep + 1) + name
                            : name;
                        if (Files.isDirectory(p)) display += "/";
                        return new CompletionItem(display, Files.isDirectory(p) ? "dir" : "");
                    })
                    .toList();
            } catch (IOException e) {
                return List.of();
            }
        } catch (Exception e) {
            return List.of();
        }
    }

    private Result mergeWithPrevious() {
        String current = lines.remove(row);
        row--;
        col = lines.get(row).length();
        lines.set(row, lines.get(row) + current);
        clearCompletions();
        return Result.didChange();
    }

    private Result killToEnd() {
        String line = lines.get(row);
        if (col < line.length()) {
            lines.set(row, line.substring(0, col));
            clearCompletions();
            return Result.didChange();
        }
        if (row >= lines.size() - 1) return Result.unchangedResult();
        lines.subList(row + 1, lines.size()).clear();
        clearCompletions();
        return Result.didChange();
    }

    private Result deleteWordBeforeCursor() {
        if (col == 0) {
            if (row == 0) return Result.unchangedResult();
            return mergeWithPrevious();
        }

        String line = lines.get(row);
        int start = col;
        while (start > 0 && Character.isWhitespace(line.charAt(start - 1))) start--;
        while (start > 0 && !Character.isWhitespace(line.charAt(start - 1))) start--;
        lines.set(row, line.substring(0, start) + line.substring(col));
        col = start;
        refreshCompletions();
        return Result.didChange();
    }

    private Result wordLeft() {
        if (col == 0) {
            if (row == 0) return Result.unchangedResult();
            row--;
            col = lines.get(row).length();
        }
        String line = lines.get(row);
        while (col > 0 && Character.isWhitespace(line.charAt(col - 1))) col--;
        while (col > 0 && !Character.isWhitespace(line.charAt(col - 1))) col--;
        clearCompletions();
        return Result.didChange();
    }

    private Result wordRight() {
        String line = lines.get(row);
        if (col >= line.length()) {
            if (row >= lines.size() - 1) return Result.unchangedResult();
            row++;
            col = 0;
            line = lines.get(row);
        }
        while (col < line.length() && !Character.isWhitespace(line.charAt(col))) col++;
        while (col < line.length() && Character.isWhitespace(line.charAt(col))) col++;
        clearCompletions();
        return Result.didChange();
    }

    private void refreshCompletions() {
        if (row == 0 && col == lines.getFirst().length()) {
            String text = lines.getFirst();
            completionPrefix = "";
            if (text.startsWith("/")) {
                var matches = commands.completeWithDescriptions(text);
                if (!matches.isEmpty()) {
                    selector.setItems(matches);
                    selector.filter(text);
                    completions = matches;
                    selectedCompletion = selector.selectionIndex();
                    return;
                }
            }
            clearCompletions();
        } else {
            clearCompletions();
        }
    }

    void clearCompletions() {
        completions = List.of();
        selectedCompletion = -1;
        completionPrefix = "";
        selector.setItems(List.of());
        selector.deactivate();
    }

    public void insertText(String text) {
        if (text == null || text.isEmpty()) return;
        String[] parts = text.split("\\R", -1);
        if (parts.length == 1) {
            for (char ch : text.toCharArray()) insert(ch);
            return;
        }
        String line = lines.get(row);
        lines.set(row, line.substring(0, col) + parts[0]);
        col = parts[0].length();
        for (int i = 1; i < parts.length; i++) {
            newline();
            String current = lines.get(row);
            lines.set(row, current + parts[i]);
            col += parts[i].length();
        }
        refreshCompletions();
    }

    public boolean hasCompletions() {
        return selector.total() > 0;
    }

    public void selectNext() {
        selector.selectNext();
        selectedCompletion = selector.selectionIndex();
    }

    public void selectPrev() {
        selector.selectPrev();
        selectedCompletion = selector.selectionIndex();
    }

    public boolean isEmpty() {
        return lines.size() == 1 && lines.getFirst().isEmpty();
    }

    public boolean isInHistory() {
        return historyIndex >= 0;
    }

    public synchronized boolean historyPrev() {
        if (history.isEmpty()) return false;
        if (historyIndex < 0) {
            preHistoryText = String.join("\n", lines);
            historyIndex = history.size() - 1;
        } else if (historyIndex > 0) {
            historyIndex--;
        } else {
            return false;
        }
        setTextInternal(history.get(historyIndex));
        return true;
    }

    public synchronized void historyNext() {
        if (historyIndex < 0) return;
        historyIndex++;
        if (historyIndex >= history.size()) {
            historyIndex = -1;
            setTextInternal(preHistoryText);
        } else {
            setTextInternal(history.get(historyIndex));
        }
    }

    private void setTextInternal(String text) {
        lines.clear();
        if (text == null || text.isEmpty()) {
            lines.add("");
        } else {
            lines.addAll(text.lines().toList());
            if (text.endsWith("\n")) lines.add("");
        }
        row = lines.size() - 1;
        col = lines.get(row).length();
        clearCompletions();
    }

    private void reset() {
        lines.clear();
        lines.add("");
        row = 0;
        col = 0;
        clearCompletions();
    }
}
