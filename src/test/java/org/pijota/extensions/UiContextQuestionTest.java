package org.pijota.extensions;

import org.pijota.tools.SlashCommandTool.Option;
import org.pijota.tools.SlashCommandTool.Question;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** The frontend-agnostic default: askQuestions decomposes into the select/prompt every UiContext already implements. */
class UiContextQuestionTest {

    @Test
    void defaultDecomposesIntoSelectForOptionsAndPromptForFreeText() throws Exception {
        UiContext ui = new UiContext() {
            @Override
            public void setStatus(String message) {}

            @Override
            public <T> void select(String title, List<T> items, Function<T, String> render, Consumer<T> onChoice) {
                onChoice.accept(items.get(0)); // a frontend would await a tap; here pick the first deterministically
            }

            @Override
            public CompletableFuture<String> prompt(String question, String defaultValue) {
                return CompletableFuture.completedFuture("typed answer");
            }
        };

        var withOptions = new Question("Color?", "Color", List.of(new Option("Red", ""), new Option("Blue", "")),
                                        false);
        var freeText = new Question("Name?", "Name", List.of(), false);

        String combined = ui.askQuestions(List.of(withOptions, freeText)).get();

        assertEquals("Color? → Red\nName? → typed answer", combined);
    }

    @Test
    void noUiResolvesToPromptDefaults() throws Exception {
        UiContext ui = new UiContext() {
            @Override
            public void setStatus(String message) {}
        };
        var q = new Question("Anything?", "Q", List.of(), false);
        assertEquals("Anything? → ", ui.askQuestions(List.of(q)).get());
    }
}
