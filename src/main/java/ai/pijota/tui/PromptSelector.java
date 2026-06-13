package ai.pijota.tui;

import ai.pijota.theme.AnsiTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Modal single-choice selector drawn in the prompt area. It owns its navigation state and renders its own rows; the
 * host only forwards keys ({@link #handleKey}) and asks for the lines to draw ({@link #renderLines}). Keeping this
 * self-contained keeps the selector's behavior unit-testable without a real terminal and out of the main TUI loop.
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

    /** Lines to render in the prompt area, or empty when inactive. The window scrolls to keep the highlight visible. */
    List<String> renderLines(AnsiTheme theme) {
        var current = labels;
        if (onConfirm == null || current == null || current.isEmpty())
            return List.of();
        var out = new ArrayList<String>();
        int total = current.size();
        int window = Math.min(total, WINDOW);
        int start = Math.max(0, Math.min(index - window / 2, total - window));
        out.add(theme.styled("hr", title != null ? title : "select"));
        if (start > 0)
            out.add(theme.styled("dim", "  ↑ " + start + " more"));
        for (int i = start; i < start + window; i++) {
            String prefix = i == index ? theme.styled("toolLabel", ">") : " ";
            out.add(prefix + " " + current.get(i));
        }
        int below = total - (start + window);
        if (below > 0)
            out.add(theme.styled("dim", "  ↓ " + below + " more"));
        out.add(theme.styled("statusText", "  ↑/↓ navigate · Enter select · Esc cancel"));
        return out;
    }
}
