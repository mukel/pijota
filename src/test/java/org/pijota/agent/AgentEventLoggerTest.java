package org.pijota.agent;

import org.pijota.messages.Message;
import org.pijota.messages.Usage;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class AgentEventLoggerTest {

    /** True if some line contains all of {@code parts} — keeps the assertions short and readable. */
    private static boolean has(List<String> lines, String... parts) {
        return lines.stream().anyMatch(line -> Arrays.stream(parts).allMatch(line::contains));
    }

    @Test
    void tracesTheEventStreamAtTheRightLevels() {
        var logger = (Logger) LoggerFactory.getLogger("org.pijota.agent.events");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        Level saved = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.TRACE);
        try {
            var sut = new AgentEventLogger();
            sut.onEvent(new AgentEvent.TurnStart(1));
            sut.onEvent(new AgentEvent.ToolStart("bash", "t1", () -> "{\"command\":\"ls\"}"));
            sut.onEvent(new AgentEvent.ToolResult(new Message.ToolResult("t1", "bash", "the output", false)));
            sut.onEvent(new AgentEvent.MessageStreamDelta("hello"));
            sut.onEvent(new AgentEvent.Error(new RuntimeException("boom")));
            sut.onEvent(new AgentEvent.TurnEnd(1));

            var lines = appender.list.stream().map(e -> e.getLevel() + " " + e.getFormattedMessage()).toList();
            assertTrue(has(lines, "INFO", "turn 1", "start"), lines.toString());
            assertTrue(has(lines, "INFO", "tool ▶ bash"), lines.toString());
            assertTrue(has(lines, "INFO", "tool ■ bash", "ok"), lines.toString());
            assertTrue(has(lines, "TRACE", "delta"), lines.toString());
            assertTrue(has(lines, "ERROR", "boom"), lines.toString());
        } finally {
            logger.setLevel(saved);
            logger.detachAppender(appender);
        }
    }

    @Test
    void onlyTheFinalAssistantMessageWithUsageLogsAtInfo() {
        var logger = (Logger) LoggerFactory.getLogger("org.pijota.agent.events");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        Level saved = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.TRACE);
        try {
            var sut = new AgentEventLogger();
            // per-chunk streaming update (null usage) — must NOT flood INFO
            sut.onEvent(new AgentEvent.AssistantMessage(new Message.Assistant("partial", List.of(), null), null));
            // final completion (has usage) — the one INFO line worth keeping
            sut.onEvent(new AgentEvent.AssistantMessage(new Message.Assistant("done", List.of(), null),
                                            new Usage(1500, 42)));

            var infoMsgs = appender.list.stream().filter(e -> e.getLevel() == Level.INFO)
                                            .map(ILoggingEvent::getFormattedMessage).toList();
            assertEquals(1, infoMsgs.size(), "one INFO line — the final completion, not the streaming update");
            assertTrue(infoMsgs.getFirst().contains("in=1500 out=42"), infoMsgs.toString());
        } finally {
            logger.setLevel(saved);
            logger.detachAppender(appender);
        }
    }

    @Test
    void toolArgumentsAreScrubbedAndFailuresNeverThrow() {
        var logger = (Logger) LoggerFactory.getLogger("org.pijota.agent.events");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        Level saved = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            var sut = new AgentEventLogger();
            // a throwing args supplier must never break logging (or the turn)
            assertDoesNotThrow(() -> sut.onEvent(new AgentEvent.ToolStart("bash", "t1", () -> {
                throw new IllegalStateException("nope");
            })));
            assertTrue(appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("args unavailable")),
                                            appender.list.toString());
        } finally {
            logger.setLevel(saved);
            logger.detachAppender(appender);
        }
    }
}
