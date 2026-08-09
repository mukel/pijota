package org.pijota.agent;

import org.pijota.messages.Message;
import org.pijota.messages.ToolCall;
import org.pijota.messages.Usage;
import org.pijota.util.Util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

public final class SessionManager {
    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private static final int SESSION_VERSION = 3;
    private final Path sessionDir;

    // The current branch position in the tree. New appends create children of this entry; null = root. Updated by
    // append operations and load.
    // Invariant: this is instance-wide, not per-file, so all appends between two create/load/fork/branch calls MUST
    // target the same session file. Switching files always goes through loadContext/createSession, which reset it,
    // so leafId can never leak one file's leaf into another's parentId. Don't append to a second file without a reset.
    private volatile String leafId;

    // Per-file write lock, ensures append-only writes from concurrent threads
    // (e.g. assistant message + tool result from overlapping turns) don't interleave.
    private final ConcurrentHashMap<Path, ReentrantLock> writeLocks = new ConcurrentHashMap<>();

    public SessionManager(Path sessionDir) {
        this.sessionDir = sessionDir;
    }

    // -- Session lifecycle --

    public Path createSession(String cwd) throws IOException {
        return createSession(cwd, null);
    }

    public Path createSession(String cwd, Path parentSession) throws IOException {
        leafId = null;
        String id = UUID.randomUUID().toString();
        Path file = resolveSessionPath(cwd, id);
        Files.createDirectories(file.getParent());
        // Clean up stale .tmp files from crashed saveMessages calls
        try (var stream = Files.list(file.getParent())) {
            stream.filter(p -> p.getFileName().toString().endsWith(".tmp")).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
        writeHeader(file, id, null, cwd, parentSession);
        return file;
    }

    /**
     * Deletes a session file that holds no actual conversation: only a header and/or metadata (cache, model, etc.) but
     * no {@code message} or {@code compaction} entry. Used to avoid littering the sessions directory with the files
     * created on every launch that the user never typed into. No-op if the file is missing or has content.
     */
    public void deleteIfEmpty(Path sessionFile) {
        if (sessionFile == null || !Files.exists(sessionFile))
            return;
        try {
            lockAndWrite(sessionFile, () -> {
                boolean hasConversation;
                try (var lines = readLines(sessionFile)) {
                    hasConversation = lines.anyMatch(line -> {
                        try {
                            String type = Util.MAPPER.readTree(line).path("type").asText("");
                            return "message".equals(type) || "compaction".equals(type);
                        } catch (Exception e) {
                            return false;
                        }
                    });
                }
                if (!hasConversation)
                    Files.deleteIfExists(sessionFile);
            });
        } catch (IOException ignored) {
            // best effort, a session we cannot read is left in place
        }
        writeLocks.remove(sessionFile);
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
        if (!Files.exists(sourceFile))
            throw new IOException("Source file not found: " + sourceFile);

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
                Files.writeString(newFile, Util.MAPPER.writeValueAsString(e) + "\n", StandardOpenOption.CREATE,
                                                StandardOpenOption.APPEND);
            }
        }

