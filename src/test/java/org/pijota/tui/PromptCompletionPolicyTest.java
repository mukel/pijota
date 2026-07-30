package org.pijota.tui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptCompletionPolicyTest {
    @Test
    void replacesSingleSlashCommandMatch() {
        var policy = new PromptCompletionPolicy(new SlashCommandRegistry(), ignored -> List.of());

        var decision = policy.complete("/sett");

        assertEquals(PromptCompletionPolicy.Kind.REPLACE, decision.kind());
        assertEquals("/settings", decision.replacement());
        assertFalse(decision.refreshAfterReplace());
    }

    @Test
    void replacesCommonSlashCommandPrefixAndRequestsRefresh() {
        var policy = new PromptCompletionPolicy(new SlashCommandRegistry(), ignored -> List.of());

        var decision = policy.complete("/c");

        assertEquals(PromptCompletionPolicy.Kind.REPLACE, decision.kind());
        assertEquals("/co", decision.replacement());
        assertTrue(decision.refreshAfterReplace());
    }

    @Test
    void showsFileCompletionsWithAcceptedPrefix() {
        var policy = new PromptCompletionPolicy(new SlashCommandRegistry(),
                                        text -> List.of(new CompletionItem("Main.java", "file")));

        var decision = policy.complete("open src/");

        assertEquals(PromptCompletionPolicy.Kind.SHOW, decision.kind());
        assertEquals("open ", decision.prefix());
        assertEquals("Main.java", decision.items().getFirst().name());
    }

    @Test
    void refreshesSlashCompletionsAndSuggestsCommands() {
        var policy = new PromptCompletionPolicy(new SlashCommandRegistry(), ignored -> List.of());

        assertTrue(policy.refreshCommandCompletions("/sess").stream().anyMatch(i -> i.name().equals("/sessions")));
        // Ghost text is only the trailing suffix ("/sess" + "ions" → "/sessions"), not the whole command name.
        assertEquals("ions", policy.commandSuggestion("/sess"));
        assertEquals("", policy.commandSuggestion("plain text"));
        // A fuzzy (non-prefix) match offers no inline suggestion — appending its suffix would not read correctly.
        assertEquals("", policy.commandSuggestion("/ssns"));
    }
}
