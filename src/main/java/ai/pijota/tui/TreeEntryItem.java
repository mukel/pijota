package ai.pijota.tui;

import java.util.Map;

record TreeEntryItem(String id, String parentId, String role, String content, String display,
                                boolean current) implements SelectorItem {
    static TreeEntryItem from(Map<String, Object> entry, boolean current) {
        String id = String.valueOf(entry.getOrDefault("id", ""));
        String parentId = entry.get("parentId") instanceof String s ? s : null;
        String type = String.valueOf(entry.getOrDefault("type", "message"));
        String role = "message".equals(type) ? String.valueOf(entry.getOrDefault("role", type)) : type;
        String content = entry.get("content") instanceof String s && !s.isBlank() ? s : type;
        content = content.replace('\n', ' ').trim();
        String display = content.length() > 90 ? content.substring(0, 87) + "..." : content;
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
        return (current ? "→ " : "  ") + role;
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
