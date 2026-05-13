package ai.pijota.extensions;

public final class ExtensionResult {

    public sealed interface ToolCall permits Allow, Block {}

    public record Allow() implements ToolCall {}
    public record Block(String reason) implements ToolCall {}

    public sealed interface Input permits Continue, Consume {}

    public record Continue(String text) implements Input {}
    public record Consume(String text) implements Input {}
}