        leafId = null;
        return newFile;
    }

    // -- Branching (in-place, within the same file) --

    /** The current branch leaf entry id. Package-private: session-tree position is internal to the agent layer. */
    String leafId() {
        return leafId;
    }

    // -- Entry types --

    /**
     * Durability stance: ordinary appends are process-crash durable only (the write has left the JVM when this returns;
     * no fsync promise against power loss). Two kinds pass {@code dsync=true}. Compaction, because losing it would
     * rebuild context from the pre-compaction entries and silently present stale context -- a wrong answer rather than
     * a missing one. And a user message, because it is the only entry nobody can reproduce: see
     * {@link #isUnreproducible}. A torn tail from a crash mid-append is repaired on the next live open.
     */
    private void appendEntry(Path sessionFile, String entryId, String type,
                                    Consumer<LinkedHashMap<String, Object>> fields, boolean dsync) throws IOException {
        lockAndWrite(sessionFile, () -> {
            String parentId = leafId != null ? leafId : findLastEntryId(sessionFile);
            var entry = new LinkedHashMap<String, Object>();
            entry.put("id", entryId);
            entry.put("parentId", parentId);
            entry.put("timestamp", Instant.now().toString());
            entry.put("type", type);
            fields.accept(entry);
            var opts = dsync
                                            ? new StandardOpenOption[] {StandardOpenOption.CREATE,
                                                    StandardOpenOption.APPEND, StandardOpenOption.DSYNC}
                                            : new StandardOpenOption[] {StandardOpenOption.CREATE,
                                                    StandardOpenOption.APPEND};
            Files.writeString(sessionFile, Util.MAPPER.writeValueAsString(entry) + "\n", opts);
            leafId = entryId;
        });
    }

    public void appendMessage(Path sessionFile, Message message) throws IOException {
        String entryId = message.id().toString();
        appendEntry(sessionFile, entryId, "message", entry -> {
            entry.put("timestamp", timestampOf(message)); // use message's real timestamp
            SessionCodec.serializeMessage(entry, message);
        }, isUnreproducible(message));
    }

    /**
     * Whether losing this message to a power cut would lose something that cannot be had again.
     *
     * <p>
     * What the user typed is the only thing here nobody can reconstruct: a reply can be asked for again, a tool result
     * can be re-run, a compaction is rebuilt from what it summarised. So the user's own words are worth an fsync and
     * the rest are not -- which matters because the rest are the frequent ones. An assistant message and a tool result
     * land several times a turn, on the turn thread; a typed message arrives at human speed, where a few milliseconds
     * of fsync is not observable.
     */
    static boolean isUnreproducible(Message message) {
        return message instanceof Message.User;
    }

    public void appendCompaction(Path sessionFile, String summary, int compactedCount, String firstKeptEntryId)
                                    throws IOException {
        appendCompaction(sessionFile, summary, compactedCount, firstKeptEntryId, null);
    }

    public void appendCompaction(Path sessionFile, String summary, int compactedCount, String firstKeptEntryId,
                                    Usage usage) throws IOException {
        // DSYNC: compaction is the durability gate. Losing it on crash would show stale context.
        appendEntry(sessionFile, UUID.randomUUID().toString(), "compaction", entry -> {
            entry.put("summary", summary);
            entry.put("compactedCount", compactedCount);
            if (firstKeptEntryId != null)
                entry.put("firstKeptEntryId", firstKeptEntryId);
            if (usage != null)
                entry.put("usage", SessionCodec.serializeUsage(usage));
        }, true);
    }

    public void appendSessionInfo(Path sessionFile, String name) throws IOException {
        appendEntry(sessionFile, UUID.randomUUID().toString(), "session_info",
                                        entry -> entry.put("name", name == null ? "" : name.trim()), false);
    }

    public void appendModelChange(Path sessionFile, String provider, String model, long contextWindow)
                                    throws IOException {
        appendEntry(sessionFile, UUID.randomUUID().toString(), "model_change", entry -> {
            entry.put("provider", provider == null ? "" : provider);
            entry.put("model", model == null ? "" : model);
            entry.put("contextWindow", contextWindow);
        }, false);
    }

    public void appendCacheMetadata(Path sessionFile, String systemPrompt, String toolManifestHash,
                                    String toolManifestJson, String provider, String model) throws IOException {
        appendEntry(sessionFile, UUID.randomUUID().toString(), "cache_metadata", entry -> {
            entry.put("systemPrompt", systemPrompt == null ? "" : systemPrompt);
            entry.put("toolManifestHash", toolManifestHash == null ? "" : toolManifestHash);
            entry.put("toolManifestJson", toolManifestJson == null ? "" : toolManifestJson);
            entry.put("provider", provider == null ? "" : provider);
            entry.put("model", model == null ? "" : model);
        }, false);
    }

    public void appendThinkingLevelChange(Path sessionFile, String level) throws IOException {
        appendEntry(sessionFile, UUID.randomUUID().toString(), "thinking_level_change",
                                        entry -> entry.put("level", level == null ? "OFF" : level), false);
    }

    public CacheMetadata latestCacheMetadata(Path sessionFile) throws IOException {
        return latestOfType(sessionFile, "cache_metadata", entry -> new CacheMetadata(
                                        (String) entry.getOrDefault("systemPrompt", ""),
                                        (String) entry.getOrDefault("toolManifestHash", ""),
                                        (String) entry.getOrDefault("toolManifestJson", ""),
                                        (String) entry.getOrDefault("provider", ""),
                                        (String) entry.getOrDefault("model", "")), false);
    }

    public ModelSelection latestModelSelection(Path sessionFile) throws IOException {
        return latestOfType(sessionFile, "model_change", entry -> {
            String provider = (String) entry.getOrDefault("provider", "");
            String model = (String) entry.getOrDefault("model", "");
            return provider.isBlank() || model.isBlank()
                                            ? null
                                            : new ModelSelection(provider, model, SessionUsage
                                                                            .asLong(entry.get("contextWindow")));
        }, true);
    }

    public String sessionName(Path sessionFile) throws IOException {
        return latestOfType(sessionFile, "session_info", entry -> {
            String value = (String) entry.getOrDefault("name", "");
            return value == null || value.isBlank() ? null : value.trim();
        }, false);
    }

    public String latestThinkingLevel(Path sessionFile) throws IOException {
        return latestOfType(sessionFile, "thinking_level_change", entry -> {
            String value = (String) entry.getOrDefault("level", "");
            return value.isBlank() ? null : value;
        }, true);
    }

    /**
     * The last entry of {@code type} in the current branch, mapped via {@code mapper}. When {@code mapper} yields null:
     * {@code keepPreviousOnNull=true} ignores that entry (keeps the prior value); false adopts the null (clears it).
     */
    private <T> T latestOfType(Path sessionFile, String type, Function<Map<String, Object>, T> mapper,
                                    boolean keepPreviousOnNull) throws IOException {
        if (sessionFile == null || !Files.exists(sessionFile))
            return null;
        T result = null;
        for (var entry : currentBranchEntries(sessionFile)) {
            if (type.equals(entry.get("type"))) {
                T mapped = mapper.apply(entry);
                if (mapped != null || !keepPreviousOnNull)
                    result = mapped;
            }
        }
        return result;
    }

    public void saveMessages(Path file, List<Message> messages, String cwd) throws IOException {
        var sb = new StringBuilder();
        var header = new LinkedHashMap<String, Object>();
        header.put("type", "session");
        header.put("version", SESSION_VERSION);
        header.put("timestamp", Instant.now().toString());
        header.put("cwd", cwd);
        sb.append(Util.MAPPER.writeValueAsString(header)).append('\n');
        String prevId = null;
        for (var msg : messages) {
            if (msg instanceof Message.System)
                continue;
            var entry = new LinkedHashMap<String, Object>();
            entry.put("id", msg.id().toString());
            if (prevId != null)
                entry.put("parentId", prevId);
            entry.put("timestamp", timestampOf(msg));
            SessionCodec.serializeMessage(entry, msg);
            sb.append(Util.MAPPER.writeValueAsString(entry)).append('\n');
            prevId = msg.id().toString();
        }

        // Atomic write: write to .tmp, fsync, then rename over the real file.
        // A crash mid-write leaves the original intact; a crash after rename
        // is fine (the tmp file may linger, cleaned up on next session create).
        Path tmpFile = file.resolveSibling(file.getFileName() + ".tmp");
        lockAndWrite(file, () -> {
            Files.writeString(tmpFile, sb.toString());
            // Force OS buffers to disk before the atomic rename
            try {
                Files.writeString(tmpFile, "", StandardOpenOption.APPEND, StandardOpenOption.DSYNC);
            } catch (IOException ignored) { /* best-effort fsync */ }
            Util.replaceFile(tmpFile, file);
        });
        leafId = prevId;
    }

    private static String timestampOf(Message msg) {
        if (msg instanceof Message.User u)
            return u.timestamp().toString();
        if (msg instanceof Message.Assistant a)
            return a.timestamp().toString();
        if (msg instanceof Message.ToolResult tr)
            return tr.timestamp().toString();
        return Instant.now().toString();
    }

    // -- Reading (path-based context building) --

    public List<Message> loadMessages(Path sessionFile) throws IOException {
        if (!Files.exists(sessionFile))
            return List.of();

        repairTornTail(sessionFile);
        var idToEntry = readAllEntries(sessionFile);
        if (idToEntry.isEmpty())
            return List.of();

        // Last entry in insertion order is the tip of the tree
        String lastId = null;
        for (var k : idToEntry.keySet())
            lastId = k;

        // If leafId already set (by branch()), keep it if valid
        if (leafId == null || !idToEntry.containsKey(leafId)) {
            leafId = lastId;
        }

        return buildContext(idToEntry, leafId);
    }

    public List<Message> branchTo(Path sessionFile, String entryId) throws IOException {
        var idToEntry = readAllEntries(sessionFile);
        if (!idToEntry.containsKey(entryId))
            throw new IOException("no session entry: " + entryId);
        leafId = entryId;
        return buildContext(idToEntry, leafId);
    }

    public List<Message> branchToRoot(Path sessionFile) throws IOException {
        if (!Files.exists(sessionFile))
            throw new IOException("No active session");
        leafId = null;
        return List.of();
    }

    List<Message> buildContext(Map<String, Map<String, Object>> idToEntry, String leafId) {
        var entry = idToEntry.get(leafId);
        if (entry == null)
            return List.of();

        if (!entry.containsKey("parentId")) {
            return reconcileToolCalls(buildFlat(idToEntry));
        }

        var path = buildBranchPath(idToEntry, leafId);
        var compactionIdx = -1;
        for (int i = 0; i < path.size(); i++) {
            if ("compaction".equals(SessionCodec.entryType(path.get(i)))) {
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
                if (foundFirstKept)
                    appendContextMessage(messages, pathEntry);
            }
            for (int i = compactionIdx + 1; i < path.size(); i++) {
                appendContextMessage(messages, path.get(i));
            }
            return reconcileToolCalls(messages);
        }

        for (var pathEntry : path)
            appendContextMessage(messages, pathEntry);
        return reconcileToolCalls(messages);
    }

    /**
     * A crash between a persisted assistant tool-call batch and its results leaves calls without answers, and providers
     * reject such context on the next request. Insert a synthetic error result for each unanswered call, directly after
     * its batch's surviving results (not at the tail: the user may have continued past the crash, and call/result
     * pairing must hold mid-context too). In-memory only, deliberately not persisted: the tree keeps the truth that
     * these calls never completed, and every load repairs identically.
     */
    static List<Message> reconcileToolCalls(List<Message> messages) {
        var answered = new HashSet<String>();
        for (var m : messages)
            if (m instanceof Message.ToolResult tr)
                answered.add(tr.toolCallId());
        var out = new ArrayList<Message>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            var m = messages.get(i);
            out.add(m);
            if (m instanceof Message.Assistant a && a.toolCalls() != null && !a.toolCalls().isEmpty()) {
                while (i + 1 < messages.size() && messages.get(i + 1) instanceof Message.ToolResult tr) {
                    out.add(tr); // the batch's surviving results stay first, in their recorded order
                    i++;
                }
                for (ToolCall tc : a.toolCalls())
                    if (!answered.contains(tc.id()))
                        out.add(new Message.ToolResult(UUID.randomUUID(), Instant.now(), tc.id(), tc.name(),
                                                        "[interrupted: the session ended before this tool finished]",
                                                        true, Map.of()));
            }
        }
        return out;
    }

    private static void appendContextMessage(List<Message> messages, Map<String, Object> entry) {
        switch (SessionCodec.entryType(entry)) {
            case "branch_summary" -> {
                String summary = (String) entry.get("summary");
                if (summary != null && !summary.isBlank()) {
                    messages.add(new Message.BranchSummary(summary));
                }
            }
            case "compaction" -> {}
            case "custom_message" -> {
                String customType = (String) entry.get("custom_type");
                String content = SessionCodec.entryContent(entry);
                if (customType != null && content != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> details = (Map<String, Object>) entry.get("details");
                    messages.add(new Message.Custom(customType, content, details));
                }
            }
            case "message" -> {
                Message msg = SessionCodec.deserializeEntry(entry);
                if (msg != null && !(msg instanceof Message.System)) {
                    messages.add(msg);
                }
            }
            default -> {}
        }
    }

    public static List<Map<String, Object>> buildBranchPath(Map<String, Map<String, Object>> idToEntry, String leafId) {
        var path = new ArrayList<Map<String, Object>>();
        var visited = new HashSet<String>();
        String currentId = leafId;

        while (currentId != null && visited.add(currentId)) {
            var entry = idToEntry.get(currentId);
            if (entry == null) {
                // Insurance, not a repair. An append-only file cannot grow a hole in its middle, and the one write
                // that could -- a torn tail swallowing the entry appended after it -- is repaired before every live
                // open. Measured across 153 real sessions, no file had a gap. So if this ever fires, that reasoning is
                // wrong somewhere, and the conversation handed to the model starts later than the user's did.
                log.warn("Session branch stops at parent {}, which is not in the file: {} entries reached, anything "
                                                + "older than the gap is absent from the context", currentId,
                                                path.size());
                break;
            }
            path.add(entry);
            currentId = (String) entry.get("parentId");
        }
        Collections.reverse(path);
        return path;
    }

    private List<Message> buildFlat(Map<String, Map<String, Object>> idToEntry) {
        var messages = new ArrayList<Message>();
        for (var e : idToEntry.values()) {
            Message msg = SessionCodec.deserializeEntry(e);
            if (msg != null && !(msg instanceof Message.System)) {
                messages.add(msg);
            }
        }
        return messages;
    }

    public List<Path> listSessions(String cwd) {
        try {
            Path dir = sessionDirForCwd(cwd);
            if (!Files.exists(dir))
                return List.of();
            try (Stream<Path> stream = Files.list(dir)) {
                return stream.filter(p -> p.toString().endsWith(".jsonl")).sorted((a, b) -> {
                    try {
                        return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                    } catch (IOException e) {
                        return 0;
                    }
                }).toList();
            }
        } catch (IOException e) {
            return List.of();
        }
    }

    public Path latestSession(String cwd, Path exclude) throws IOException {
        return listSessions(cwd).stream().filter(path -> exclude == null || !path.equals(exclude)).findFirst()
                                        .orElseThrow(() -> new IOException("No previous session found"));
    }

    public Path resolveSession(String cwd, String spec) throws IOException {
        Path direct = Path.of(spec);
        if (Files.exists(direct))
            return direct;

        Path dir = sessionDirForCwd(cwd);
        if (!Files.exists(dir))
            throw new IOException("No sessions found for cwd");

        String prefix = spec.endsWith(".jsonl") ? spec.substring(0, spec.length() - 6) : spec;
        var matches = listSessions(cwd).stream().filter(
                                        path -> path.getFileName().toString().replace(".jsonl", "").startsWith(prefix))
                                        .toList();
        if (matches.isEmpty())
            throw new IOException("Session not found: " + spec);
        if (matches.size() > 1)
            throw new IOException("Ambiguous session id: " + spec);
        return matches.getFirst();
    }

    // -- Tree introspection (for /tree /fork commands) --

    public List<Map<String, Object>> getBranchEntries(Path file) throws IOException {
        var idToEntry = readAllEntries(file);
        return buildBranchPath(idToEntry, leafId);
    }

    private List<Map<String, Object>> currentBranchEntries(Path file) throws IOException {
        var idToEntry = readAllEntries(file);
        if (idToEntry.isEmpty())
            return List.of();
        return buildBranchPath(idToEntry, effectiveLeaf(idToEntry, leafId));
    }

    /**
     * Which entry to walk a branch back from: the one named, or the last one written when that name is missing or
     * unknown.
     *
     * <p>
     * Falling back to the newest entry is what makes a session open at all when the recorded leaf is gone -- a torn
     * tail, a hand-edited file, an id from another session. The rule lived here and again in the exporter, spelled
     * slightly differently each time; the same rule twice is the kind that stays the same until one of them is fixed.
     * {@code idToEntry} is insertion-ordered, so the last key is the last entry written.
     */
    public static String effectiveLeaf(Map<String, Map<String, Object>> idToEntry, String leafId) {
        if (leafId != null && idToEntry.containsKey(leafId))
            return leafId;
        String last = null;
        for (var id : idToEntry.keySet())
            last = id;
        return last;
    }

    public List<Map<String, Object>> getTreeEntries(Path file) throws IOException {
        return new ArrayList<>(readAllEntries(file).values());
    }

    /**
     * Repairs the torn tail a crashed append can leave. Blind {@code APPEND} would concatenate the next entry onto a
     * partial final line, corrupting both entries and orphaning their children (silently truncated context), so every
     * live open repairs before any append can happen. A parseable unterminated tail lost only its newline: complete it.
     * Any other content after the last complete line was never acknowledged: drop it. A complete header line is never
     * touched, so a session whose header will not parse still loads leniently; an <em>unterminated</em> one is repaired
     * like any other torn tail, because the damage it does is to the next append rather than to this read. Deeper
     * damage is mid-file corruption, which {@link #readAllEntries} surfaces and skips.
     */
    private void repairTornTail(Path file) throws IOException {
        lockAndWrite(file, () -> {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0)
                return;
            int lastNl = -1;
            for (int i = bytes.length - 1; i >= 0; i--) {
                if (bytes[i] == '\n') {
                    lastNl = i;
                    break;
                }
            }
            String tail; // the bytes under repair: either an unterminated suffix or the last complete line
            int keep; // file length to truncate to when the tail is beyond saving
            if (lastNl < bytes.length - 1) {
                // Everything after the last newline, or the whole file when there is none. A file with no newline at
                // all is a header torn while the session was being created, and it is not a reading problem to be
                // left to the lenient loader: it is unterminated, so the next append lands on the same line and every
                // entry written from then on is lost with it. A partial header was never a usable session anyway.
                tail = new String(bytes, lastNl + 1, bytes.length - lastNl - 1, StandardCharsets.UTF_8);
                keep = lastNl + 1;
                if (parses(tail)) { // the crash cut exactly between the JSON and its newline: complete the line
                    Files.writeString(file, "\n", StandardOpenOption.APPEND);
                    log.warn("Completed torn session tail in {}", file.getFileName());
                    return;
                }
            } else {
                int prevNl = -1;
                for (int i = lastNl - 1; i >= 0; i--) {
                    if (bytes[i] == '\n') {
                        prevNl = i;
                        break;
                    }
                }
                if (prevNl == -1)
                    return; // the last complete line is the header; never touch it
                tail = new String(bytes, prevNl + 1, lastNl - prevNl - 1, StandardCharsets.UTF_8);
                keep = prevNl + 1;
                if (parses(tail))
                    return; // healthy file
            }
            try (var ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
                ch.truncate(keep);
            }
            log.warn("Dropped torn session tail in {} ({} bytes, never acknowledged)", file.getFileName(),
                                            bytes.length - keep);
        });
    }

    private static boolean parses(String line) {
        try {
            Util.MAPPER.readTree(line);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Session lines, with undecodable bytes replaced rather than thrown.
     *
     * <p>
     * {@code Files.lines} decodes UTF-8 with REPORT, so one bad byte anywhere in the file makes the stream throw
     * mid-iteration -- past every per-line guard, and the session simply would not load. A crash can leave a partial
     * multi-byte character, and a bad sector can leave anything at all; this is the crash-recovery path, so it has to
     * survive whatever it finds. A replaced byte turns its line into unparseable JSON, which the callers already skip.
     */
    static Stream<String> readLines(Path file) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                                        .onUnmappableCharacter(CodingErrorAction.REPLACE);
        var reader = new BufferedReader(new InputStreamReader(Files.newInputStream(file), decoder));
        return reader.lines().onClose(() -> {
            try {
                reader.close();
            } catch (IOException ignored) {}
        });
    }

    public static Map<String, Map<String, Object>> readAllEntries(Path file) throws IOException {
        var idToEntry = new LinkedHashMap<String, Map<String, Object>>();
        try (Stream<String> lines = readLines(file)) {
            lines.forEach(line -> {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> e = Util.MAPPER.readValue(line, Map.class);
                    String type = (String) e.get("type");
                    if ("session".equals(type))
                        return;
                    String id = (String) e.get("id");
                    if (id != null)
                        idToEntry.put(id, e);
                } catch (Exception ex) {
                    // Mid-file corruption (the torn TAIL is repaired before live opens). Load the valid prefix rather
                    // than refuse, but say so loudly: entries after a skipped line may reference a missing parent.
                    log.warn("Skipping unparseable session entry in {}: {}", file.getFileName(), ex.getMessage());
                }
            });
        }
        return idToEntry;
    }

    private void copyBranch(Map<String, Map<String, Object>> idToEntry, String leafId, Path newFile)
                                    throws IOException {
        for (var entry : buildBranchPath(idToEntry, leafId)) {
            Files.writeString(newFile, Util.MAPPER.writeValueAsString(entry) + "\n", StandardOpenOption.CREATE,
                                            StandardOpenOption.APPEND);
        }
    }

    private Path sessionDirForCwd(String cwd) {
        // Flatten a cwd into one directory name. Replace path separators AND the characters Windows forbids in file
        // names (: * ? " < > |) so e.g. "C:\proj" doesn't produce the illegal name "C:_proj".
        String sanitized = cwd.replaceAll("[/\\\\:*?\"<>|]", "_");
        if (sanitized.startsWith("_"))
            sanitized = sanitized.substring(1);
        return sessionDir.resolve(sanitized);
    }

    private Path resolveSessionPath(String cwd, String id) {
        return sessionDirForCwd(cwd).resolve(id + ".jsonl");
    }

    @FunctionalInterface
    private interface WriteAction {
        void run() throws IOException;
    }

    private void lockAndWrite(Path file, WriteAction action) throws IOException {
        var lock = writeLocks.computeIfAbsent(file, ignored -> new ReentrantLock());
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    private void writeHeader(Path file, String id, String parentEntryId, String cwd, Path parentSession)
                                    throws IOException {
        var header = new LinkedHashMap<String, Object>();
        header.put("id", id);
        header.put("type", "session");
        header.put("version", SESSION_VERSION);
        header.put("timestamp", Instant.now().toString());
        header.put("cwd", cwd);
        if (parentEntryId != null)
            header.put("parentId", parentEntryId);
        if (parentSession != null)
            header.put("parentSession", parentSession.toString());
        Files.writeString(file, Util.MAPPER.writeValueAsString(header) + "\n");
    }

    private String findLastEntryId(Path sessionFile) {
        try {
            String lastLine = null;
            try (var lines = readLines(sessionFile)) {
                lastLine = lines.reduce((ignored, b) -> b).orElse(null);
            }
            if (lastLine != null) {
                var node = Util.MAPPER.readTree(lastLine);
                if (node.has("id") && node.has("type") && !"session".equals(node.get("type").asText())) {
                    return node.get("id").asText();
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    public record CacheMetadata(String systemPrompt, String toolManifestHash, String toolManifestJson, String provider,
                                    String model) {
    }
}
