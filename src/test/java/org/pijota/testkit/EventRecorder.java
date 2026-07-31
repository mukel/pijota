package org.pijota.testkit;

import org.pijota.agent.AgentEvent;
import org.pijota.agent.AgentLoop;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Captures {@link AgentEvent}s from an {@link AgentLoop} for assertions. Replaces the hand-rolled {@code recordEvents}
 * + {@code events.stream().anyMatch(e -> e instanceof X)} idiom: terser, and the {@code assertEmitted} helpers name the
 * missing event type (and the events actually seen) on failure.
 */
public final class EventRecorder {
    private final List<AgentEvent> events = new CopyOnWriteArrayList<>();

    private EventRecorder() {}

    /**
     * Attach a recorder to an agent; every event from now on is captured. Use its {@code assertEmitted}/{@code first}.
     */
    public static EventRecorder attach(AgentLoop agent) {
        var recorder = new EventRecorder();
        agent.addEventListener(recorder.events::add);
        return recorder;
    }

    /** A live list of the agent's events (mutates as they arrive), drop-in for plain stream/anyMatch assertions. */
    public static List<AgentEvent> capture(AgentLoop agent) {
        var list = new CopyOnWriteArrayList<AgentEvent>();
        agent.addEventListener(list::add);
        return list;
    }

    /** All captured events, in order. */
    public List<AgentEvent> events() {
        return List.copyOf(events);
    }

    public boolean emitted(Class<? extends AgentEvent> type) {
        return events.stream().anyMatch(type::isInstance);
    }

    public <T extends AgentEvent> boolean emitted(Class<T> type, Predicate<T> when) {
        return events.stream().filter(type::isInstance).map(type::cast).anyMatch(when);
    }

    /** First event of the given type. */
    public <T extends AgentEvent> T first(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).findFirst()
                                        .orElseThrow(() -> new AssertionError("no " + type.getSimpleName()
                                                                        + " event; saw: " + seen()));
    }

    public void assertEmitted(Class<? extends AgentEvent> type) {
        if (!emitted(type))
            throw new AssertionError("expected event " + type.getSimpleName() + " but saw: " + seen());
    }

    public <T extends AgentEvent> void assertEmitted(Class<T> type, Predicate<T> when) {
        if (!emitted(type, when))
            throw new AssertionError("expected " + type.getSimpleName() + " matching predicate but saw: " + seen());
    }

    private String seen() {
        return events.stream().map(e -> e.getClass().getSimpleName()).toList().toString();
    }
}
