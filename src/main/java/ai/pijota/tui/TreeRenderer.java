package ai.pijota.tui;

import ai.pijota.theme.AnsiTheme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

final class TreeRenderer {
    private static final int MAX_TREE_INDENT = 3;

    private final AnsiTheme theme;

    TreeRenderer(AnsiTheme theme) {
        this.theme = theme;
    }

    List<String> render(List<Map<String, Object>> treeEntries,
                        List<Map<String, Object>> branchEntries,
                        String leafId) {
        var tree = buildTreeView(treeEntries);
        var activePath = entryIds(branchEntries);

        var lines = new ArrayList<String>();
        lines.add(theme.styled("toolLabel", "Conversation Tree"));
        lines.add(theme.styled("statusText", tree.userCount() + " turns · "
            + tree.entries().size() + " entries · /fork N"));
        lines.add("");
        lines.add(theme.styled("statusText", "turns"));
        var rootTurns = firstUserDescendants(tree.roots(), tree.children());
        if (rootTurns.isEmpty()) {
            lines.add(theme.styled("statusText", "no user turns yet"));
        }
        for (int i = 0; i < rootTurns.size(); i++) {
            appendTurnNode(lines, rootTurns.get(i), tree.children(), tree.parents(), activePath, leafId, tree.userNumbers(),
                "", i == rootTurns.size() - 1, 0);
        }
        return lines;
    }

    private TreeView buildTreeView(List<Map<String, Object>> entries) {
        var byId = new LinkedHashMap<String, Map<String, Object>>();
        var children = new LinkedHashMap<String, List<Map<String, Object>>>();
        var parents = new HashMap<String, String>();
        var roots = new ArrayList<Map<String, Object>>();
        var userNumbers = new HashMap<String, Integer>();
        int userCount = 0;
        for (var entry : entries) {
            String id = entryId(entry);
            if (id == null) continue;
            byId.put(id, entry);
            if (isUserEntry(entry)) userNumbers.put(id, ++userCount);
        }
        for (var entry : entries) {
            String parentId = (String) entry.get("parentId");
            if (parentId == null || !byId.containsKey(parentId)) {
                roots.add(entry);
            } else {
                children.computeIfAbsent(parentId, _ -> new ArrayList<>()).add(entry);
                String id = entryId(entry);
                if (id != null) parents.put(id, parentId);
            }
        }
        return new TreeView(entries, roots, children, parents, userNumbers, userCount);
    }

