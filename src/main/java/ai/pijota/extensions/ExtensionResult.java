package ai.pijota.extensions;

public final class ExtensionResult {

    public sealed interface ToolCall permits Allow, Block {}

    public record Allow() implements ToolCall {}
    public record Block(String reason) implements ToolCall {}

    public sealed interface Input permits Continue, Consume {}

    public record Continue(String text) implements Input {}
    public record Consume(String text) implements Input {}

    public record UserBashResult(boolean blocked, String reason, String replacementCommand) {
        public static UserBashResult allow() { return new UserBashResult(false, null, null); }
        public static UserBashResult block(String reason) { return new UserBashResult(true, reason, null); }
        public static UserBashResult replace(String command) { return new UserBashResult(false, null, command); }
    }

    public record BashToolCallResult(boolean blocked, String reason) {
        public static BashToolCallResult allow() { return new BashToolCallResult(false, null); }
        public static BashToolCallResult block(String reason) { return new BashToolCallResult(true, reason); }
    }
}
