package ai.pijota.tui;

import ai.pijota.messages.Message;

import java.util.List;

final class MessageBrowser {
    private final PromptEditor editor;
    private final java.util.function.Supplier<List<Message.User>> userMessagesSupplier;

    private List<Message.User> messages = List.of();
    private int idx = -1;

    MessageBrowser(PromptEditor editor, java.util.function.Supplier<List<Message.User>> userMessagesSupplier) {
        this.editor = editor;
        this.userMessagesSupplier = userMessagesSupplier;
    }

    boolean isActive() {
        return idx >= 0;
    }

    /**
     * Handles a key while browsing. Up/Down move, Enter accepts (exit), Esc cancels — all consumed (return true). Any
     * other key exits the browser and is NOT consumed (returns false) so the caller routes it onward to the editor.
     */
    boolean handleKey(Key key) {
        if (key == Key.Special.UP) {
            prev();
            return true;
        }
        if (key == Key.Special.DOWN) {
            next();
            return true;
        }
        if (key == Key.Special.ENTER) {
            exit();
            return true;
        }
        if (key == Key.Special.ESC) {
            cancel();
            return true;
        }
        exit();
        return false;
    }

    void enter() {
        messages = userMessagesSupplier.get();
        if (messages.isEmpty())
            return;
        idx = messages.size() - 1;
        editor.setText(messages.get(idx).content());
    }

    void prev() {
        if (idx > 0) {
            idx--;
            editor.setText(messages.get(idx).content());
        } else {
            exit();
            editor.setText("");
        }
    }

    void next() {
        if (idx < messages.size() - 1) {
            idx++;
            editor.setText(messages.get(idx).content());
        } else {
            exit();
            editor.setText("");
        }
    }

    void cancel() {
        if (idx < 0)
            return;
        exit();
        editor.setText("");
    }

    void exit() {
        if (idx < 0)
            return;
        idx = -1;
        messages = List.of();
    }
}
