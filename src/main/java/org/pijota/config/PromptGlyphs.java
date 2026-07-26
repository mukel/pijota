package org.pijota.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * User-configurable prompt-editor appearance (settings.json {@code "prompt"}). A {@code null} field (omitted in JSON)
 * falls back to the built-in default; an empty glyph string {@code ""} is honoured as "no glyph" (e.g. a caretless
 * input, or prefix-free continuation lines). {@code caret} is the idle/busy first-line marker; {@code bar} is prepended
 * to continuation / wrapped lines. {@code topDivider}/{@code bottomDivider} toggle the frame rules above/below the
 * input.
 */
public record PromptGlyphs(@JsonProperty("caret") String caret, @JsonProperty("error") String error,
                                @JsonProperty("question") String question,
                                @JsonProperty("continuation") String continuation, @JsonProperty("bar") String bar,
                                @JsonProperty("top_divider") Boolean topDivider,
                                @JsonProperty("bottom_divider") Boolean bottomDivider) {

    public PromptGlyphs {
        caret = caret == null ? "❯" : caret;
        error = error == null ? "✖" : error;
        question = question == null ? "❓" : question;
        continuation = continuation == null ? "┃" : continuation;
        bar = bar == null ? "│" : bar;
        topDivider = topDivider == null ? Boolean.TRUE : topDivider;
        bottomDivider = bottomDivider == null ? Boolean.TRUE : bottomDivider;
    }

    public PromptGlyphs() {
        this(null, null, null, null, null, null, null);
    }

    public static final PromptGlyphs DEFAULTS = new PromptGlyphs();
}
