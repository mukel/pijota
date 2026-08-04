package org.pijota;

import org.pijota.llm.ModelInfo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The facts sat in fixed columns, so a row ran to 113 characters and wrapped mid-word on any normal terminal, taking
 * the alignment of every row below it with it.
 */
class ListModelsFitsTheTerminalTest {

    /** A short name, a middling one, and the 51-character shape a gateway's ids really take. */
    private static final List<ModelInfo> MODELS = List.of(
                                    new ModelInfo("deepseek-v4-pro", "deepseek", "DeepSeek v4 Pro", 1_000_000, 0.435,
                                                                    0.87, List.of()),
                                    new ModelInfo("kimi-for-coding-highspeed", "kimi-coding", "Kimi", 256_000, 1.0, 2.0,
                                                                    List.of()),
                                    new ModelInfo("accounts/fireworks/models/deepseek-v4-pro", "fireworks", "DS",
                                                                    1_000_000, 1.74, 3.48, List.of()));

    private static List<String> lines(int width) {
        return Main.modelRows(MODELS, width).stream().flatMap(String::lines).toList();
    }

    /**
     * With one stated exception: an id longer than the whole terminal. Shortening it would make the output useless for
     * the thing it exists for, which is telling you what to type, so that one line is allowed to wrap and the facts
     * still get their own line beneath it.
     */
    @ParameterizedTest
    @ValueSource(ints = {40, 50, 60, 72, 80, 100, 132, 200})
    void noLineExceedsTheWidthUnlessTheIdAloneIsLongerThanIt(int width) {
        for (String line : lines(width)) {
            if (line.length() <= width)
                continue;
            String bare = line.strip();
            assertTrue(MODELS.stream().anyMatch(m -> (m.provider() + "/" + m.id()).equals(bare)),
                                            "only an unshortenable id may overflow; this is something else: " + line);
        }
    }

    /** Everything that is not a bare id has to fit, including every facts line. */
    @ParameterizedTest
    @ValueSource(ints = {40, 50, 60, 72, 80, 100, 132, 200})
    void everyFactsLineFits(int width) {
        for (String line : lines(width))
            if (line.contains("context "))
                assertTrue(line.length() <= width, "width " + width + " overflowed by " + (line.length() - width) + ": "
                                                + line);
    }

    /** The id is the thing you have to type back, so it is never shortened to make a row fit. */
    @ParameterizedTest
    @ValueSource(ints = {40, 80, 200})
    void everyIdSurvivesInFull(int width) {
        String all = String.join("\n", lines(width));
        for (ModelInfo m : MODELS)
            assertTrue(all.contains(m.provider() + "/" + m.id()), "truncated at width " + width + ": " + all);
    }

    /** And every model keeps its facts, whichever shape the row took. */
    @ParameterizedTest
    @ValueSource(ints = {40, 80, 200})
    void everyModelKeepsItsFacts(int width) {
        String all = String.join("\n", lines(width));
        assertEquals(MODELS.size(), all.split("context ", -1).length - 1, "a model lost its facts: " + all);
    }

    @Test
    void aWideTerminalGetsOneAlignedRowPerModel() {
        var rows = Main.modelRows(MODELS, 200);

        assertEquals(MODELS.size(), rows.size());
        assertTrue(rows.stream().noneMatch(r -> r.contains("\n")), "nothing should stack at 200 columns: " + rows);
        var factsAt = rows.stream().map(r -> r.indexOf("context ")).distinct().toList();
        assertEquals(1, factsAt.size(), "the facts must line up into a column: " + rows);
    }

    /**
     * One outlier must not stack the whole list. The column is the longest name that still leaves room for the facts,
     * so the rows that fit stay a table and only the gateway id gets a line of its own.
     */
    @Test
    void aSingleLongIdDoesNotCostEveryOtherRowItsTable() {
        var rows = Main.modelRows(MODELS, 80);

        assertFalse(rows.get(0).contains("\n"), "a 24-character name fits at 80 columns: " + rows.get(0));
        assertTrue(rows.get(2).contains("\n"), "the 51-character one cannot: " + rows.get(2));
    }

    @Test
    void aNarrowTerminalStacksEveryRow() {
        assertTrue(Main.modelRows(MODELS, 40).stream().allMatch(r -> r.contains("\n")),
                                        "nothing fits beside its facts at 40 columns");
    }

    @Test
    void noModelsIsNotAnError() {
        assertEquals(List.of(), Main.modelRows(List.of(), 80));
    }
}
