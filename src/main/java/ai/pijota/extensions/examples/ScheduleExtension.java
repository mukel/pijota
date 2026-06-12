package ai.pijota.extensions.examples;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.modes.SlashCommand;
import ai.pijota.tools.Tool;
import ai.pijota.util.Emojis;
import ai.pijota.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class ScheduleExtension implements Extension {

    private static final String STORAGE_FILE = ".pi/schedule-prompts.json";

    enum JobType {
        cron, once, interval
    }

    record ScheduledJob(String id, String name, JobType type, String schedule, String prompt, String description,
                                    boolean enabled, int runCount, long lastRunMs, long nextRunMs, String lastError,
                                    String sessionId) {
        ScheduledJob withEnabled(boolean e) {
            return new ScheduledJob(id, name, type, schedule, prompt, description, e, runCount, lastRunMs, nextRunMs,
                                            lastError, sessionId);
        }

        ScheduledJob withRun(long lastRun, long nextRun, String error) {
            return new ScheduledJob(id, name, type, schedule, prompt, description, enabled, runCount + 1, lastRun,
                                            nextRun, error, sessionId);
        }
    }

    private final ConcurrentHashMap<String, ScheduledJob> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private ExtensionAPI api;
    private Path storagePath;
    private String sessionId;

    @Override
    public String name() {
        return "schedule-prompt";
    }

    @Override
    public String description() {
        return "Schedule recurring and one-shot prompts with cron-like functionality";
    }

    @Override
    public void init(ExtensionAPI api) {
        this.api = api;
        this.sessionId = Util.shortUuid();

        api.registerTool(new Tool.ToolDefinition("schedule_prompt",
                                        "Schedule a prompt. Actions: add, remove, list, enable, disable, update, cleanup. Use job_id to target remove/enable/disable/update.",
                                        Map.of("type", "object", "properties", Map.of("action", Map.of("type", "string",
                                                                        "description",
                                                                        "add, remove, list, enable, disable, update, or cleanup"),
                                                                        "name",
                                                                        Map.of("type", "string", "description",
                                                                                                        "Job name (auto-generated if omitted on add)"),
                                                                        "schedule",
                                                                        Map.of("type", "string", "description",
                                                                                                        "Cron (6-field with seconds), interval (5m, 1h), relative time (+5m, +1h), or ISO timestamp"),
                                                                        "prompt",
                                                                        Map.of("type", "string", "description",
                                                                                                        "Prompt text to inject when the job fires"),
                                                                        "type",
                                                                        Map.of("type", "string", "description",
                                                                                                        "cron, once, or interval. Default cron. Use once for +5m relative times."),
                                                                        "job_id",
                                                                        Map.of("type", "string", "description",
                                                                                                        "Target job ID for remove/enable/disable/update"),
                                                                        "description",
                                                                        Map.of("type", "string", "description",
                                                                                                        "Free-form note")),
                                                                        "required", List.of("action")),
                                        (id, args, cwd) -> CompletableFuture.completedFuture(handleTool(args, cwd))));

        api.registerSlashCommand(new SlashCommand("schedule-prompt", "View or manage scheduled prompts", List.of(),
                                        this::handleSlash));
        api.registerStatusLines(this::statusLine);
        api.onSessionReady((_, _) -> start());
        api.onSessionShutdown((_, _) -> stop());
    }

    // ---- lifecycle ----

    private void start() {
        storagePath = api.runtime().cwd().resolve(STORAGE_FILE);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "pijota-scheduler");
            t.setDaemon(true);
            return t;
        });
        loadJobs();
    }

    private void stop() {
        if (scheduler != null)
            scheduler.shutdownNow();
        futures.values().forEach(f -> f.cancel(false));
        futures.clear();
        jobs.values().removeIf(j -> !j.enabled);
        saveJobs();
    }

    // ---- tool handler ----

    private Tool.ToolResult handleTool(Map<String, Object> args, String cwd) {
        String action = (String) args.get("action");
        if (action == null)
            return Tool.ToolResult.error("action is required");
        return switch (action) {
            case "add" -> doAdd(args);
            case "remove" -> doRemove(args);
            case "list" -> doList();
            case "enable" -> doEnable(args);
            case "disable" -> doDisable(args);
            case "update" -> doUpdate(args);
            case "cleanup" -> doCleanup();
            default -> Tool.ToolResult.error("Unknown action: " + action);
        };
    }

    private Tool.ToolResult doAdd(Map<String, Object> args) {
        String schedule = (String) args.get("schedule");
        String prompt = (String) args.get("prompt");
        if (schedule == null || schedule.isBlank() || prompt == null || prompt.isBlank())
            return Tool.ToolResult.error("schedule and prompt are required for add");

        String name = (String) args.get("name");
        if (name == null || name.isBlank())
            name = "job-" + Util.shortUuid(6);
        final String finalName = name;
        if (jobs.values().stream().anyMatch(j -> j.name.equals(finalName)))
            return Tool.ToolResult.error("Job name already exists: " + finalName);

        JobType type = parseType((String) args.get("type"), schedule);
        long nextRun = computeNextRun(schedule, type);
        if (nextRun < 0)
            return Tool.ToolResult.error("Invalid schedule: " + schedule);

        var job = new ScheduledJob(Util.shortUuid(12), finalName, type, schedule, prompt,
                                        (String) args.get("description"), true, 0, 0, nextRun, null, sessionId);
        jobs.put(job.id, job);
        scheduleJob(job);
        saveJobs();
        return Tool.ToolResult.success("Scheduled: " + finalName + " · " + humanSchedule(schedule, type),
                                        Map.of("job_id", job.id, "next_run_ms", nextRun));
    }

    private Tool.ToolResult doRemove(Map<String, Object> args) {
        String jobId = (String) args.get("job_id");
        if (jobId == null)
            return Tool.ToolResult.error("job_id is required for remove");
        var job = jobs.remove(jobId);
        if (job == null)
            return Tool.ToolResult.error("Job not found: " + jobId);
        cancelFuture(jobId);
        saveJobs();
        return Tool.ToolResult.success("Removed: " + job.name);
    }

    private Tool.ToolResult doList() {
        if (jobs.isEmpty())
            return Tool.ToolResult.success("No scheduled jobs");
        var sb = new StringBuilder();
        jobs.values().stream().sorted(Comparator.comparingLong(j -> j.nextRunMs)).forEach(j -> sb.append(String.format(
                                        "%s %s · %s · %s · %s · %d runs\n", j.enabled ? "✓" : "✗", j.name,
                                        humanSchedule(j.schedule, j.type),
                                        j.nextRunMs > 0 ? "next " + Util.formatRelative(j.nextRunMs) : "",
                                        j.lastRunMs > 0 ? "last " + Util.formatRelative(j.lastRunMs) : "",
                                        j.runCount)));
        return Tool.ToolResult.success(sb.toString().strip());
    }

    private Tool.ToolResult doEnable(Map<String, Object> args) {
        String jobId = (String) args.get("job_id");
        if (jobId == null)
            return Tool.ToolResult.error("job_id is required");
        var job = jobs.get(jobId);
        if (job == null)
            return Tool.ToolResult.error("Job not found: " + jobId);
        if (job.enabled)
            return Tool.ToolResult.success("Already enabled: " + job.name);
        var updated = job.withEnabled(true);
        jobs.put(jobId, updated);
        scheduleJob(updated);
        saveJobs();
        return Tool.ToolResult.success("Enabled: " + job.name);
    }

    private Tool.ToolResult doDisable(Map<String, Object> args) {
        String jobId = (String) args.get("job_id");
        if (jobId == null)
            return Tool.ToolResult.error("job_id is required");
        var job = jobs.get(jobId);
        if (job == null)
            return Tool.ToolResult.error("Job not found: " + jobId);
        if (!job.enabled)
            return Tool.ToolResult.success("Already disabled: " + job.name);
        cancelFuture(jobId);
        jobs.put(jobId, job.withEnabled(false));
        saveJobs();
        return Tool.ToolResult.success("Disabled: " + job.name);
    }

    private Tool.ToolResult doUpdate(Map<String, Object> args) {
        String jobId = (String) args.get("job_id");
        if (jobId == null)
            return Tool.ToolResult.error("job_id is required");
        var job = jobs.get(jobId);
        if (job == null)
            return Tool.ToolResult.error("Job not found: " + jobId);
        String newSchedule = (String) args.get("schedule");
        String newPrompt = (String) args.get("prompt");
        if (newSchedule != null)
            job = new ScheduledJob(job.id, job.name, job.type, newSchedule, newPrompt != null ? newPrompt : job.prompt,
                                            job.description, job.enabled, job.runCount, job.lastRunMs, job.nextRunMs,
                                            job.lastError, job.sessionId);
        jobs.put(jobId, job);
        if (job.enabled) {
            cancelFuture(jobId);
            scheduleJob(job);
        }
        saveJobs();
        return Tool.ToolResult.success("Updated: " + job.name);
    }

    private Tool.ToolResult doCleanup() {
        var removedIds = new ArrayList<String>();
        int count = 0;
        var it = jobs.values().iterator();
        while (it.hasNext()) {
            var j = it.next();
            if (!j.enabled) {
                removedIds.add(j.id);
                it.remove();
                count++;
            }
        }
        removedIds.forEach(this::cancelFuture);
        saveJobs();
        return Tool.ToolResult.success("Cleaned up " + count + " disabled jobs");
    }

    // ---- scheduling ----

    private void scheduleJob(ScheduledJob job) {
        if (!job.enabled || scheduler == null)
            return;
        long delay = job.nextRunMs - System.currentTimeMillis();
        if (delay < 0)
            delay = 0;
        Runnable run = () -> executeJob(job);
        ScheduledFuture<?> future = switch (job.type) {
            case once -> scheduler.schedule(run, delay, TimeUnit.MILLISECONDS);
            case interval ->
                scheduler.scheduleAtFixedRate(run, delay, parseInterval(job.schedule), TimeUnit.MILLISECONDS);
            case cron -> scheduler.schedule(run, delay, TimeUnit.MILLISECONDS);
        };
        futures.put(job.id, future);
    }

    private void executeJob(ScheduledJob job) {
        if (!job.enabled)
            return;
        long now = System.currentTimeMillis();
        api.sendUserMessage(job.prompt);
        long nextRun = job.type == JobType.cron
                                        ? computeNextRun(job.schedule, JobType.cron)
                                        : job.type == JobType.interval ? now + parseInterval(job.schedule) : 0;
        jobs.put(job.id, job.withRun(now, nextRun, null));
        if (job.type == JobType.cron && jobs.get(job.id).enabled) {
            cancelFuture(job.id);
            scheduleJob(jobs.get(job.id));
        }
        saveJobs();
    }

    private void cancelFuture(String jobId) {
        var f = futures.remove(jobId);
        if (f != null)
            f.cancel(false);
    }

    // ---- storage ----

    private void loadJobs() {
        if (storagePath == null || !Files.exists(storagePath))
            return;
        try {
            String json = Files.readString(storagePath);
            if (json.isBlank())
                return;
            var arr = Util.MAPPER.readTree(json);
            if (!arr.isArray())
                return;
            for (var node : arr) {
                String typeStr = node.path("type").asText();
                String schedule = node.path("schedule").asText();
                JobType parsedType = parseType(typeStr, schedule);
                var job = new ScheduledJob(node.path("id").asText(), node.path("name").asText(), parsedType, schedule,
                                                node.path("prompt").asText(), node.path("description").asText(""),
                                                node.path("enabled").asBoolean(true), node.path("runCount").asInt(0),
                                                node.path("lastRunMs").asLong(0), computeNextRun(schedule, parsedType),
                                                node.path("lastError").asText(""), node.path("sessionId").asText(""));
                jobs.put(job.id, job);
                if (job.enabled)
                    scheduleJob(job);
            }
        } catch (IOException e) {
            api.runtime().setStatus("Schedule: failed to load jobs");
        }
    }

    private void saveJobs() {
        if (storagePath == null)
            return;
        try {
            var arr = Util.MAPPER.createArrayNode();
            for (var job : jobs.values()) {
                arr.add(Util.MAPPER.createObjectNode().put("id", job.id).put("name", job.name)
                                                .put("type", job.type.name()).put("schedule", job.schedule)
                                                .put("prompt", job.prompt).put("description", job.description)
                                                .put("enabled", job.enabled).put("runCount", job.runCount)
                                                .put("lastRunMs", job.lastRunMs).put("nextRunMs", job.nextRunMs)
                                                .put("sessionId", job.sessionId != null ? job.sessionId : ""));
            }
            Files.writeString(storagePath, Util.MAPPER.writeValueAsString(arr));
        } catch (IOException ignored) {}
    }

    // ---- slash command ----

    private void handleSlash(String args) {
        api.runtime().setStatus(doList().content());
    }

    private List<String> statusLine() {
        long count = jobs.values().stream().filter(j -> j.enabled).count();
        return count == 0
                                        ? List.of()
                                        : List.of(String.format("%s %d scheduled prompt%s", Emojis.CLOCK, count,
                                                                        count == 1 ? "" : "s"));
    }

    // ---- schedule parsing ----

    static long computeNextRun(String schedule, JobType type) {
        return switch (type) {
            case once -> parseRelativeOrIso(schedule);
            case interval -> System.currentTimeMillis() + parseInterval(schedule);
            case cron -> nextCronTime(schedule);
        };
    }

    private static long parseRelativeOrIso(String s) {
        String trimmed = s.trim();
        if (trimmed.startsWith("+"))
            return System.currentTimeMillis() + parseDuration(trimmed.substring(1));
        try {
            return Instant.parse(trimmed).toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis() + parseDuration(trimmed);
        }
    }

    static long parseDuration(String s) {
        s = s.trim();
        if (s.isEmpty())
            return 0;
        char unit = s.charAt(s.length() - 1);
        long value;
        try {
            value = Long.parseLong(s.substring(0, s.length() - 1));
        } catch (NumberFormatException e) {
            return 0;
        }
        return switch (unit) {
            case 's' -> value * 1000;
            case 'm' -> value * 60_000;
            case 'h' -> value * 3600_000;
            case 'd' -> value * 86_400_000;
            default -> 0;
        };
    }

    static long parseInterval(String s) {
        return parseDuration(s);
    }

    private static long nextCronTime(String cron) {
        String[] fields = cron.trim().split("\\s+");
        if (fields.length < 6)
            return -1;
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        ZonedDateTime next = now.withNano(0).plusSeconds(1);
        for (int i = 0; i < 366 * 24 * 60 * 60; i++) {
            if (matchesCron(fields, next))
                return next.toInstant().toEpochMilli();
            next = next.plusSeconds(1);
        }
        return -1;
    }

    private static boolean matchesCron(String[] fields, ZonedDateTime dt) {
        return matchField(fields[0], dt.getSecond(), 0, 59) && matchField(fields[1], dt.getMinute(), 0, 59)
                                        && matchField(fields[2], dt.getHour(), 0, 23)
                                        && matchField(fields[3], dt.getDayOfMonth(), 1, 31)
                                        && matchField(fields[4], dt.getMonthValue(), 1, 12)
                                        && matchField(fields[5], dt.getDayOfWeek().getValue() % 7, 0, 6);
    }

    static boolean matchField(String field, int value, int min, int max) {
        if ("*".equals(field))
            return true;
        for (String part : field.split(",")) {
            if (part.startsWith("*/")) {
                if ((value - min) % Integer.parseInt(part.substring(2)) == 0)
                    return true;
            } else if (part.contains("-")) {
                String[] r = part.split("-");
                if (value >= Integer.parseInt(r[0]) && value <= Integer.parseInt(r[1]))
                    return true;
            } else if (part.contains("/")) {
                String[] p = part.split("/");
                int b = "*".equals(p[0]) ? min : Integer.parseInt(p[0]);
                if (value >= b && (value - b) % Integer.parseInt(p[1]) == 0)
                    return true;
            } else if (part.matches("\\d+") && Integer.parseInt(part) == value)
                return true;
        }
        return false;
    }

    static JobType parseType(String typeStr, String schedule) {
        if (typeStr != null) {
            try {
                return JobType.valueOf(typeStr);
            } catch (IllegalArgumentException ignored) {}
        }
        if (schedule.startsWith("+"))
            return JobType.once;
        if (schedule.matches("\\d+[smhd]"))
            return JobType.interval;
        if (schedule.contains("T") || schedule.contains(":"))
            return JobType.once;
        return JobType.cron;
    }

    private static String humanSchedule(String schedule, JobType type) {
        return switch (type) {
            case once -> Util.formatRelative(parseRelativeOrIso(schedule));
            case interval -> "every " + schedule;
            case cron -> {
                String[] f = schedule.trim().split("\\s+");
                if (f.length != 6)
                    yield schedule;
                if (f[0].equals("0") && f[1].equals("0") && f[4].equals("*") && f[5].equals("*")) {
                    if (f[2].equals("*") && f[3].equals("*"))
                        yield "hourly";
                    if (f[3].equals("*"))
                        yield "daily at " + f[2] + ":00";
                }
                if (f[0].equals("0") && f[2].equals("*") && f[3].equals("*") && f[4].equals("*") && f[5].equals("*")) {
                    if (f[1].equals("*"))
                        yield "every minute";
                    yield "every " + (f[1].startsWith("*/") ? f[1].substring(2) : f[1]) + "m";
                }
                yield schedule;
            }
        };
    }
}
