package ai.pijota.tools;

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

    private static volatile QuestionHandler questionHandler;

    public static void setQuestionHandler(QuestionHandler handler) {
        questionHandler = handler;
    }

    @Override
    public String name() {
        return "question";
    }

    @Override
    public String description() {
        return "Use this tool when you need to ask the user questions during execution. "
                                        + "This allows you to gather user preferences, clarify ambiguous instructions, "
                                        + "or get decisions on implementation choices. Prefer questions: [{question, header, options, multiple}].";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of("type", "object", "properties", Map.of("question", Map.of("type", "string", "description",
                                        "Single question to ask. Alias for questions[0].question"), "text",
                                        Map.of("type", "string", "description",
                                                                        "Single question to ask. Alias for question"),
                                        "header",
                                        Map.of("type", "string", "description",
                                                                        "Very short label for a single question"),
                                        "options",
                                        Map.of("type", "array", "description", "Options for a single question", "items",
                                                                        optionSchema()),
                                        "multiple",
                                        Map.of("type", "boolean", "description",
                                                                        "Allow selecting multiple options for a single question"),
                                        "questions",
                                        Map.of("type", "array", "description",
                                                                        "Questions to ask. Preferred form for one or more questions.",
                                                                        "items",
                                                                        Map.of("type", "object", "properties", Map.of(
                                                                                                        "question",
                                                                                                        Map.of("type", "string", "description",
                                                                                                                                        "Complete question"),
                                                                                                        "text",
                                                                                                        Map.of("type", "string", "description",
                                                                                                                                        "Alias for question"),
                                                                                                        "header",
                                                                                                        Map.of("type", "string", "description",
                                                                                                                                        "Very short label"),
                                                                                                        "options",
                                                                                                        Map.of("type", "array", "items", optionSchema()),
                                                                                                        "multiple",
                                                                                                        Map.of("type", "boolean")),
                                                                                                        "additionalProperties",
                                                                                                        false))),
                                        "additionalProperties", false);
    }

    @Override
    public CompletableFuture<Tool.ToolResult> execute(String toolCallId, Map<String, Object> args, String cwd) {
        try {
            List<?> questions = normalizeQuestions(args);
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
        return Map.of("type", "object", "properties", Map.of("label",
                                        Map.of("type", "string", "description", "Short option label"), "description",
                                        Map.of("type", "string", "description", "What this option means")), "required",
                                        List.of("label"));
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
