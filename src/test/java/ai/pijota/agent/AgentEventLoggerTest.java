package ai.pijota.agent;

import ai.pijota.messages.Message;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class AgentEventLoggerTest {

    @Test
    void tracesTheEventStreamAtTheRightLevels() {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("ai.pijota.agent.events");
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
            assertTrue(lines.stream().anyMatch(m -> m.contains("INFO") && m.contains("turn 1") && m.contains("start")),
                                            lines.toString());
            assertTrue(lines.stream().anyMatch(m -> m.contains("INFO") && m.contains("tool ▶ bash")), lines.toString());
            assertTrue(lines.stream().anyMatch(m -> m.contains("INFO") && m.contains("tool ■ bash")
                                            && m.contains("ok")), lines.toString());
            assertTrue(lines.stream().anyMatch(m -> m.contains("TRACE") && m.contains("delta")), lines.toString());
            assertTrue(lines.stream().anyMatch(m -> m.contains("ERROR") && m.contains("boom")), lines.toString());
        } finally {
            logger.setLevel(saved);
            logger.detachAppender(appender);
        }
    }

    @Test
    void toolArgumentsAreScrubbedAndFailuresNeverThrow() {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("ai.pijota.agent.events");
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
