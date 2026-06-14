package ai.pijota.agent;

import ai.pijota.messages.Message;
import ai.pijota.util.Util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

final class MessageHistory {
    private final List<Message> messages;

    MessageHistory() {
        this.messages = new ArrayList<>();
    }

    void add(Message msg) {
        synchronized (messages) {
            messages.add(msg);
        }
    }

    void set(int index, Message msg) {
        synchronized (messages) {
            messages.set(index, msg);
        }
    }

    void remove(int index) {
        synchronized (messages) {
            messages.remove(index);
        }
    }

    int size() {
        synchronized (messages) {
            return messages.size();
        }
    }

    void clear() {
        synchronized (messages) {
            messages.clear();
        }
    }

    void restore(List<Message> msgs) {
        synchronized (messages) {
            messages.clear();
            messages.addAll(msgs);
        }
    }

    List<Message> snapshot() {
        synchronized (messages) {
            return List.copyOf(messages);
        }
    }

    List<Message> all() {
        return Collections.unmodifiableList(messages);
    }

    /** A fresh, independently-mutable copy taken under the lock — safe to hand to callers and mutate. */
    List<Message> mutableSnapshot() {
        synchronized (messages) {
            return new ArrayList<>(messages);
        }
    }

    /**
     * The live backing list, for the single caller that compacts it in place ({@link CompactionManager}, which
     * synchronizes on the returned list — the same monitor this class uses). Everyone else takes a copy via
     * {@link #snapshot()} / {@link #mutableSnapshot()}.
     */
    List<Message> mutableList() {
        return messages;
    }

    /** Drops a trailing assistant message left empty by an aborted/incomplete turn (no content, tools, or thinking). */
    void removeTrailingEmptyAssistant() {
        synchronized (messages) {
            if (messages.isEmpty())
                return;
            var last = messages.getLast();
            if (last instanceof Message.Assistant a && a.content().isEmpty() && a.toolCalls().isEmpty()
                                            && (a.thinking() == null || a.thinking().isBlank()))
                messages.removeLast();
        }
    }

    void addSystemPrompt(String s) {
        synchronized (messages) {
            messages.add(new Message.System(s));
        }
    }

    void replaceSystemPrompt(String content) {
        synchronized (messages) {
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i) instanceof Message.System) {
                    messages.set(i, new Message.System(content));
                    return;
                }
            }
            messages.addFirst(new Message.System(content));
        }
    }

    Optional<String> undoLastUserTurn() {
        synchronized (messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i) instanceof Message.User user) {
                    String content = user.content();
                    messages.subList(i, messages.size()).clear();
                    stripSystemReminders();
                    return Optional.ofNullable(content);
                }
            }
        }
        stripSystemReminders();
        return Optional.empty();
    }

    void stripSystemReminders() {
        synchronized (messages) {
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i) instanceof Message.User user) {
                    String cleaned = Util.stripSystemReminders(user.content()).strip();
                    if (!cleaned.equals(user.content())) {
                        messages.set(i, new Message.User(user.id(), user.timestamp(), cleaned));
                    }
                }
            }
            messages.removeIf(msg -> msg instanceof Message.User user && user.content().isBlank());
        }
    }

    String lastAssistantContent() {
        synchronized (messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i) instanceof Message.Assistant a && a.content() != null)
                    return a.content();
            }
        }
        return null;
    }

    List<Message.User> userMessages() {
        synchronized (messages) {
            return messages.stream().filter(m -> m instanceof Message.User).map(m -> (Message.User) m).toList();
        }
    }
}
