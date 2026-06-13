package ai.pijota.extensions;

/**
 * How a message sent by an extension is delivered relative to the agent's current activity. Replaces the previous
 * magic-string delivery mode on {@link ExtensionAPI#sendUserMessage}.
 */
public enum DeliverAs {

    /** Processed on the next turn, interrupting/steering the current run. */
    STEER("steer"),

    /** Queued to run after the current turn completes. */
    FOLLOW_UP("followUp"),

    /** Delivered when the agent is next idle (the default). */
    NEXT_TURN("nextTurn");

    private final String wire;

    DeliverAs(String wire) {
        this.wire = wire;
    }

    /** The wire identifier understood by the agent layer. */
    public String wire() {
        return wire;
    }
}
