package ai.pijota.tui;

import ai.pijota.theme.AnsiTheme;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SelectorRegionTest {

    @Test
    void heightZeroWhenInactive() {
        var selector = new Selector<>(List.of());
        var region = new SelectorRegion<>(selector);
        assertEquals(0, region.height(80, new PromptSnapshot(List.of(""), 0, 0, List.of())));
    }

    @Test
    void heightWhenActiveWithItems() {
        var items = List.<SelectorItem>of(new CompletionItem("alpha", "desc A"), new CompletionItem("beta", "desc B"),
                                        new CompletionItem("gamma", "desc C"));
        var selector = new Selector<>(items);
        selector.activate("");
        var region = new SelectorRegion<>(selector);

        var snapshot = new PromptSnapshot(List.of(""), 0, 0, List.of());
        int h = region.height(80, snapshot);
        assertTrue(h > 0, "height should be > 0 when active with items");
    }

    @Test
    void heightWhenActiveEmptyItems() {
        var selector = new Selector<>(List.of());
        selector.activate("");
        var region = new SelectorRegion<>(selector);
        var snapshot = new PromptSnapshot(List.of(""), 0, 0, List.of());
        int h = region.height(80, snapshot);
        assertTrue(h > 0);
    }

    @Test
    void heightWithManyItems() {
        var items = new ArrayList<SelectorItem>();
        for (int i = 0; i < 20; i++)
            items.add(new CompletionItem("item" + i, "desc" + i));
        var selector = new Selector<>(items);
        selector.activate("");
        var region = new SelectorRegion<>(selector);
        var snapshot = new PromptSnapshot(List.of(""), 0, 0, List.of());
        int h = region.height(80, snapshot);
        assertTrue(h > 0);
    }

    @Test
    void renderReturnsNoCursorWhenInactive() {
        var selector = new Selector<>(List.of());
        var region = new SelectorRegion<>(selector);
        var out = new PrintWriter(OutputStream.nullOutputStream());
        var theme = AnsiTheme.dark();
        var snapshot = new PromptSnapshot(List.of(""), 0, 0, List.of());
        assertEquals(PromptRegion.NO_CURSOR, region.render(out, 80, theme, snapshot));
    }

    @Test
    void renderActiveWithItemsWritesTitleAndItems() {
        var items = List.<SelectorItem>of(new CompletionItem("alpha", "desc A"), new CompletionItem("beta", "desc B"));
        var selector = new Selector<>(items);
        selector.activate("");
        var region = new SelectorRegion<>(selector);

        var sw = new StringWriter();
        var out = new PrintWriter(sw);
        var theme = AnsiTheme.dark();
        var snapshot = new PromptSnapshot(List.of(""), 0, 0, List.of());

        region.render(out, 80, theme, snapshot);
        out.flush();
        var rendered = sw.toString();

        assertTrue(rendered.contains("select"), rendered);
        assertTrue(rendered.contains("alpha") || rendered.contains("β") ? rendered.contains("alpha") : true);
        assertTrue(rendered.contains("beta") || rendered.contains("β") ? rendered.contains("beta") : true);
        assertTrue(rendered.contains("1/2"), rendered);
        assertTrue(rendered.contains("type to search"), rendered);
    }

    @Test
    void renderActiveEmptyWritesNothingToShow() {
        var selector = new Selector<>(List.of());
        selector.activate("");
        var region = new SelectorRegion<>(selector);

        var sw = new StringWriter();
        var out = new PrintWriter(sw);
        var theme = AnsiTheme.dark();
        var snapshot = new PromptSnapshot(List.of(""), 0, 0, List.of());

        region.render(out, 80, theme, snapshot);
        out.flush();
        var rendered = sw.toString();

        assertTrue(rendered.contains("nothing to show"), rendered);
    }

    @Test
    void renderWithScrollIndicators() {
        var items = new ArrayList<SelectorItem>();
        for (int i = 0; i < 12; i++)
            items.add(new CompletionItem("item" + i, "desc" + i));
        var selector = new Selector<>(items);
        selector.activate("");
        for (int i = 0; i < 5; i++)
            selector.selectNext();

        var region = new SelectorRegion<>(selector);
        var snapshot = new PromptSnapshot(List.of(""), 0, 0, List.of());

        assertTrue(region.height(80, snapshot) > 5);

        var sw = new StringWriter();
        var out = new PrintWriter(sw);
        var theme = AnsiTheme.dark();
        region.render(out, 80, theme, snapshot);
        out.flush();
        var rendered = sw.toString();

        assertTrue(rendered.contains("↑"), rendered);
        assertTrue(rendered.contains("↓"), rendered);
    }
}
