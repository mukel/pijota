package ai.pijota.agent;

import ai.pijota.messages.Message;
import ai.pijota.messages.ToolCall;
import ai.pijota.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

public final class SessionManager {

    private static final int SESSION_VERSION = 3;
    private final Path sessionDir;

    // The current branch position in the tree. New appends create children of this entry.
    // null = root. Updated by append operations and load.
    private volatile String leafId;

    public SessionManager(Path sessionDir) { this.sessionDir = sessionDir; }

    // -- Session lifecycle --

    public Path createSession(String cwd) throws IOException {
        return createSession(cwd, null);
    }

    public Path createSession(String cwd, Path parentSession) throws IOException {
        leafId = null;
        String id = UUID.randomUUID().toString();
        Path file = resolveSessionPath(cwd, id);
        Files.createDirectories(file.getParent());
        writeHeader(file, id, null, cwd, parentSession);
        return file;
    }

    public Path forkSession(Path sourceFile, String targetEntryId, String cwd) throws IOException {
        String id = UUID.randomUUID().toString();
        Path file = resolveSessionPath(cwd, id);
        Files.createDirectories(file.getParent());
        writeHeader(file, id, null, cwd, sourceFile);
        var idToEntry = readAllEntries(sourceFile);
        copyBranch(idToEntry, targetEntryId, file);
        leafId = null;
        return file;
    }

    // -- Migration between providers / working directories --

