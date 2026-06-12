package ai.pijota.tui;

import java.util.ArrayList;
import java.util.List;

final class PromptTextBuffer {
    enum Effect {
        UNCHANGED, REFRESH_COMPLETIONS, CLEAR_COMPLETIONS
    }

    private final List<String> lines = new ArrayList<>();
    private int row;
    private int col;
    private int desiredCol = -1;

    PromptTextBuffer() {
        lines.add("");
    }

    List<String> lines() {
        return List.copyOf(lines);
    }

    int row() {
        return row;
    }

    int col() {
        return col;
    }

    String text() {
        return String.join("\n", lines);
    }

    boolean isEmpty() {
        return lines.size() == 1 && lines.getFirst().isEmpty();
    }

    boolean isAtEndOfFirstLine() {
        return row == 0 && col == lines.getFirst().length();
    }

    boolean isSingleLineStartingWith(String prefix) {
        return row == 0 && lines.size() == 1 && lines.getFirst().startsWith(prefix);
    }

    String firstLine() {
        return lines.getFirst();
    }

    boolean cursorAtEndOfBuffer() {
        return row == lines.size() - 1 && col == lines.getLast().length();
    }

    Effect insert(char ch) {
        String line = lines.get(row);
        lines.set(row, line.substring(0, col) + ch + line.substring(col));
        col++;
        desiredCol = -1;
        return Effect.REFRESH_COMPLETIONS;
    }

    Effect insertText(String text) {
        if (text == null || text.isEmpty())
            return Effect.UNCHANGED;
        String[] parts = text.split("\\R", -1);
        if (parts.length == 1) {
            for (char ch : text.toCharArray())
                insert(ch);
            return Effect.REFRESH_COMPLETIONS;
        }
        String line = lines.get(row);
        String suffix = line.substring(col);
        lines.set(row, line.substring(0, col) + parts[0]);
        col += parts[0].length();
        for (int i = 1; i < parts.length; i++) {
            newline();
            String current = lines.get(row);
            lines.set(row, current + parts[i]);
            col += parts[i].length();
        }
        lines.set(row, lines.get(row) + suffix);
        desiredCol = -1;
        return Effect.REFRESH_COMPLETIONS;
    }

    Effect newline() {
        String line = lines.get(row);
        lines.set(row, line.substring(0, col));
        lines.add(row + 1, line.substring(col));
        row++;
        col = 0;
        desiredCol = -1;
        return Effect.CLEAR_COMPLETIONS;
    }

    Effect backspace() {
        if (col > 0) {
            String line = lines.get(row);
            lines.set(row, line.substring(0, col - 1) + line.substring(col));
            col--;
            desiredCol = -1;
            return Effect.REFRESH_COMPLETIONS;
        }
        if (row == 0)
            return Effect.UNCHANGED;
        mergeWithPrevious();
        return Effect.CLEAR_COMPLETIONS;
    }

    Effect delete() {
        String line = lines.get(row);
        if (col < line.length()) {
            lines.set(row, line.substring(0, col) + line.substring(col + 1));
            desiredCol = -1;
            return Effect.REFRESH_COMPLETIONS;
        }
        if (row >= lines.size() - 1)
            return Effect.UNCHANGED;
        lines.set(row, line + lines.remove(row + 1));
        return Effect.CLEAR_COMPLETIONS;
    }

    Effect left() {
        if (col > 0)
            col--;
        else if (row > 0) {
            row--;
            col = lines.get(row).length();
        } else
            return Effect.UNCHANGED;
        desiredCol = -1;
        return Effect.CLEAR_COMPLETIONS;
    }

    Effect right() {
        if (col < lines.get(row).length())
            col++;
        else if (row < lines.size() - 1) {
            row++;
            col = 0;
        } else
            return Effect.UNCHANGED;
        desiredCol = -1;
        return Effect.CLEAR_COMPLETIONS;
    }

    Effect up() {
        if (row == 0)
            return Effect.UNCHANGED;
        if (desiredCol < 0)
            desiredCol = col;
        row--;
        col = Math.min(desiredCol, lines.get(row).length());
        return Effect.CLEAR_COMPLETIONS;
    }

