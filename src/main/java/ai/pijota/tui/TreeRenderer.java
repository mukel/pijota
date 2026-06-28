package ai.pijota.tui;

import ai.pijota.agent.SessionDescriptor;
import ai.pijota.theme.AnsiTheme;
import ai.pijota.util.Util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

final class TreeRenderer {
    private final AnsiTheme theme;

    TreeRenderer(AnsiTheme theme) {
        this.theme = theme;
    }

    List<String> render(List<Map<String, Object>> treeEntries, List<Map<String, Object>> branchEntries, String leafId) {
        var tree = buildTreeView(treeEntries);
        var activePath = entryIds(branchEntries);

        var lines = new ArrayList<String>();
        lines.add(theme.styled("toolLabel", "Conversation Tree"));
        long totalTokens = totalTokens(tree.entries());
        lines.add(theme.styled("statusText", tree.userCount() + " turns · " + tree.entries().size() + " entries"
                                        + (totalTokens > 0 ? " · ~" + SessionDescriptor.formatTokens(totalTokens) : "")
                                        + " · /fork N"));
        lines.add("");
        lines.add(theme.styled("statusText", "turns"));
        var rootTurns = firstUserDescendants(tree.roots(), tree.children());
        if (rootTurns.isEmpty()) {
            lines.add(theme.styled("statusText", "no user turns yet"));
        } else {
            // a single start renders flat (no connector) until it forks; multiple starts are themselves a root fork
            boolean single = rootTurns.size() == 1;
            var seen = new HashSet<String>(); // guards against a cyclic parentId chain looping the walk forever
            for (int i = 0; i < rootTurns.size(); i++) {
                boolean last = i == rootTurns.size() - 1;
                appendSegment(lines, rootTurns.get(i), tree.children(), activePath, leafId, tree.userNumbers(),
                                                single ? "" : (last ? "└ " : "├ "),
                                                single ? "" : (last ? "   " : "│  "), seen);
            }
        }
        return lines;
    }

    private TreeView buildTreeView(List<Map<String, Object>> entries) {
        var byId = new LinkedHashMap<String, Map<String, Object>>();
        var children = new LinkedHashMap<String, List<Map<String, Object>>>();
        var roots = new ArrayList<Map<String, Object>>();
        var userNumbers = new HashMap<String, Integer>();
        int userCount = 0;
        for (var entry : entries) {
            String id = entryId(entry);
            if (id == null)
                continue;
            byId.put(id, entry);
            if (isUserEntry(entry))
                userNumbers.put(id, ++userCount);
        }
        for (var entry : entries) {
            Object pid = entry.get("parentId");
            String parentId = pid instanceof String s ? s : null;
            if (parentId == null || !byId.containsKey(parentId))
                roots.add(entry);
            else
                children.computeIfAbsent(parentId, _ -> new ArrayList<>()).add(entry);
        }
        return new TreeView(entries, roots, children, userNumbers, userCount);
    }

    private Set<String> entryIds(List<Map<String, Object>> entries) {
        var ids = new HashSet<String>();
        for (var entry : entries) {
            String id = entryId(entry);
            if (id != null)
                ids.add(id);
        }
        return ids;
    }

    /**
     * Render a maximal linear run of turns starting at {@code start} at the same indent, then recurse — indenting only
     * where the conversation actually forks (a turn with more than one child branch). {@code firstPrefix} is drawn
     * before the run's first turn (the branch connector), {@code restPrefix} before every later turn in the run.
     */
    private void appendSegment(List<String> lines, Map<String, Object> start,
                                    Map<String, List<Map<String, Object>>> children, Set<String> activePath,
                                    String leafId, Map<String, Integer> userNumbers, String firstPrefix,
                                    String restPrefix, Set<String> seen) {
        Map<String, Object> entry = start;
        boolean first = true;
        while (entry != null) {
            String id = entryId(entry);
            if (id != null && !seen.add(id))
                break; // already rendered this turn — a cyclic parentId chain; stop rather than loop forever
            appendTurnLine(lines, entry, first ? firstPrefix : restPrefix, children, activePath, leafId, userNumbers);
            first = false;
            var nextTurns = firstUserDescendants(children.getOrDefault(id, List.of()), children);
            if (nextTurns.size() == 1) {
                entry = nextTurns.getFirst(); // linear continuation — stay in the same column
            } else if (nextTurns.isEmpty()) {
                entry = null;
            } else {
                for (int i = 0; i < nextTurns.size(); i++) { // a real fork — indent each branch under it
                    boolean last = i == nextTurns.size() - 1;
                    appendSegment(lines, nextTurns.get(i), children, activePath, leafId, userNumbers,
                                                    restPrefix + (last ? "└ " : "├ "),
                                                    restPrefix + (last ? "   " : "│  "), seen);
                }
                entry = null;
            }
        }
    }

    private void appendTurnLine(List<String> lines, Map<String, Object> entry, String prefix,
                                    Map<String, List<Map<String, Object>>> children, Set<String> activePath,
                                    String leafId, Map<String, Integer> userNumbers) {
        String id = entryId(entry);
        boolean currentTurn = leafId != null && containsBeforeNextUser(entry, children, leafId::equals);
        boolean activeTurn = containsBeforeNextUser(entry, children, activePath::contains);
        String marker = currentTurn ? "→" : activeTurn ? "·" : " ";
        lines.add(theme.styled("statusText", prefix)
                                        + theme.styled(currentTurn ? "toolLabel" : "statusText", marker + " ")
                                        + turnLabel(entry, userNumbers.get(id), children, currentTurn));
    }

