package org.pijota.extensions;

/**
 * How a message sent by an extension is delivered relative to the agent's current activity; passed directly to the
 * agent layer ({@link ExtensionAPI#sendUserMessage} / {@code AgentContext.sendUserMessage}).
 */
public enum DeliverAs {

    /** Processed on the next turn, interrupting/steering the current run. */
    STEER,

    /** Queued to run after the current turn completes. */
    FOLLOW_UP,

    /** Delivered when the agent is next idle (the default). */
    NEXT_TURN
}
