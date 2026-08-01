package org.pijota.tui;

import org.pijota.util.Util;

import java.util.Map;
import java.util.Set;

record TreeEntryItem(String id, String parentId, String role, String content, String display,
                                boolean current) implements SelectorItem {
    // Pure internal state entries (no content of their own; they'd render as "type type" and are meaningless jump
    // targets). Conversation messages and content-bearing markers (branch_summary, compaction) are kept.
    private static final Set<String> INTERNAL_TYPES = Set.of("cache_metadata", "model_change", "thinking_level_change",
                                    "session_info", "session");

    static boolean isInternalMetadata(Map<String, Object> entry) {
        return INTERNAL_TYPES.contains(String.valueOf(entry.getOrDefault("type", "message")));
    }

    static TreeEntryItem from(Map<String, Object> entry, boolean current) {
        String id = String.valueOf(entry.getOrDefault("id", ""));
        String parentId = entry.get("parentId") instanceof String s ? s : null;
        String type = String.valueOf(entry.getOrDefault("type", "message"));
        String role = "message".equals(type) ? String.valueOf(entry.getOrDefault("role", type)) : type;
        String content = entry.get("content") instanceof String s && !s.isBlank() ? s : type;
        content = content.replace('\n', ' ').trim();
        String display = Util.ellipsize(content, 90);
        return new TreeEntryItem(id, parentId, role, content, display, current);
    }

    boolean user() {
        return "user".equals(role);
    }

    @Override
    public String searchText() {
        return role + " " + content + " " + id;
    }

    @Override
    public String titleText() {
        return role;
    }

    @Override
    public String detailText() {
        return display;
    }

    @Override
    public boolean selected() {
        return current;
    }
}
