package ai.pijota.agent;

/** The provider/model/context-window a session was last run with, recorded in the session and used to resume it. */
public record ModelSelection(String provider, String model, long contextWindow) {
}
