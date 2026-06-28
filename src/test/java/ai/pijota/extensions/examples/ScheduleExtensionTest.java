package ai.pijota.extensions.examples;

import ai.pijota.tools.Tool;

import ai.pijota.extensions.testkit.FakeExtensionAPI;
import ai.pijota.extensions.testkit.FakeExtensionRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    @TempDir
    Path tempDir;

    FakeExtensionAPI api() {
        var runtime = new FakeExtensionRuntime();
        runtime.setCwd(tempDir);
        return new FakeExtensionAPI(runtime);
    }

    @Test
    void initRegistersToolSlashCommandAndStatusLines() {
        var api = api();
        var ext = new ScheduleExtension();
        ext.init(api);
        assertEquals(1, api.tools().size());
        assertEquals("schedule_prompt", api.tools().get(0).name());
        assertNotNull(api.slashCommand("schedule-prompt"));
        assertEquals(1, api.statusLineSuppliers().size());
    }

    @Test
    void sessionReadyStartsSchedulerAndLoadsStorage() {
        var api = api();
        var ext = new ScheduleExtension();
        ext.init(api);
        assertDoesNotThrow(() -> ext.onReady(api.runtime()));
    }

    @Test
    void toolAddRequiresScheduleAndPrompt() throws Exception {
        var api = api();
        var ext = new ScheduleExtension();
        ext.init(api);
        var tool = api.tools().get(0);
        var result = tool.execute().execute("tc", new Tool.Args(Map.of("action", "add")), tempDir.toString()).get(1,
                                        TimeUnit.SECONDS);
        assertTrue(result.isError());
        assertTrue(result.content().contains("schedule and prompt are required"));
    }

    @Test
    void toolAddSuccess() throws Exception {
        var api = api();
        var ext = new ScheduleExtension();
        ext.init(api);
        var tool = api.tools().get(0);
        var result = tool.execute().execute("tc",
                                        new Tool.Args(Map.of("action", "add", "schedule", "+1h", "prompt", "hello",
                                                                        "name", "test-job")),
                                        tempDir.toString()).get(1, TimeUnit.SECONDS);
        assertFalse(result.isError());
        assertTrue(result.content().contains("Scheduled"));
    }

    @Test
    void toolEnableDisableUpdateCleanupAndUnknownActions() throws Exception {
        var api = api();
        var ext = new ScheduleExtension();
        ext.init(api);
        var tool = api.tools().get(0);
        tool.execute().execute("tc", new Tool.Args(
                                        Map.of("action", "add", "schedule", "+1h", "prompt", "p", "name", "j1")),
                                        tempDir.toString()).get(1, TimeUnit.SECONDS);

        assertFalse(tool.execute().execute("tc", new Tool.Args(Map.of("action", "disable", "job_id", "j1")),
                                        tempDir.toString()).get(1, TimeUnit.SECONDS).isError());
        assertFalse(tool.execute().execute("tc", new Tool.Args(Map.of("action", "enable", "job_id", "j1")),
                                        tempDir.toString()).get(1, TimeUnit.SECONDS).isError());
        assertFalse(tool.execute().execute("tc",
                                        new Tool.Args(Map.of("action", "update", "job_id", "j1", "schedule", "+2h")),
                                        tempDir.toString()).get(1, TimeUnit.SECONDS).isError());
        assertNotNull(tool.execute().execute("tc", new Tool.Args(Map.of("action", "cleanup")), tempDir.toString())
                                        .get(1, TimeUnit.SECONDS));
        assertTrue(tool.execute().execute("tc", new Tool.Args(Map.of("action", "bogus")), tempDir.toString())
                                        .get(1, TimeUnit.SECONDS).isError(), "unknown action → error");
    }

    @Test
    void toolAddRejectsDuplicateName() throws Exception {
        var api = api();
        var ext = new ScheduleExtension();
        ext.init(api);
        var tool = api.tools().get(0);
        Map<String, Object> args = Map.of("action", "add", "schedule", "+1h", "prompt", "hello", "name", "dup");
        tool.execute().execute("tc", new Tool.Args(args), tempDir.toString()).get(1, TimeUnit.SECONDS);
        var result = tool.execute().execute("tc", new Tool.Args(args), tempDir.toString()).get(1, TimeUnit.SECONDS);
        assertTrue(result.isError());
        assertTrue(result.content().contains("already exists"));
    }

    @Test
    void toolListEmpty() throws Exception {
        var api = api();
        var ext = new ScheduleExtension();
        ext.init(api);
        var tool = api.tools().get(0);
        var result = tool.execute().execute("tc", new Tool.Args(Map.of("action", "list")), tempDir.toString()).get(1,
                                        TimeUnit.SECONDS);
        assertFalse(result.isError());
        assertTrue(result.content().contains("No scheduled jobs"));
    }

    @Test
    void toolRemoveRequiresJobId() throws Exception {
        var api = api();
        var ext = new ScheduleExtension();
        ext.init(api);
        var tool = api.tools().get(0);
        var result = tool.execute().execute("tc", new Tool.Args(Map.of("action", "remove")), tempDir.toString()).get(1,
                                        TimeUnit.SECONDS);
        assertTrue(result.isError());
        assertTrue(result.content().contains("job_id is required"));
    }

    @Test
    void toolEnableDisableAndUpdate() throws Exception {
        var api = api();
        var ext = new ScheduleExtension();
        ext.init(api);
        var tool = api.tools().get(0);
        var add = tool.execute().execute("tc",
                                        new Tool.Args(Map.of("action", "add", "schedule", "+1h", "prompt", "hello",
                                                                        "name", "j")),
                                        tempDir.toString()).get(1, TimeUnit.SECONDS);
        String jobId = (String) add.details().get("job_id");

        var disable = tool.execute().execute("tc", new Tool.Args(Map.of("action", "disable", "job_id", jobId)),
                                        tempDir.toString()).get(1, TimeUnit.SECONDS);
        assertFalse(disable.isError());

        var enable = tool.execute().execute("tc", new Tool.Args(Map.of("action", "enable", "job_id", jobId)),
                                        tempDir.toString()).get(1, TimeUnit.SECONDS);
        assertFalse(enable.isError());

        var update = tool.execute().execute("tc",
                                        new Tool.Args(Map.of("action", "update", "job_id", jobId, "schedule", "+2h",
                                                                        "prompt", "updated")),
                                        tempDir.toString()).get(1, TimeUnit.SECONDS);
        assertFalse(update.isError());
    }

    @Test
    void toolCleanupRemovesDisabledJobs() throws Exception {
        var api = api();
        var ext = new ScheduleExtension();
        ext.init(api);
        var tool = api.tools().get(0);
        var add = tool.execute().execute("tc",
                                        new Tool.Args(Map.of("action", "add", "schedule", "+1h", "prompt", "hello",
                                                                        "name", "j")),
                                        tempDir.toString()).get(1, TimeUnit.SECONDS);
        String jobId = (String) add.details().get("job_id");
        tool.execute().execute("tc", new Tool.Args(Map.of("action", "disable", "job_id", jobId)), tempDir.toString())
                                        .get(1, TimeUnit.SECONDS);

        var cleanup = tool.execute().execute("tc", new Tool.Args(Map.of("action", "cleanup")), tempDir.toString())
                                        .get(1, TimeUnit.SECONDS);
        assertFalse(cleanup.isError());
        assertTrue(cleanup.content().contains("1"));

        var list = tool.execute().execute("tc", new Tool.Args(Map.of("action", "list")), tempDir.toString()).get(1,
                                        TimeUnit.SECONDS);
        assertTrue(list.content().contains("No scheduled jobs"));
    }

    @Test
    void toolUnknownActionReturnsError() throws Exception {
        var api = api();
        var ext = new ScheduleExtension();
        ext.init(api);
        var tool = api.tools().get(0);
        var result = tool.execute().execute("tc", new Tool.Args(Map.of("action", "bogus")), tempDir.toString()).get(1,
                                        TimeUnit.SECONDS);
        assertTrue(result.isError());
    }

    @Test
    void statusLineEmptyWhenNoJobs() {
        var api = api();
        var ext = new ScheduleExtension();
        ext.init(api);
        var lines = api.statusLineSuppliers().get(0).get();
        assertTrue(lines.isEmpty());
    }

    @Test
    void statusLineShowsJobCount() throws Exception {
        var api = api();
        var ext = new ScheduleExtension();
        ext.init(api);
        var tool = api.tools().get(0);
        tool.execute().execute("tc", new Tool.Args(
                                        Map.of("action", "add", "schedule", "+1h", "prompt", "hello", "name", "j1")),
                                        tempDir.toString()).get(1, TimeUnit.SECONDS);
        tool.execute().execute("tc", new Tool.Args(
                                        Map.of("action", "add", "schedule", "+2h", "prompt", "hello", "name", "j2")),
                                        tempDir.toString()).get(1, TimeUnit.SECONDS);
        var lines = api.statusLineSuppliers().get(0).get();
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("2 scheduled prompts"));
    }

    @Test
    void saveAndLoadJobs() throws Exception {
        var api = api();
        var ext = new ScheduleExtension();
        ext.init(api);
        ext.onReady(api.runtime());
        var tool = api.tools().get(0);
        Map<String, Object> addArgs = Map.of("action", "add", "schedule", "+1h", "prompt", "hello", "name", "persist");
        tool.execute().execute("tc", new Tool.Args(addArgs), tempDir.toString()).get(1, TimeUnit.SECONDS);

        var ext2 = new ScheduleExtension();
        ext2.init(api);
        ext2.onReady(api.runtime());
        var list = api.tools().get(0).execute()
                                        .execute("tc", new Tool.Args(Map.of("action", "list")), tempDir.toString())
                                        .get(1, TimeUnit.SECONDS);
        assertTrue(list.content().contains("persist"));
    }

    @Test
    void humanScheduleFormatsIntervalAndCommonCronShapes() {
        assertEquals("every 5m", ScheduleExtension.humanSchedule("5m", ScheduleExtension.JobType.interval));
        assertEquals("hourly", ScheduleExtension.humanSchedule("0 0 * * * *", ScheduleExtension.JobType.cron));
        assertEquals("daily at 9:00", ScheduleExtension.humanSchedule("0 0 9 * * *", ScheduleExtension.JobType.cron));
        assertEquals("every minute", ScheduleExtension.humanSchedule("0 * * * * *", ScheduleExtension.JobType.cron));
        assertEquals("every 5m", ScheduleExtension.humanSchedule("0 */5 * * * *", ScheduleExtension.JobType.cron));
        // an unrecognized cron shape (or wrong field count) falls back to the raw string
        assertEquals("0 0 12 * * 1", ScheduleExtension.humanSchedule("0 0 12 * * 1", ScheduleExtension.JobType.cron));
        assertEquals("weird", ScheduleExtension.humanSchedule("weird", ScheduleExtension.JobType.cron));
    }
}