    Effect down() {
        if (row >= lines.size() - 1)
            return Effect.UNCHANGED;
        if (desiredCol < 0)
            desiredCol = col;
        row++;
        col = Math.min(desiredCol, lines.get(row).length());
        return Effect.CLEAR_COMPLETIONS;
    }

    Effect home() {
        col = 0;
        desiredCol = -1;
        return Effect.CLEAR_COMPLETIONS;
    }

    Effect end() {
        col = lines.get(row).length();
        desiredCol = -1;
        return Effect.CLEAR_COMPLETIONS;
    }

    Effect killToEnd() {
        String line = lines.get(row);
        if (col < line.length()) {
            lines.set(row, line.substring(0, col));
            desiredCol = -1;
            return Effect.CLEAR_COMPLETIONS;
        }
        if (row >= lines.size() - 1)
            return Effect.UNCHANGED;
        lines.subList(row + 1, lines.size()).clear();
        desiredCol = -1;
        return Effect.CLEAR_COMPLETIONS;
    }

    Effect deleteWordBeforeCursor() {
        if (col == 0) {
            if (row == 0)
                return Effect.UNCHANGED;
            mergeWithPrevious();
            return Effect.CLEAR_COMPLETIONS;
        }

        String line = lines.get(row);
        int start = col;
        while (start > 0 && Character.isWhitespace(line.charAt(start - 1)))
            start--;
        while (start > 0 && !Character.isWhitespace(line.charAt(start - 1)))
            start--;
        lines.set(row, line.substring(0, start) + line.substring(col));
        col = start;
        desiredCol = -1;
        return Effect.REFRESH_COMPLETIONS;
    }

    Effect deleteWordAfterCursor() {
        String line = lines.get(row);
        if (col >= line.length()) {
            if (row >= lines.size() - 1)
                return Effect.UNCHANGED;
            lines.set(row, line + lines.remove(row + 1));
            desiredCol = -1;
            return Effect.CLEAR_COMPLETIONS;
        }

        int end = col;
        while (end < line.length() && Character.isWhitespace(line.charAt(end)))
            end++;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end)))
            end++;
        lines.set(row, line.substring(0, col) + line.substring(end));
        desiredCol = -1;
        return Effect.REFRESH_COMPLETIONS;
    }

    Effect wordLeft() {
        if (col == 0) {
            if (row == 0)
                return Effect.UNCHANGED;
            row--;
            col = lines.get(row).length();
        }
        String line = lines.get(row);
        while (col > 0 && Character.isWhitespace(line.charAt(col - 1)))
            col--;
        while (col > 0 && !Character.isWhitespace(line.charAt(col - 1)))
            col--;
        desiredCol = -1;
        return Effect.CLEAR_COMPLETIONS;
    }

    Effect wordRight() {
        String line = lines.get(row);
        if (col >= line.length()) {
            if (row >= lines.size() - 1)
                return Effect.UNCHANGED;
            row++;
            col = 0;
            line = lines.get(row);
        }
        while (col < line.length() && !Character.isWhitespace(line.charAt(col)))
            col++;
        while (col < line.length() && Character.isWhitespace(line.charAt(col)))
            col++;
        desiredCol = -1;
        return Effect.CLEAR_COMPLETIONS;
    }

    void setText(String text) {
        lines.clear();
        if (text == null || text.isEmpty()) {
            lines.add("");
        } else {
            lines.addAll(text.lines().toList());
            if (text.endsWith("\n"))
                lines.add("");
        }
        row = lines.size() - 1;
        col = lines.get(row).length();
        desiredCol = -1;
    }

    void reset() {
        lines.clear();
        lines.add("");
        row = 0;
        col = 0;
        desiredCol = -1;
    }

    void replaceSingleLine(String text) {
        lines.clear();
        lines.add(text != null ? text : "");
        row = 0;
        col = lines.getFirst().length();
        desiredCol = -1;
    }

    private void mergeWithPrevious() {
        String current = lines.remove(row);
        row--;
        col = lines.get(row).length();
        lines.set(row, lines.get(row) + current);
        desiredCol = -1;
    }
}
