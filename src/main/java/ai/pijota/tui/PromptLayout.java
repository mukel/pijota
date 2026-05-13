package ai.pijota.tui;

import ai.pijota.theme.AnsiTheme;

import java.io.PrintWriter;
import java.util.List;

public final class PromptLayout {

    private final List<PromptRegion> regions;
    private PromptSnapshot prompt = new PromptSnapshot(List.of(""), 0, 0, List.of());
    private int renderedHeight;
    private int visualCursorRow;

    public PromptLayout(List<PromptRegion> regions) {
        this.regions = List.copyOf(regions);
    }

    public void eraseCurrent(PrintWriter out) {
        if (visualCursorRow > 0) out.print("\033[" + visualCursorRow + "A");
        out.print("\r\033[J");
    }

    public void renderAfterErase(PrintWriter out, int width, AnsiTheme theme, PromptSnapshot snapshot) {
        prompt = snapshot;

        int height = 0;
        int cursorRow = -1;
        int cursorCol = -1;

        for (int i = 0; i < regions.size(); i++) {
            var region = regions.get(i);
            int h = region.height(width, snapshot);
            if (h <= 0) continue;
            if (height > 0) out.print("\r\n");
            var cursor = region.render(out, width, theme, snapshot);
            if (cursor.visualRow() >= 0) {
                cursorRow = height + cursor.visualRow();
                cursorCol = cursor.visualCol();
            }
            height += h;
        }

        renderedHeight = Math.max(1, height);
        visualCursorRow = Math.max(0, cursorRow);

        if (cursorRow >= 0) {
            int rowsUp = renderedHeight - 1 - cursorRow;
            if (rowsUp > 0) out.print("\033[" + rowsUp + "A");
            out.print('\r');
            if (cursorCol > 0) out.print("\033[" + cursorCol + "C");
        }
        out.flush();
    }
}
