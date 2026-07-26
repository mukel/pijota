package org.pijota.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamProcessorTest {
    @Test
    void stripsInlineSystemReminderWithoutDroppingVisibleText() {
        var processor = new StreamProcessor();

        var cleaned = processor.stripSystemReminders("before <system-reminder>hidden</system-reminder> after");

        assertEquals("before  after", cleaned);
    }

    @Test
    void suppressesSystemReminderAcrossLinesUntilClosingTag() {
        var processor = new StreamProcessor();

        assertEquals("visible", processor.stripSystemReminders("visible<system-reminder>hidden"));
        assertEquals("", processor.stripSystemReminders("still hidden"));
        assertEquals("shown", processor.stripSystemReminders("</system-reminder>shown"));
    }

    @Test
    void resetStopsCrossLineSuppression() {
        var processor = new StreamProcessor();

        processor.stripSystemReminders("<system-reminder>hidden");
        processor.reset();

        assertEquals("visible", processor.stripSystemReminders("visible"));
    }

    @Test
    void filtersReasoningMetaLinesButKeepsRegularContent() {
        var processor = new StreamProcessor();

        assertNull(processor.stripAndFilter("The user wants a plan"));
        assertNull(processor.stripAndFilter("Let me inspect the files"));
        assertEquals("Here is the result", processor.stripAndFilter("Here is the result"));
    }
}
