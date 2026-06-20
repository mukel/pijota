package ai.pijota.extensions;

import java.util.List;

/**
 * Typed results returned by decision-hook handlers. Each record corresponds to one hook type:
 * <ul>
 * <li>{@link Block} — veto a tool call</li>
 * <li>{@link InputResult} — transform or consume user input</li>
 * <li>{@link UserBashResult} — block/replace/allow a user-initiated command</li>
 * <li>{@link BashToolCallResult} — block/allow an LLM-initiated bash</li>
 * <li>{@link MessageRender} — render a custom extension message in the TUI</li>
 * </ul>
 */
public final class ExtensionResult {

    /** Veto a tool call with a human-readable reason (shown to the model and user). */
    public record Block(String reason) {
    }

    /** The transformed text after input processing; {@code consumed} short-circuits remaining handlers. */
    public record InputResult(String text, boolean consumed) {
    }

    /** Decision on a user-initiated bash command: allow, block with reason, or replace with an alternative command. */
    public record UserBashResult(boolean blocked, String reason, String replacementCommand) {
        public static UserBashResult allow() {
            return new UserBashResult(false, null, null);
        }

        public static UserBashResult block(String reason) {
            return new UserBashResult(true, reason, null);
        }

        public static UserBashResult replace(String command) {
            return new UserBashResult(false, null, command);
        }
    }

    /** Decision on an LLM-initiated bash tool call: allow or block with reason. */
    public record BashToolCallResult(boolean blocked, String reason) {
        public static BashToolCallResult allow() {
            return new BashToolCallResult(false, null);
        }

        public static BashToolCallResult block(String reason) {
            return new BashToolCallResult(true, reason);
        }
    }

    /** Rendered display lines for a custom extension message in the TUI. */
    public record MessageRender(List<String> lines) {
        public MessageRender {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }
}
