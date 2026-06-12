package ai.pijota.extensions;

import java.util.List;

public final class ExtensionResult {

    public record Block(String reason) {
    }

    public record InputResult(String text, boolean consumed) {
    }

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

    public record BashToolCallResult(boolean blocked, String reason) {
        public static BashToolCallResult allow() {
            return new BashToolCallResult(false, null);
        }

        public static BashToolCallResult block(String reason) {
            return new BashToolCallResult(true, reason);
        }
    }

    public record MessageRender(List<String> lines) {
        public MessageRender {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }
}
