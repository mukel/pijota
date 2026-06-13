package ai.pijota.tui;

import ai.pijota.messages.Message;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageBrowserTest {

    private PromptEditor fakeEditor() {
        return new PromptEditor(new SlashCommandRegistry());
    }

    @Test
    void initiallyInactive() {
        var editor = fakeEditor();
        var browser = new MessageBrowser(editor, List::of);
        assertFalse(browser.isActive());
    }

    @Test
    void enterWithMessagesSetsLastMessage() {
        var editor = fakeEditor();
        var msgs = List.of(new Message.User("first"), new Message.User("second"), new Message.User("third"));
        Supplier<List<Message.User>> supplier = () -> msgs;

        var browser = new MessageBrowser(editor, supplier);
        browser.enter();

        assertTrue(browser.isActive());
        assertEquals("third", editor.snapshot().lines().get(0));
    }

    @Test
    void enterWithEmptyMessagesDoesNotActivate() {
        var editor = fakeEditor();
        var browser = new MessageBrowser(editor, List::of);
        browser.enter();
        assertFalse(browser.isActive());
    }

    @Test
    void prevNavigatesBackward() {
        var editor = fakeEditor();
        var msgs = List.of(new Message.User("a"), new Message.User("b"), new Message.User("c"));
        var browser = new MessageBrowser(editor, () -> msgs);
        browser.enter();

        browser.prev();
        assertTrue(browser.isActive());
        assertEquals("b", editor.snapshot().lines().get(0));

        browser.prev();
        assertEquals("a", editor.snapshot().lines().get(0));
    }

    @Test
    void prevAtFirstExitsAndClears() {
        var editor = fakeEditor();
        var msgs = List.of(new Message.User("only"));
        var browser = new MessageBrowser(editor, () -> msgs);
        browser.enter();

        browser.prev();
        assertFalse(browser.isActive());
        assertTrue(editor.snapshot().lines().get(0).isEmpty());
    }

    @Test
    void nextNavigatesForward() {
        var editor = fakeEditor();
        var msgs = List.of(new Message.User("a"), new Message.User("b"), new Message.User("c"));
        var browser = new MessageBrowser(editor, () -> msgs);
        browser.enter();
        browser.prev();
        browser.prev();

        browser.next();
        assertEquals("b", editor.snapshot().lines().get(0));

        browser.next();
        assertEquals("c", editor.snapshot().lines().get(0));
    }

    @Test
    void nextAtLastExitsAndClears() {
        var editor = fakeEditor();
        var msgs = List.of(new Message.User("a"), new Message.User("b"));
        var browser = new MessageBrowser(editor, () -> msgs);
        browser.enter();

        browser.next();
        assertFalse(browser.isActive());
        assertTrue(editor.snapshot().lines().get(0).isEmpty());
    }

    @Test
    void cancelExitsAndClears() {
        var editor = fakeEditor();
        var msgs = List.of(new Message.User("test"));
        var browser = new MessageBrowser(editor, () -> msgs);
        browser.enter();

        browser.cancel();
        assertFalse(browser.isActive());
        assertTrue(editor.snapshot().lines().get(0).isEmpty());
    }

    @Test
    void cancelWhenInactiveDoesNothing() {
        var editor = fakeEditor();
        var browser = new MessageBrowser(editor, List::of);
        browser.cancel();
        assertFalse(browser.isActive());
    }

    @Test
    void exitWhenInactiveDoesNothing() {
        var editor = fakeEditor();
        var browser = new MessageBrowser(editor, List::of);
        browser.exit();
        assertFalse(browser.isActive());
    }

    @Test
    void handleKeyNavigatesAndConsumes() {
        var editor = fakeEditor();
        var msgs = List.of(new Message.User("a"), new Message.User("b"), new Message.User("c"));
        var browser = new MessageBrowser(editor, () -> msgs);
        browser.enter();

        assertTrue(browser.handleKey(Key.Special.UP), "Up is consumed");
        assertEquals("b", editor.snapshot().lines().get(0));
        assertTrue(browser.handleKey(Key.Special.DOWN), "Down is consumed");
        assertEquals("c", editor.snapshot().lines().get(0));
    }

    @Test
    void handleKeyEnterAcceptsAndExits() {
        var editor = fakeEditor();
        var browser = new MessageBrowser(editor, () -> List.of(new Message.User("keep me")));
        browser.enter();

        assertTrue(browser.handleKey(Key.Special.ENTER));
        assertFalse(browser.isActive());
        assertEquals("keep me", editor.snapshot().lines().get(0), "Enter keeps the browsed text");
    }

    @Test
    void handleKeyEscCancelsAndClears() {
        var editor = fakeEditor();
        var browser = new MessageBrowser(editor, () -> List.of(new Message.User("x")));
        browser.enter();

        assertTrue(browser.handleKey(Key.Special.ESC));
        assertFalse(browser.isActive());
        assertTrue(editor.snapshot().lines().get(0).isEmpty(), "Esc clears");
    }

    @Test
    void handleKeyOtherKeyExitsAndFallsThrough() {
        var editor = fakeEditor();
        var browser = new MessageBrowser(editor, () -> List.of(new Message.User("x")));
        browser.enter();

        assertFalse(browser.handleKey(new Key.Char('z')), "non-nav key is not consumed (falls through to editor)");
        assertFalse(browser.isActive(), "and exits the browser");
    }
}
