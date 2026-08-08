package org.pijota.agent;

import org.pijota.messages.Message;
import org.pijota.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class MessageHistory {
    private int nonAppendEpoch; // see nonAppendEpoch()
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

    /**
     * How many times the list has changed by anything other than appending.
     *
     * <p>
     * The context anchor in {@link AgentLoop} measures a gap against a specific set of messages and stays valid while
     * messages are ADDED -- that is the point, so the count grows as a turn streams. It stops being valid the moment
     * content is removed or rewritten, because the gap it recorded absorbed that content's share of the byte
     * heuristic's undercount, and keeping it then reads high on a conversation that just got smaller.
     *
     * <p>
     * Counted here rather than announced by each caller. Hanging the invalidation off a notification meant /undo was
     * covered because it happens to notify, while a system-reminder strip -- which runs on every submit and can delete
     * messages outright -- was not. A mutator cannot forget to be counted if the counting is inside it.
     */
    int nonAppendEpoch() {
        synchronized (messages) {
            return nonAppendEpoch;
        }
    }

    void remove(int index) {
        synchronized (messages) {
            messages.remove(index);
            nonAppendEpoch++;
        }
    }

    int size() {
        synchronized (messages) {
            return messages.size();
        }
    }

    void clear() {
        synchronized (messages) {
            nonAppendEpoch++;
            messages.clear();
        }
    }

    void restore(List<Message> msgs) {
        synchronized (messages) {
            nonAppendEpoch++;
            messages.clear();
            messages.addAll(msgs);
        }
    }

    List<Message> snapshot() {
        synchronized (messages) {
            return List.copyOf(messages);
        }
    }

    /** A fresh, independently-mutable copy taken under the lock, safe to hand to callers and mutate. */
    List<Message> mutableSnapshot() {
        synchronized (messages) {
            return new ArrayList<>(messages);
        }
    }

    /**
     * The live backing list, for the single caller that compacts it in place ({@link CompactionManager}, which
     * synchronizes on the returned list, the same monitor this class uses). Everyone else takes a copy via
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
                                            && (a.thinking() == null || a.thinking().isBlank())) {
                messages.removeLast();
                nonAppendEpoch++;
            }
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
                    nonAppendEpoch++;
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
            boolean changed = false;
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i) instanceof Message.User user) {
                    String cleaned = Util.stripSystemReminders(user.content()).strip();
                    if (!cleaned.equals(user.content())) {
                        messages.set(i, new Message.User(user.id(), user.timestamp(), cleaned, user.attachments()));
                        changed = true;
                    }
                }
            }
            changed |= messages.removeIf(msg -> msg instanceof Message.User user && user.content().isBlank());
            // Only when something actually went: this runs on every submit, and bumping regardless would throw away a
            // valid anchor before every single turn.
            if (changed)
                nonAppendEpoch++;
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
