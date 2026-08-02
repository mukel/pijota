package org.pijota.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * The shape every informational panel in the TUI shares: a header naming the subject, then indented label/value rows.
 * {@code /context}, {@code /usage}, {@code /skills} and friends each hand-built this, padding labels with literal
 * spaces, so the columns drifted apart (a twelve-character label silently pushed its row two columns right of the rest)
 * and adding a row meant recounting spaces by hand. Three different header grammars had also grown up: {@code
 * name  stats}, {@code name (stats)} and {@code name  count}.
 *
 * <p>
 * One renderer instead. The label column is computed from the widest label, so rows cannot drift; metadata is joined
 * with the same {@code ·} chip separator the tool lines and selector footers already use, so a panel reads like the
 * rest of the interface rather than like its own dialect.
 */
final class Report {

    private final BiFunction<String, String, String> styled;
    private final String title;
    private final List<String> chips = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();

    private record Row(String label, String value, String slot) {
    }

    /**
     * @param styled
     *            (slot, text) to styled text -- {@code theme::styled} or a host's equivalent, so a caller that only has
     *            one of those does not have to reach for the other.
     */
    Report(BiFunction<String, String, String> styled, String title) {
        this.styled = styled;
        this.title = title;
    }

    /** A metadata chip for the header, shown dim after the title and joined with {@code ·}. */
    Report chip(String text) {
        if (text != null && !text.isBlank())
            chips.add(text.strip());
        return this;
    }

    /** A label/value row. The label column is sized from the widest label, never hand-padded. */
    Report row(String label, String value) {
        return row(label, value, "statusText");
    }

    /** A row whose value carries a specific theme slot, for the one value in a panel that needs emphasis. */
    Report row(String label, String value, String slot) {
        rows.add(new Row(label == null ? "" : label, value == null ? "" : value, slot));
        return this;
    }

    /** A row of plain text with no label, for a note under the rows. */
    Report note(String text) {
        rows.add(new Row("", text == null ? "" : text, "commandMeta"));
        return this;
    }

    List<String> render() {
        var out = new ArrayList<String>();
        out.add(""); // a panel claims its line of air like any other block; duplicates collapse downstream
        var header = new StringBuilder(styled.apply("toolLabel", title));
        if (!chips.isEmpty())
            header.append(styled.apply("commandMeta", "  " + String.join(" · ", chips)));
        out.add(header.toString());

        int column = rows.stream().filter(r -> !r.label().isEmpty()).mapToInt(r -> AnsiWidth.visibleWidth(r.label()))
                                        .max().orElse(0);
        for (Row row : rows) {
            if (row.label().isEmpty()) {
                out.add(styled.apply(row.slot(), "  " + row.value()));
                continue;
            }
            String label = row.label() + " ".repeat(column - AnsiWidth.visibleWidth(row.label()));
            out.add(styled.apply("commandMeta", "  " + label) + "  " + styled.apply(row.slot(), row.value()));
        }
        return out;
    }
}
