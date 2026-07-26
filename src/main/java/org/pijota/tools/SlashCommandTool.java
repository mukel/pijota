package org.pijota.tools;

import org.pijota.llm.ToolSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class SlashCommandTool implements Tool {
    public record Option(String label, String description) {
    }
    public record Question(String question, String header, List<Option> options, boolean multiple) {
    }
    public interface QuestionHandler {
        CompletableFuture<String> ask(List<Question> questions);
    }

    // Process-wide handler: the agent runs one active UI owner at a time, and ExtensionRegistry re-points this whenever
    // that owner changes (and sets it to null when none is attached), so a single static reflects the live invariant.
    // Kept static rather than per-instance to avoid coupling ExtensionRegistry to the ToolRegistry just to find the
    // registered tool instance; if multiple concurrent UIs ever exist, this must become instance-scoped.
    private static volatile QuestionHandler questionHandler;

    public static void setQuestionHandler(QuestionHandler handler) {
        questionHandler = handler;
    }

    @Override
    public String name() {
        return "question";
    }

    @Override
    public String glyph() {
        return "❓";
    }

    @Override
    public String description() {
        return "Ask the user one or more questions and wait for their answer. Use it only when you are genuinely "
                                        + "blocked on a decision, preference, or missing fact you cannot reasonably infer or look up — not "
                                        + "for things you can decide yourself, and not to narrate progress. Pass a non-empty `questions` "
                                        + "array of {question, header, options?, multiple?}: `question` is the full question text (required), "
                                        + "`header` a 1-3 word label, `options` an optional list of {label, description?} choices, `multiple` "
                                        + "true to allow picking several. The user can always type a free-text answer instead of choosing an "
                                        + "option. For a single question you may pass `question`/`header`/`options` at the top level instead.";
    }

    @Override
    public Map<String, Object> schema() {
        var questionItem = ToolSchema.object().str("question", "Complete question").str("text", "Alias for question")
                                        .str("header", "Very short label").array("options", null, false, optionSchema())
                                        .bool("multiple", null).build();
        return ToolSchema.object().str("question", "Single question to ask. Alias for questions[0].question")
                                        .str("text", "Single question to ask. Alias for question")
                                        .str("header", "Very short label for a single question")
                                        .array("options", "Options for a single question", false, optionSchema())
                                        .bool("multiple", "Allow selecting multiple options for a single question")
                                        .array("questions", "Questions to ask. Preferred form for one or more questions.",
                                                                        false, questionItem)
                                        .build();
    }

    @Override
    public CompletableFuture<Tool.ToolResult> execute(String toolCallId, Args args, String cwd) {
        try {
            List<?> questions = normalizeQuestions(args.map());
            if (questions.isEmpty()) {
                return CompletableFuture.completedFuture(Tool.ToolResult.error("No questions provided"));
            }

            var parsed = new ArrayList<Question>();
            for (int i = 0; i < questions.size(); i++) {
                if (!(questions.get(i) instanceof Map<?, ?> q)) {
                    return CompletableFuture.completedFuture(Tool.ToolResult
                                                    .error("Question " + (i + 1) + " must be an object"));
                }
                Object question = q.get("question") != null ? q.get("question") : q.get("text");
                if (!(question instanceof String text) || text.isBlank()) {
                    return CompletableFuture.completedFuture(Tool.ToolResult
                                                    .error("Question " + (i + 1) + " is missing question text"));
                }
                var optionsOut = new ArrayList<Option>();
                Object rawOptions = q.get("options");
                if (rawOptions instanceof List<?> options) {
                    for (Object rawOption : options) {
                        if (!(rawOption instanceof Map<?, ?> opt))
                            continue;
                        optionsOut.add(new Option(stringValue(opt.get("label")), stringValue(opt.get("description"))));
                    }
                }
                parsed.add(new Question(text, stringValue(q.get("header")), optionsOut,
                                                Boolean.TRUE.equals(q.get("multiple"))));
            }

            var handler = questionHandler;
            if (handler != null) {
                return handler.ask(parsed).thenApply(answer -> Tool.ToolResult.success("User answered:\n" + answer));
            }
            return CompletableFuture.completedFuture(Tool.ToolResult.success(formatQuestions(parsed)));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(Tool.ToolResult.error("Question tool error: " + e.getMessage()));
        }
    }

    private static String formatQuestions(List<Question> questions) {
        var sb = new StringBuilder();
        sb.append("[The following questions need user input]\n\n");
        for (int i = 0; i < questions.size(); i++) {
            var q = questions.get(i);
            sb.append("Q").append(i + 1).append(": ").append(q.question()).append('\n');
            for (var opt : q.options()) {
                sb.append("  - ").append(opt.label()).append(": ").append(opt.description()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String stringValue(Object value) {
        return value instanceof String s ? s : "";
    }

    private static Map<String, Object> optionSchema() {
        return ToolSchema.object().str("label", "Short option label", true).str("description", "What this option means")
                                        .buildOpen();
    }

    private static List<?> normalizeQuestions(Map<String, Object> args) {
        Object rawQuestions = args.get("questions");
        if (rawQuestions instanceof List<?> list)
            return list;
        Object question = args.get("question") != null ? args.get("question") : args.get("text");
        if (question instanceof String text && !text.isBlank()) {
            return List.of(Map.of("question", text, "header", stringValue(args.get("header")), "options",
                                            args.getOrDefault("options", List.of()), "multiple",
                                            Boolean.TRUE.equals(args.get("multiple"))));
        }
        return List.of();
    }
}
