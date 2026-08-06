package org.pijota.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 30, unit = TimeUnit.SECONDS) // an unanswerable future is a bug to report, not a build to hang
class SlashCommandToolTest {

    /**
     * The question handler is process-wide, and surefire reuses forks, so a test that installed one and did not take it
     * back leaves this class asking a UI that will never answer. These tests want the no-handler path, so they say so
     * rather than inheriting whatever ran first.
     */
    @BeforeEach
    void noQuestionHandler() {
        SlashCommandTool.setQuestionHandler(null);
    }

    @Test
    void nameIsQuestion() {
        var tool = new SlashCommandTool();
        assertEquals("question", tool.name());
    }

    @Test
    void descriptionIsNotEmpty() {
        var tool = new SlashCommandTool();
        assertNotNull(tool.description());
        assertFalse(tool.description().isBlank());
    }

    @Test
    void executeNoQuestionsReturnsError() throws Exception {
        var tool = new SlashCommandTool();
        var result = tool.execute("call-1", new Tool.Args(Map.of()), "/cwd").get();
        assertTrue(result.isError());
        assertTrue(result.content().contains("No questions"));
    }

    @Test
    void executeSingleQuestionFormatsOutput() throws Exception {
        var tool = new SlashCommandTool();
        var result = tool.execute("call-1", new Tool.Args(Map.of("question", "what?")), "/cwd").get();
        assertFalse(result.isError());
        assertTrue(result.content().contains("Q1: what?"));
    }

    @Test
    void executeWithOptions() throws Exception {
        var tool = new SlashCommandTool();
        var options = List.of(Map.of("label", "yes", "description", "agree"),
                                        Map.of("label", "no", "description", "disagree"));
        var result = tool.execute("call-1", new Tool.Args(
                                        Map.of("question", "proceed?", "options", options, "header", "confirm")),
                                        "/cwd").get();
        assertTrue(result.content().contains("- yes: agree"));
        assertTrue(result.content().contains("- no: disagree"));
    }

    @Test
    void executeMultipleQuestions() throws Exception {
        var tool = new SlashCommandTool();
        var questions = List.of(Map.of("question", "q1?", "header", "h1"), Map.of("question", "q2?", "header", "h2"));
        var result = tool.execute("call-1", new Tool.Args(Map.of("questions", questions)), "/cwd").get();
        assertTrue(result.content().contains("Q1: q1?"));
        assertTrue(result.content().contains("Q2: q2?"));
    }

    @Test
    void executeWithHandlerCallsHandler() throws Exception {
        var tool = new SlashCommandTool();
        var handlerCalled = new CompletableFuture<String>();
        SlashCommandTool.setQuestionHandler(qs -> {
            handlerCalled.complete(qs.get(0).question());
            return CompletableFuture.completedFuture("answer text");
        });
        try {
            var result = tool.execute("call-1", new Tool.Args(Map.of("question", "handler q?")), "/cwd").get();
            assertFalse(result.isError());
            assertEquals("handler q?", handlerCalled.get());
            assertTrue(result.content().contains("answer text"));
        } finally {
            SlashCommandTool.setQuestionHandler(null);
        }
    }

    @Test
    void executeNonMapInQuestionsListReturnsError() throws Exception {
        var tool = new SlashCommandTool();
        var result = tool.execute("call-1", new Tool.Args(Map.of("questions", List.of("not-a-map"))), "/cwd").get();
        assertTrue(result.isError());
        assertTrue(result.content().contains("must be an object"));
    }

    @Test
    void executeMissingQuestionTextReturnsError() throws Exception {
        var tool = new SlashCommandTool();
        var result = tool.execute("call-1", new Tool.Args(Map.of("questions", List.of(Map.of("header", "h")))), "/cwd")
                                        .get();
        assertTrue(result.isError());
        assertTrue(result.content().contains("missing question text"));
    }

    @Test
    void executeTextAliasForQuestion() throws Exception {
        var tool = new SlashCommandTool();
        var result = tool.execute("call-1", new Tool.Args(Map.of("text", "alias question")), "/cwd").get();
        assertTrue(result.content().contains("alias question"));
    }

    @Test
    void defaultEnabledIsTrue() {
        assertTrue(new SlashCommandTool().defaultEnabled());
    }

    @Test
    void questionRecordEquality() {
        var a = new SlashCommandTool.Question("q", "h", List.of(), false);
        var b = new SlashCommandTool.Question("q", "h", List.of(), false);
        assertEquals(a, b);
    }

    @Test
    void optionRecordEquality() {
        var a = new SlashCommandTool.Option("yes", "agree");
        assertEquals(a, new SlashCommandTool.Option("yes", "agree"));
        assertNotEquals(a, new SlashCommandTool.Option("no", "agree"));
    }

    @Test
    void multipleFlagPreserved() throws Exception {
        var tool = new SlashCommandTool();
        var result = tool.execute("call-1", new Tool.Args(Map.of("question", "choose", "multiple", true)), "/cwd")
                                        .get();
        assertTrue(result.content().contains("Q1: choose"));
    }

    @Test
    void malformedOptionsAreSkipped() throws Exception {
        var tool = new SlashCommandTool();
        var options = List.of(Map.of("label", "ok"), "not-a-map");
        var result = tool.execute("call-1", new Tool.Args(Map.of("question", "q", "options", options)), "/cwd").get();
        assertTrue(result.content().contains("- ok:"));
    }

    @Test
    void textAliasInsideQuestions() throws Exception {
        var tool = new SlashCommandTool();
        var questions = List.of(Map.of("text", "alias?"));
        var result = tool.execute("call-1", new Tool.Args(Map.of("questions", questions)), "/cwd").get();
        assertTrue(result.content().contains("Q1: alias?"));
    }
}
