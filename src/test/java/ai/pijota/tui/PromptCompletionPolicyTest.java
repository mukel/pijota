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
        var policy = new PromptCompletionPolicy(new SlashCommandRegistry(), _ -> List.of());

        assertTrue(policy.refreshCommandCompletions("/sess").stream().anyMatch(i -> i.name().equals("/sessions")));
        assertEquals("/sessions", policy.commandSuggestion("/sess"));
        assertEquals("", policy.commandSuggestion("plain text"));
    }
}