    private String turnLabel(Map<String, Object> entry, Integer userNumber,
                                    Map<String, List<Map<String, Object>>> children, boolean currentTurn) {
        var summary = summarizeTurn(entry, children);
        var label = new StringBuilder();
        String number = userNumber == null ? "?" : String.valueOf(userNumber);
        label.append(theme.styled(currentTurn ? "toolLabel" : "statusText", String.format("%2s", number)));
        label.append("  ");
        label.append(theme.styled(currentTurn ? "toolLabel" : "", snippet((String) entry.get("content"), 44)));
        if (summary.toolCalls() > 0) {
            label.append(theme.styled("statusText", "  · ")).append(
                                            theme.styled("statusText", Util.plural(summary.toolCalls(), "tool")));
        }
        if (summary.errors() > 0) {
            label.append(theme.styled("toolError", "  · " + Util.plural(summary.errors(), "error")));
        }
        if (summary.compacted() > 0) {
            label.append(theme.styled("statusWarn", "  · compacted"));
        }
        if (summary.estTokens() > 0) {
            label.append(theme.styled("statusText", "  · ~" + SessionDescriptor.formatTokens(summary.estTokens())));
        }
        String age = age(entry);
        if (age != null) {
            label.append(theme.styled("statusText", "  · " + age));
        }
        if (currentTurn)
            label.append(theme.styled("toolLabel", "  · current"));
        return label.toString();
    }

    private List<Map<String, Object>> firstUserDescendants(List<Map<String, Object>> entries,
                                    Map<String, List<Map<String, Object>>> children) {
        var users = new ArrayList<Map<String, Object>>();
        for (var entry : entries) {
            if (isUserEntry(entry)) {
                users.add(entry);
            } else {
                users.addAll(firstUserDescendants(children.getOrDefault(entryId(entry), List.of()), children));
            }
        }
        return users;
    }

    private TurnSummary summarizeTurn(Map<String, Object> entry, Map<String, List<Map<String, Object>>> children) {
        var state = new TurnSummaryState();
        state.chars += contentChars(entry); // the user message itself
        summarizeTurnChildren(children.getOrDefault(entryId(entry), List.of()), children, state);
        return new TurnSummary(state.toolCallIds.size(), state.errors, state.compacted,
                                        SessionDescriptor.estimateTokens(state.chars));
    }

    private void summarizeTurnChildren(List<Map<String, Object>> entries,
                                    Map<String, List<Map<String, Object>>> children, TurnSummaryState state) {
        for (var entry : entries) {
            if (isUserEntry(entry))
                continue;
            state.chars += contentChars(entry);
            String type = (String) entry.getOrDefault("type", "message");
            String role = (String) entry.getOrDefault("role", "");
            if ("compaction".equals(type)) {
                Object compacted = entry.get("compactedCount");
                if (compacted instanceof Number n)
                    state.compacted += n.intValue();
            } else if ("assistant".equals(role)) {
                Object toolCalls = entry.get("tool_calls");
                if (toolCalls instanceof List<?> list) {
                    for (var item : list) {
                        if (item instanceof Map<?, ?> m) {
                            Object callId = m.get("id");
                            state.toolCallIds.add(callId instanceof String s ? s : String.valueOf(m));
                        }
                    }
                }
            } else if ("tool".equals(role) || "tool_result".equals(role)) {
                if (Boolean.TRUE.equals(entry.get("is_error")))
                    state.errors++;
            }
            summarizeTurnChildren(children.getOrDefault(entryId(entry), List.of()), children, state);
        }
    }

    private boolean containsBeforeNextUser(Map<String, Object> entry, Map<String, List<Map<String, Object>>> children,
                                    Predicate<String> predicate) {
        String id = entryId(entry);
        if (id != null && predicate.test(id))
            return true;
        for (var child : children.getOrDefault(id, List.of())) {
            if (isUserEntry(child))
                continue;
            if (containsBeforeNextUser(child, children, predicate))
                return true;
        }
        return false;
    }

    /** Characters of model-visible text in an entry (content + thinking), used for the ~token estimate. */
    private static long contentChars(Map<String, Object> entry) {
        long chars = 0;
        if (entry.get("content") instanceof String s)
            chars += s.length();
        if (entry.get("thinking") instanceof String t)
            chars += t.length();
        return chars;
    }

    private static long totalTokens(List<Map<String, Object>> entries) {
        long chars = 0;
        for (var e : entries) {
            if ("message".equals(e.getOrDefault("type", "message")))
                chars += contentChars(e);
        }
        return SessionDescriptor.estimateTokens(chars);
    }

    /** Relative age of an entry's timestamp ("5m ago"), or null when absent/unparseable. */
    private static String age(Map<String, Object> entry) {
        if (!(entry.get("timestamp") instanceof String ts))
            return null;
        try {
            return SessionDescriptor.relativeAge(java.time.Instant.parse(ts).toEpochMilli());
        } catch (Exception e) {
            return null;
        }
    }

    static String entryId(Map<String, Object> entry) {
        Object id = entry.get("id");
        return id instanceof String s ? s : null;
    }

    static boolean isUserEntry(Map<String, Object> entry) {
        return "message".equals(entry.getOrDefault("type", "message")) && "user".equals(entry.getOrDefault("role", ""));
    }

    static String snippet(String content, int max) {
        if (content == null)
            return "";
        String s = content.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        return s.length() > max ? s.substring(0, Math.max(0, max - 1)) + "…" : s;
    }

    private record TurnSummary(int toolCalls, int errors, int compacted, long estTokens) {
    }

    private record TreeView(List<Map<String, Object>> entries, List<Map<String, Object>> roots,
                                    Map<String, List<Map<String, Object>>> children, Map<String, Integer> userNumbers,
                                    int userCount) {
    }

    private static final class TurnSummaryState {
        final Set<String> toolCallIds = new HashSet<>();
        int errors;
        int compacted;
        long chars;
    }
}
