package org.pijota.tui;

import org.pijota.theme.AnsiTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Modal single-choice selector drawn in the prompt area. It owns its navigation state and renders its own rows; the
 * host only forwards keys ({@link #handleKey}) and asks for the lines to draw ({@link #renderLines}). Being
 * self-contained keeps its behavior unit-testable without a real terminal and out of the main TUI loop.
 */
final class PromptSelector {

    private static final int WINDOW = 8;

    private volatile List<String> labels;
    private volatile String title;
    private int index;
    private volatile IntConsumer onConfirm;

    /** Activates the selector with the given display labels; {@code onConfirm} fires with the chosen index. */
    void show(String title, List<String> labels, IntConsumer onConfirm) {
        this.title = title;
        this.labels = List.copyOf(labels);
        this.index = 0;
        this.onConfirm = onConfirm;
    }

    boolean active() {
        return onConfirm != null;
    }

    void cancel() {
        onConfirm = null;
        labels = null;
    }

    /**
     * Handles a key while active, returning whether it was consumed. Enter fires the choice callback with the
     * highlighted index and deactivates; Esc deactivates silently; Up/Down move the highlight. The caller is
     * responsible for redrawing after a consumed key.
     */
    boolean handleKey(Key key) {
        var current = labels;
        if (current == null)
            return false;
        if (key == Key.Special.UP) {
            index = Math.max(0, index - 1);
            return true;
        }
        if (key == Key.Special.DOWN) {
            index = Math.min(current.size() - 1, index + 1);
            return true;
        }
        if (key == Key.Special.ENTER) {
            int chosen = index;
            var confirm = onConfirm;
            int count = current.size();
            cancel();
            if (confirm != null && chosen >= 0 && chosen < count)
                confirm.accept(chosen);
            return true;
        }
        if (key == Key.Special.ESC) {
            cancel();
            return true;
        }
        return false;
    }

    /** The scrolled view of the list: a {@code size}-row window starting at {@code start}, with overflow markers. */
    private record Window(int total, int start, int size, boolean moreAbove, boolean moreBelow) {
        int below() {
            return total - (start + size);
        }
    }

    private Window window(List<String> current) {
        int total = current.size();
        int size = Math.min(total, WINDOW);
        int start = Math.max(0, Math.min(index - size / 2, total - size));
        return new Window(total, start, size, start > 0, total - (start + size) > 0);
    }

    /** Number of terminal rows the rendered selector occupies (title + window + optional more-markers + hint). */
    int lineCount() {
        var current = labels;
        if (onConfirm == null || current == null || current.isEmpty())
            return 0;
        var w = window(current);
        return 1 + w.size() + 1 + (w.moreAbove() ? 1 : 0) + (w.moreBelow() ? 1 : 0);
    }

    /** Lines to render in the prompt area, or empty when inactive. The window scrolls to keep the highlight visible. */
    List<String> renderLines(AnsiTheme theme) {
        var current = labels;
        if (onConfirm == null || current == null || current.isEmpty())
            return List.of();
        var w = window(current);
        var out = new ArrayList<String>();
        out.add(theme.styled("hr", title != null ? title : "select"));
        if (w.moreAbove())
            out.add(theme.styled("dim", "  ↑ " + w.start() + " more"));
        for (int i = w.start(); i < w.start() + w.size(); i++) {
            String prefix = i == index ? theme.styled("toolLabel", ">") : " ";
            out.add(prefix + " " + current.get(i));
        }
        if (w.moreBelow())
            out.add(theme.styled("dim", "  ↓ " + w.below() + " more"));
        out.add(theme.styled("statusText", "  ↑/↓ navigate · Enter select · Esc cancel"));
        return out;
    }
}
