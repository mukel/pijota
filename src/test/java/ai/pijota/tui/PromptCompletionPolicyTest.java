package ai.pijota.tui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptCompletionPolicyTest {
    @Test
    void replacesSingleSlashCommandMatch() {
        var policy = new PromptCompletionPolicy(new SlashCommandRegistry(), _ -> List.of());

        var decision = policy.complete("/sett");

        assertEquals(PromptCompletionPolicy.Kind.REPLACE, decision.kind());
        assertEquals("/settings", decision.replacement());
        assertFalse(decision.refreshAfterReplace());
    }

    @Test
    void replacesCommonSlashCommandPrefixAndRequestsRefresh() {
        var policy = new PromptCompletionPolicy(new SlashCommandRegistry(), _ -> List.of());

        var decision = policy.complete("/ses");

        assertEquals(PromptCompletionPolicy.Kind.REPLACE, decision.kind());
        assertEquals("/session", decision.replacement());
        assertTrue(decision.refreshAfterReplace());
    }

    @Test
    void singleFileMatchCompletesOutrightSoYouCanKeepTyping() {
        var policy = new PromptCompletionPolicy(new SlashCommandRegistry(),
                                        text -> List.of(new CompletionItem("@src/", "dir")));

        var decision = policy.complete("@s");

        assertEquals(PromptCompletionPolicy.Kind.REPLACE, decision.kind());
        assertEquals("@src/", decision.replacement()); // trailing / kept → the next Tab descends a level
    }

    @Test
    void multipleFileMatchesExtendToTheirCommonPrefix() {
        var policy = new PromptCompletionPolicy(new SlashCommandRegistry(),
                                        text -> List.of(new CompletionItem("@src/main/", "dir"),
                                                                        new CompletionItem("@src/models/", "dir")));

        var decision = policy.complete("@src/");

        assertEquals(PromptCompletionPolicy.Kind.REPLACE, decision.kind());
        assertEquals("@src/m", decision.replacement()); // longest unambiguous extension, then the user types on
    }

    @Test
    void ambiguousFileMatchesShowTheChooserWithTheAcceptedPrefix() {
        var policy = new PromptCompletionPolicy(new SlashCommandRegistry(), text -> List
                                        .of(new CompletionItem("@alpha", "file"), new CompletionItem("@beta", "file")));

        var decision = policy.complete("open @");

        assertEquals(PromptCompletionPolicy.Kind.SHOW, decision.kind());
        assertEquals("open ", decision.prefix());
    }

    @Test
    void refreshesSlashCompletionsAndSuggestsCommands() {
        var policy = new PromptCompletionPolicy(new SlashCommandRegistry(), _ -> List.of());

        assertTrue(policy.refreshCommandCompletions("/sess").stream().anyMatch(i -> i.name().equals("/sessions")));
        // Ghost text is only the trailing suffix ("/sess" + "ions" → "/sessions"), not the whole command name.
        assertEquals("ions", policy.commandSuggestion("/sess"));
        assertEquals("", policy.commandSuggestion("plain text"));
        // A fuzzy (non-prefix) match offers no inline suggestion — appending its suffix would not read correctly.
        assertEquals("", policy.commandSuggestion("/ssns"));
    }
}