    private Set<String> entryIds(List<Map<String, Object>> entries) {
        var ids = new HashSet<String>();
        for (var entry : entries) {
            String id = entryId(entry);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private void appendTurnNode(List<String> lines, Map<String, Object> entry,
                                Map<String, List<Map<String, Object>>> children,
                                Map<String, String> parents,
                                Set<String> activePath, String leafId,
                                Map<String, Integer> userNumbers,
                                String prefix, boolean last, int depth) {
        String id = entryId(entry);
        boolean currentTurn = leafId != null && containsBeforeNextUser(entry, children, leafId::equals);
        boolean activeTurn = containsBeforeNextUser(entry, children, activePath::contains);
        int visualDepth = Math.min(depth, MAX_TREE_INDENT);
        String marker = currentTurn ? "→" : activeTurn ? "·" : " ";
        String connector = depth > MAX_TREE_INDENT ? "┆ " : last ? "└ " : "├ ";
        String visualPrefix = depth > MAX_TREE_INDENT ? "   ".repeat(MAX_TREE_INDENT) : prefix;
        Integer from = depth > MAX_TREE_INDENT ? forkFrom(id, parents, userNumbers) : null;
        String line = theme.styled("statusText", visualPrefix + connector)
            + theme.styled(currentTurn ? "toolLabel" : "statusText", marker + " ")
            + turnLabel(entry, userNumbers.get(id), children, currentTurn, from);
        lines.add(line);

        var childEntries = firstUserDescendants(children.getOrDefault(id, List.of()), children);
        String childPrefix = visualDepth >= MAX_TREE_INDENT ? visualPrefix : prefix + (last ? "   " : "│  ");
        for (int i = 0; i < childEntries.size(); i++) {
            appendTurnNode(lines, childEntries.get(i), children, parents, activePath, leafId, userNumbers,
                childPrefix, i == childEntries.size() - 1, depth + 1);
        }
    }

    private String turnLabel(Map<String, Object> entry, Integer userNumber,
                             Map<String, List<Map<String, Object>>> children,
                             boolean currentTurn, Integer forkFrom) {
        var summary = summarizeTurn(entry, children);
        var label = new StringBuilder();
        String number = userNumber == null ? "?" : String.valueOf(userNumber);
        label.append(theme.styled(currentTurn ? "toolLabel" : "statusText", String.format("%2s", number)));
        label.append("  ");
        label.append(theme.styled(currentTurn ? "toolLabel" : "", snippet((String) entry.get("content"), 44)));
        if (forkFrom != null && userNumber != null && forkFrom != userNumber - 1) {
            label.append(theme.styled("statusText", "  · from ")).append(forkFrom);
        }
        if (summary.toolCalls() > 0) {
            label.append(theme.styled("statusText", "  · ")).append(theme.styled("statusText", summary.toolCalls()
                + " tool" + (summary.toolCalls() == 1 ? "" : "s")));
        }
        if (summary.errors() > 0) {
            label.append(theme.styled("toolError", "  · " + summary.errors() + " error"
                + (summary.errors() == 1 ? "" : "s")));
        }
        if (summary.compacted() > 0) {
            label.append(theme.styled("statusWarn", "  · compacted"));
        }
        if (currentTurn) label.append(theme.styled("toolLabel", "  · current"));
        return label.toString();
    }

    private Integer forkFrom(String id, Map<String, String> parents, Map<String, Integer> userNumbers) {
        String parent = parents.get(id);
        while (parent != null) {
            Integer number = userNumbers.get(parent);
            if (number != null) return number;
            parent = parents.get(parent);
        }
        return null;
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

    private TurnSummary summarizeTurn(Map<String, Object> entry,
                                      Map<String, List<Map<String, Object>>> children) {
        var tools = new LinkedHashSet<String>();
        var state = new TurnSummaryState();
        summarizeTurnChildren(children.getOrDefault(entryId(entry), List.of()), children, tools, state);
        return new TurnSummary(state.assistant, state.toolCallIds.size(), List.copyOf(tools), state.errors, state.compacted);
    }

    private void summarizeTurnChildren(List<Map<String, Object>> entries,
                                       Map<String, List<Map<String, Object>>> children,
                                       Set<String> tools, TurnSummaryState state) {
        for (var entry : entries) {
            if (isUserEntry(entry)) continue;
            String type = (String) entry.getOrDefault("type", "message");
            String role = (String) entry.getOrDefault("role", "");
            if ("compaction".equals(type)) {
                Object compacted = entry.get("compactedCount");
                if (compacted instanceof Number n) state.compacted += n.intValue();
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
                String content = (String) entry.get("content");
                if (content != null && !content.isBlank()) state.assistant = content;
            } else if ("tool".equals(role) || "tool_result".equals(role)) {
                String tool = String.valueOf(entry.getOrDefault("tool_name", "?"));
                tools.add(tool);
                if (Boolean.TRUE.equals(entry.get("is_error"))) state.errors++;
            }
            summarizeTurnChildren(children.getOrDefault(entryId(entry), List.of()), children, tools, state);
        }
    }

    private boolean containsBeforeNextUser(Map<String, Object> entry,
                                           Map<String, List<Map<String, Object>>> children,
                                           Predicate<String> predicate) {
        String id = entryId(entry);
        if (id != null && predicate.test(id)) return true;
        for (var child : children.getOrDefault(id, List.of())) {
            if (isUserEntry(child)) continue;
            if (containsBeforeNextUser(child, children, predicate)) return true;
        }
        return false;
    }

    private static String entryId(Map<String, Object> entry) {
        Object id = entry.get("id");
        return id instanceof String s ? s : null;
    }

    private static boolean isUserEntry(Map<String, Object> entry) {
        return "message".equals(entry.getOrDefault("type", "message"))
            && "user".equals(entry.getOrDefault("role", ""));
    }

    private static String snippet(String content, int max) {
        if (content == null) return "";
        String s = content.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        return s.length() > max ? s.substring(0, Math.max(0, max - 1)) + "…" : s;
    }

    private record TurnSummary(String assistant, int toolCalls, List<String> tools, int errors, int compacted) {}

    private record TreeView(List<Map<String, Object>> entries,
                            List<Map<String, Object>> roots,
                            Map<String, List<Map<String, Object>>> children,
                            Map<String, String> parents,
                            Map<String, Integer> userNumbers,
                            int userCount) {}

    private static final class TurnSummaryState {
        String assistant = "";
        final Set<String> toolCallIds = new HashSet<>();
        int errors;
        int compacted;
    }
}
