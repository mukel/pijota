package org.pijota.agent;

import org.pijota.messages.Message;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageHistoryTest {
    @Test
    void snapshotReturnsImmutableCopyAndAddDoesNotAffectIt() {
        var h = new MessageHistory();
        h.add(new Message.User("hello"));

        var snap = h.snapshot();
        h.add(new Message.User("world"));

        assertEquals(1, snap.size());
        assertEquals(2, h.snapshot().size());
    }

    @Test
    void undoLastUserTurnRemovesUserAndEverythingAfter() {
        var h = new MessageHistory();
        h.add(new Message.User("first"));
        h.add(new Message.Assistant("a1", List.of(), null));
        h.add(new Message.User("second"));
        h.add(new Message.Assistant("a2", List.of(), null));

        var result = h.undoLastUserTurn();

        assertTrue(result.isPresent());
        assertEquals("second", result.orElseThrow());
        assertEquals(2, h.snapshot().size());
        assertEquals("first", ((Message.User) h.snapshot().get(0)).content());
    }

    @Test
    void replaceSystemPromptReplacesOrPrepends() {
        var h = new MessageHistory();
        h.addSystemPrompt("v1");
        h.add(new Message.User("hi"));

        h.replaceSystemPrompt("v2");
        assertEquals("v2", ((Message.System) h.snapshot().get(0)).content());

        var h2 = new MessageHistory();
        h2.add(new Message.User("hi"));
        h2.replaceSystemPrompt("fresh");
        assertEquals("fresh", ((Message.System) h2.snapshot().get(0)).content());
    }

    @Test
    void stripSystemRemindersCleansUserMessages() {
        var h = new MessageHistory();
        h.add(new Message.User("before <system-reminder>x</system-reminder> after"));
        h.add(new Message.Assistant("assistant", List.of(), null));

        h.stripSystemReminders();

        var user = (Message.User) h.snapshot().get(0);
        assertFalse(user.content().contains("system-reminder"));
    }

    @Test
    void lastAssistantContentFindsLatestAssistant() {
        var h = new MessageHistory();
        h.add(new Message.User("hi"));
        h.add(new Message.Assistant("first", List.of(), null));
        h.add(new Message.User("more"));
        h.add(new Message.Assistant("second", List.of(), null));

        assertEquals("second", h.lastAssistantContent());
    }

    @Test
    void restoreReplacesAllMessages() {
        var h = new MessageHistory();
        h.add(new Message.User("old"));

        h.restore(List.of(new Message.User("new"), new Message.Assistant("a", List.of(), null)));

        assertEquals(2, h.snapshot().size());
        assertEquals("new", ((Message.User) h.snapshot().get(0)).content());
    }
}
