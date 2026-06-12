package ai.pijota.extensions.examples;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleExtensionTest {

    @Test
    void parseDurationSeconds() {
        assertEquals(30_000, ScheduleExtension.parseDuration("30s"));
    }

    @Test
    void parseDurationMinutes() {
        assertEquals(300_000, ScheduleExtension.parseDuration("5m"));
    }

    @Test
    void parseDurationHours() {
        assertEquals(7_200_000, ScheduleExtension.parseDuration("2h"));
    }

    @Test
    void parseDurationDays() {
        assertEquals(86_400_000, ScheduleExtension.parseDuration("1d"));
    }

    @Test
    void parseDurationEmpty() {
        assertEquals(0, ScheduleExtension.parseDuration(""));
    }

    @Test
    void parseDurationInvalidNumber() {
        assertEquals(0, ScheduleExtension.parseDuration("xms"));
    }

    @Test
    void parseDurationUnknownUnit() {
        assertEquals(0, ScheduleExtension.parseDuration("5x"));
    }

    @Test
    void parseDurationZero() {
        assertEquals(0, ScheduleExtension.parseDuration("0m"));
    }

    @Test
    void parseIntervalDelegatesToParseDuration() {
        assertEquals(120_000, ScheduleExtension.parseInterval("2m"));
    }

    @Test
    void computeNextRunOnce() {
        var next = ScheduleExtension.computeNextRun("+5m", ScheduleExtension.JobType.once);
        assertTrue(next > System.currentTimeMillis());
    }

    @Test
    void computeNextRunInterval() {
        var next = ScheduleExtension.computeNextRun("10m", ScheduleExtension.JobType.interval);
        var expected = System.currentTimeMillis() + 600_000;
        assertTrue(Math.abs(next - expected) < 1000);
    }

    @Test
    void computeNextRunCronReturnsFuture() {
        var next = ScheduleExtension.computeNextRun("0 0 * * * *", ScheduleExtension.JobType.cron);
        assertTrue(next > System.currentTimeMillis() || next == -1);
    }

    @Test
    void parseTypeWithExplicitType() {
        assertEquals(ScheduleExtension.JobType.once, ScheduleExtension.parseType("once", "+5m"));
        assertEquals(ScheduleExtension.JobType.interval, ScheduleExtension.parseType("interval", "10m"));
        assertEquals(ScheduleExtension.JobType.cron, ScheduleExtension.parseType("cron", "0 0 * * * *"));
    }

    @Test
    void parseTypeInfersFromSchedulePlus() {
        assertEquals(ScheduleExtension.JobType.once, ScheduleExtension.parseType(null, "+1h"));
    }

    @Test
    void parseTypeInfersFromScheduleDuration() {
        assertEquals(ScheduleExtension.JobType.interval, ScheduleExtension.parseType(null, "5m"));
    }

    @Test
    void parseTypeInfersFromScheduleIso() {
        assertEquals(ScheduleExtension.JobType.once, ScheduleExtension.parseType(null, "2025-01-15T10:30:00Z"));
    }

    @Test
    void parseTypeDefaultsToCron() {
        assertEquals(ScheduleExtension.JobType.cron, ScheduleExtension.parseType(null, "0 0 * * * *"));
    }

    @Test
    void matchFieldWildcard() {
        assertTrue(ScheduleExtension.matchField("*", 42, 0, 59));
    }

    @Test
    void matchFieldExactValue() {
        assertTrue(ScheduleExtension.matchField("15", 15, 0, 59));
        assertFalse(ScheduleExtension.matchField("15", 20, 0, 59));
    }

    @Test
    void matchFieldStep() {
        assertTrue(ScheduleExtension.matchField("*/5", 10, 0, 59));
        assertFalse(ScheduleExtension.matchField("*/5", 11, 0, 59));
        assertTrue(ScheduleExtension.matchField("*/5", 0, 0, 59));
    }

    @Test
    void matchFieldRange() {
        assertTrue(ScheduleExtension.matchField("10-20", 15, 0, 59));
        assertFalse(ScheduleExtension.matchField("10-20", 5, 0, 59));
    }

    @Test
    void matchFieldList() {
        assertTrue(ScheduleExtension.matchField("1,15,30", 15, 0, 59));
        assertFalse(ScheduleExtension.matchField("1,15,30", 20, 0, 59));
    }

    @Test
    void matchFieldStepWithStart() {
        assertTrue(ScheduleExtension.matchField("5/10", 15, 0, 59));
        assertFalse(ScheduleExtension.matchField("5/10", 10, 0, 59));
    }
}