    public Path migrate(Path sourceFile, String sourceLeafId, String targetCwd) throws IOException {
        if (!Files.exists(sourceFile)) throw new IOException("Source file not found: " + sourceFile);

        var idToEntry = readAllEntries(sourceFile);
        if (sourceLeafId != null && !idToEntry.containsKey(sourceLeafId)) {
            sourceLeafId = null;
        }

        String newId = UUID.randomUUID().toString();
        Path newFile = resolveSessionPath(targetCwd, newId);
        Files.createDirectories(newFile.getParent());
        writeHeader(newFile, newId, null, targetCwd, sourceFile);

        if (sourceLeafId != null) {
            copyBranch(idToEntry, sourceLeafId, newFile);
        } else {
            for (var e : idToEntry.values()) {
                Files.writeString(newFile, Util.MAPPER.writeValueAsString(e) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        }

        leafId = null;
        return newFile;
    }

    // -- Branching (in-place, within the same file) --

    public String leafId() { return leafId; }

    // -- Entry types --

    public void appendMessage(Path sessionFile, Message message) throws IOException {
        String parentId = leafId != null ? leafId : findLastEntryId(sessionFile);
        var entry = new LinkedHashMap<String, Object>();
        entry.put("id", message.id().toString());
        entry.put("parentId", parentId);
        entry.put("timestamp", timestampOf(message));
        entry.put("type", "message");
        serializeMessage(entry, message);
        Files.writeString(sessionFile, Util.MAPPER.writeValueAsString(entry) + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        leafId = message.id().toString();
    }

    public void appendCompaction(Path sessionFile, String summary, int compactedCount,
                                  String firstKeptEntryId) throws IOException {
        String parentId = leafId != null ? leafId : findLastEntryId(sessionFile);
        String entryId = UUID.randomUUID().toString();
        var entry = new LinkedHashMap<String, Object>();
        entry.put("id", entryId);
        entry.put("parentId", parentId);
        entry.put("timestamp", Instant.now().toString());
        entry.put("type", "compaction");
        entry.put("summary", summary);
        entry.put("compactedCount", compactedCount);
        if (firstKeptEntryId != null) entry.put("firstKeptEntryId", firstKeptEntryId);
        Files.writeString(sessionFile, Util.MAPPER.writeValueAsString(entry) + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        leafId = entryId;
    }

    public void appendSessionInfo(Path sessionFile, String name) throws IOException {
        String parentId = leafId != null ? leafId : findLastEntryId(sessionFile);
        var entry = new LinkedHashMap<String, Object>();
        entry.put("id", UUID.randomUUID().toString());
        entry.put("parentId", parentId);
        entry.put("timestamp", Instant.now().toString());
        entry.put("type", "session_info");
        entry.put("name", name == null ? "" : name.trim());
        Files.writeString(sessionFile, Util.MAPPER.writeValueAsString(entry) + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public String sessionName(Path sessionFile) throws IOException {
        String name = null;
        for (var entry : readAllEntries(sessionFile).values()) {
            if ("session_info".equals(entry.get("type"))) {
                String value = (String) entry.getOrDefault("name", "");
                name = value == null || value.isBlank() ? null : value.trim();
            }
        }
        return name;
    }

    public void saveMessages(Path file, List<Message> messages, String cwd) throws IOException {
        var sb = new StringBuilder();
        var header = new LinkedHashMap<String, Object>();
        header.put("type", "session");
        header.put("version", SESSION_VERSION);
        header.put("timestamp", Instant.now().toString());
        header.put("cwd", cwd);
        sb.append(Util.MAPPER.writeValueAsString(header)).append('\n');
        for (var msg : messages) {
            if (msg instanceof Message.System) continue;
            var entry = new LinkedHashMap<String, Object>();
            entry.put("id", msg.id().toString());
            entry.put("timestamp", timestampOf(msg));
            entry.put("type", "message");
            serializeMessage(entry, msg);
            sb.append(Util.MAPPER.writeValueAsString(entry)).append('\n');
        }
        Files.writeString(file, sb.toString());
    }

    private static String timestampOf(Message msg) {
        if (msg instanceof Message.User u) return u.timestamp().toString();
        if (msg instanceof Message.Assistant a) return a.timestamp().toString();
        if (msg instanceof Message.ToolResult tr) return tr.timestamp().toString();
        return Instant.now().toString();
    }

    // -- Reading (path-based context building) --

    public List<Message> loadMessages(Path sessionFile) throws IOException {
        if (!Files.exists(sessionFile)) return List.of();

        var idToEntry = readAllEntries(sessionFile);
        if (idToEntry.isEmpty()) return List.of();

        // Last entry in insertion order is the tip of the tree
        String lastId = null;
        for (var k : idToEntry.keySet()) lastId = k;

        // If leafId already set (by branch()), keep it if valid
        if (leafId == null || !idToEntry.containsKey(leafId)) {
            leafId = lastId;
        }

        return buildContext(idToEntry, leafId);
    }

    List<Message> buildContext(Map<String, Map<String, Object>> idToEntry, String leafId) {
        var entry = idToEntry.get(leafId);
        if (entry == null) return List.of();

        if (!entry.containsKey("parentId")) {
            return buildFlat(idToEntry);
        }

        var path = buildBranchPath(idToEntry, leafId);
        var compactionIdx = -1;
        for (int i = 0; i < path.size(); i++) {
            if ("compaction".equals(path.get(i).getOrDefault("type", "message"))) {
                compactionIdx = i;
            }
        }

        var messages = new ArrayList<Message>();
        if (compactionIdx >= 0) {
            var compaction = path.get(compactionIdx);
            String summary = (String) compaction.get("summary");
            if (summary != null && !summary.isBlank()) {
                messages.add(new Message.CompactionSummary(summary));
            }

            String firstKept = (String) compaction.get("firstKeptEntryId");
            var foundFirstKept = firstKept == null;
            for (int i = 0; i < compactionIdx; i++) {
                var pathEntry = path.get(i);
                if (!foundFirstKept && firstKept.equals(pathEntry.get("id"))) {
                    foundFirstKept = true;
                }
                if (foundFirstKept) appendContextMessage(messages, pathEntry);
            }
            for (int i = compactionIdx + 1; i < path.size(); i++) {
                appendContextMessage(messages, path.get(i));
            }
            return messages;
        }

        for (var pathEntry : path) appendContextMessage(messages, pathEntry);
        return messages;
    }

    private static void appendContextMessage(List<Message> messages, Map<String, Object> entry) {
        switch ((String) entry.getOrDefault("type", "message")) {
            case "branch_summary" -> {
                String summary = (String) entry.get("summary");
                if (summary != null && !summary.isBlank()) {
                    messages.add(new Message.BranchSummary(summary));
                }
            }
            case "message" -> {
                Message msg = deserializeEntry(entry);
                if (msg != null && !(msg instanceof Message.System)) {
                    messages.add(msg);
                }
            }
            default -> {}
        }
    }

    private static List<Map<String, Object>> buildBranchPath(
            Map<String, Map<String, Object>> idToEntry,
            String leafId) {
        var path = new ArrayList<Map<String, Object>>();
        var visited = new java.util.HashSet<String>();
        String currentId = leafId;

        while (currentId != null && visited.add(currentId)) {
            var entry = idToEntry.get(currentId);
            if (entry == null) break;
            path.add(entry);
            currentId = (String) entry.get("parentId");
        }
        java.util.Collections.reverse(path);
        return path;
    }

    private List<Message> buildFlat(Map<String, Map<String, Object>> idToEntry) {
        var messages = new ArrayList<Message>();
        for (var e : idToEntry.values()) {
            Message msg = deserializeEntry(e);
            if (msg != null && !(msg instanceof Message.System)) {
                messages.add(msg);
            }
        }
        return messages;
    }

    public List<Path> listSessions(String cwd) {
        try {
            Path dir = sessionDirForCwd(cwd);
            if (!Files.exists(dir)) return List.of();
            try (Stream<Path> stream = Files.list(dir)) {
                return stream.filter(p -> p.toString().endsWith(".jsonl"))
                    .sorted((a, b) -> {
                        try { return Files.getLastModifiedTime(b)
                                .compareTo(Files.getLastModifiedTime(a)); }
                        catch (IOException e) { return 0; }
                    }).toList();
            }
        } catch (IOException e) { return List.of(); }
    }

    public Path latestSession(String cwd, Path exclude) throws IOException {
        return listSessions(cwd).stream()
            .filter(path -> exclude == null || !path.equals(exclude))
            .findFirst()
            .orElseThrow(() -> new IOException("No previous session found"));
    }

    public Path resolveSession(String cwd, String spec) throws IOException {
        Path direct = Path.of(spec);
        if (Files.exists(direct)) return direct;

        Path dir = sessionDirForCwd(cwd);
        if (!Files.exists(dir)) throw new IOException("No sessions found for cwd");

        String prefix = spec.endsWith(".jsonl") ? spec.substring(0, spec.length() - 6) : spec;
        var matches = listSessions(cwd).stream()
            .filter(path -> path.getFileName().toString().replace(".jsonl", "").startsWith(prefix))
            .toList();
        if (matches.isEmpty()) throw new IOException("Session not found: " + spec);
        if (matches.size() > 1) throw new IOException("Ambiguous session id: " + spec);
        return matches.getFirst();
    }

    // -- Tree introspection (for /tree /fork commands) --

    public List<Map<String, Object>> getBranchEntries(Path file) throws IOException {
        var idToEntry = readAllEntries(file);
        return buildBranchPath(idToEntry, leafId);
    }

    public List<Map<String, Object>> getTreeEntries(Path file) throws IOException {
        return new ArrayList<>(readAllEntries(file).values());
    }

    private Map<String, Map<String, Object>> readAllEntries(Path file) throws IOException {
        var idToEntry = new LinkedHashMap<String, Map<String, Object>>();
        try (Stream<String> lines = Files.lines(file)) {
            lines.forEach(line -> {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> e = Util.MAPPER.readValue(line, Map.class);
                    String type = (String) e.get("type");
                    if ("session".equals(type)) return;
                    String id = (String) e.get("id");
                    if (id != null) idToEntry.put(id, e);
                } catch (Exception ignored) {}
            });
        }
        return idToEntry;
    }

    private void copyBranch(Map<String, Map<String, Object>> idToEntry, String leafId,
                            Path newFile) throws IOException {
        for (var entry : buildBranchPath(idToEntry, leafId)) {
            Files.writeString(newFile, Util.MAPPER.writeValueAsString(entry) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private Path sessionDirForCwd(String cwd) {
        String sanitized = cwd.replace('/', '_').replace('\\', '_');
        if (sanitized.startsWith("_")) sanitized = sanitized.substring(1);
        return sessionDir.resolve(sanitized);
    }

    private Path resolveSessionPath(String cwd, String id) {
        return sessionDirForCwd(cwd).resolve(id + ".jsonl");
    }

    private void writeHeader(Path file, String id, String parentEntryId, String cwd) throws IOException {
        writeHeader(file, id, parentEntryId, cwd, null);
    }

    private void writeHeader(Path file, String id, String parentEntryId, String cwd,
                             Path parentSession) throws IOException {
        var header = new LinkedHashMap<String, Object>();
        header.put("id", id);
        header.put("type", "session");
        header.put("version", SESSION_VERSION);
        header.put("timestamp", Instant.now().toString());
        header.put("cwd", cwd);
        if (parentEntryId != null) header.put("parentId", parentEntryId);
        if (parentSession != null) header.put("parentSession", parentSession.toString());
        Files.writeString(file, Util.MAPPER.writeValueAsString(header) + "\n");
    }

    private String findLastEntryId(Path sessionFile) {
        try {
            String lastLine = null;
            try (Stream<String> lines = Files.lines(sessionFile)) {
                var list = lines.toList();
                if (!list.isEmpty()) lastLine = list.get(list.size() - 1);
            }
            if (lastLine != null) {
                var node = Util.MAPPER.readTree(lastLine);
                if (node.has("id")) return node.get("id").asText();
            }
        } catch (IOException ignored) {}
        return null;
    }

    private static void serializeMessage(Map<String, Object> entry, Message msg) {
        switch (msg) {
            case Message.User u -> {
                entry.put("role", "user");
                entry.put("content", u.content());
            }
            case Message.Assistant a -> {
                entry.put("role", "assistant");
                entry.put("content", a.content());
                if (a.toolCalls() != null && !a.toolCalls().isEmpty()) {
                    entry.put("tool_calls", a.toolCalls().stream()
                        .map(tc -> Map.of("id", tc.id(), "name", tc.name(), "arguments", tc.arguments()))
                        .toList());
                }
                if (a.thinking() != null) entry.put("thinking", a.thinking());
            }
            case Message.ToolResult tr -> {
                entry.put("role", "tool_result");
                entry.put("tool_call_id", tr.toolCallId());
                entry.put("tool_name", tr.toolName());
                entry.put("content", tr.content());
                entry.put("is_error", tr.isError());
                if (tr.details() != null && !tr.details().isEmpty()) entry.put("details", tr.details());
            }
            case Message.System s -> {
                entry.put("role", "system");
                entry.put("content", s.content());
            }
            case Message.BranchSummary bs -> {
                entry.put("role", "user");
                entry.put("content", "[Branch context]\n" + bs.summary());
            }
            default -> {}
        }
    }

    @SuppressWarnings("unchecked")
    private static Message deserializeEntry(Map<String, Object> entry) {
        String type = (String) entry.getOrDefault("type", "message");
        if ("model_change".equals(type) || "thinking_level_change".equals(type)
            || "session_info".equals(type) || "compaction".equals(type) || "session".equals(type)
            || "branch_summary".equals(type)) {
            return null;
        }

        String role = (String) entry.getOrDefault("role", "user");
        UUID id = UUID.fromString((String) entry.getOrDefault("id", UUID.randomUUID().toString()));
        Instant ts = Instant.parse((String) entry.getOrDefault("timestamp", Instant.now().toString()));

        return switch (role) {
            case "user" -> {
                String content = (String) entry.getOrDefault("content", "");
                yield new Message.User(id, ts, content);
            }
            case "assistant" -> {
                String content = (String) entry.get("content");
                String thinking = (String) entry.get("thinking");
                List<ToolCall> tcs = List.of();
                Object tcObj = entry.get("tool_calls");
                if (tcObj instanceof List<?> list) {
                    tcs = list.stream()
                        .filter(item -> item instanceof Map)
                        .map(item -> {
                            Map<String, Object> m = (Map<String, Object>) item;
                            return new ToolCall(
                                (String) m.get("id"), (String) m.get("name"),
                                m.get("arguments") instanceof String s ? s
                                    : m.get("arguments") != null ? m.get("arguments").toString() : "{}");
                        }).toList();
                }
                yield new Message.Assistant(id, ts, content, tcs, thinking);
            }
            case "tool", "tool_result" -> {
                String tcId = (String) entry.get("tool_call_id");
                String tcName = (String) entry.get("tool_name");
                String content = (String) entry.get("content");
                boolean isError = Boolean.TRUE.equals(entry.get("is_error"));
                Map<String, Object> details = entry.get("details") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
                yield new Message.ToolResult(id, ts, tcId, tcName, content, isError, details);
            }
            case "system" -> {
                String content = (String) entry.getOrDefault("content", "");
                yield new Message.System(id, ts, content);
            }
            default -> {
                String content = (String) entry.getOrDefault("content", "");
                yield new Message.User(id, ts, content);
            }
        };
    }
}
