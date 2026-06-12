package ai.pijota.tui;

import ai.pijota.util.Util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

final class PendingEditManager {
    private final String cwd;

    private String oldText;
    private String newText;
    private String filePath;
    private boolean previewShown;
    private String toolName;
    private Supplier<String> toolArgs;

    PendingEditManager(String cwd) {
        this.cwd = cwd;
    }

    String toolName() {
        return toolName;
    }

    Supplier<String> toolArgs() {
        return toolArgs;
    }

    boolean hasPath() {
        return filePath != null;
    }

    boolean hasEditResult() {
        return oldText != null && newText != null && !oldText.equals(newText);
    }

    String oldText() {
        return oldText;
    }

    String newText() {
        return newText;
    }

    String filePath() {
        return filePath;
    }

    boolean previewShown() {
        return previewShown;
    }

    void setPreviewShown(boolean v) {
        this.previewShown = v;
    }

    void onToolStart(String name, Supplier<String> args) {
        toolName = name;
        toolArgs = args;
        if (args == null)
            return;
        String path = extractPath(args.get());
        if (path != null && !path.isBlank()) {
            filePath = path;
            oldText = readFile(path);
            previewShown = false;
            newText = previewEdit(args.get());
        }
    }

    void onToolResult(boolean isEditWrite, boolean isError) {
        if (isEditWrite && !isError && filePath != null) {
            String actual = readFile(filePath);
            if (previewShown && !actual.equals(newText))
                previewShown = false;
            if (!previewShown)
                newText = actual;
        } else if (isError) {
            oldText = null;
            newText = null;
            filePath = null;
            previewShown = false;
            toolName = null;
            toolArgs = null;
        }
    }

    void clear() {
        oldText = null;
        newText = null;
        filePath = null;
        previewShown = false;
        toolName = null;
        toolArgs = null;
    }

    private String extractPath(String argsJson) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> a = Util.MAPPER.readValue(argsJson, Map.class);
            return Util.getFirstString(a, "path", "filePath", "file_path");
        } catch (Exception ignored) {
            return "";
        }
    }

    private String readFile(String path) {
        if (path == null || path.isBlank())
            return "";
        try {
            Path p = Path.of(path);
            if (!p.isAbsolute()) {
                String raw = cwd.replaceAll("\\(.*\\)", "").strip();
                if (raw.startsWith("~"))
                    raw = System.getProperty("user.home") + raw.substring(1);
                p = Path.of(raw).resolve(path).normalize();
            }
            if (Files.exists(p)) {
                String content = Files.readString(p);
                return content.length() > 32_000 ? content.substring(0, 32_000) + "\n... (truncated)" : content;
            }
        } catch (Exception ignored) {}
        return "";
    }

    @SuppressWarnings("unchecked")
    private String previewEdit(String argsJson) {
        if (oldText == null || oldText.isEmpty() || argsJson == null)
            return null;
        try {
            Map<String, Object> a = Util.parseJsonArgs(argsJson);
            var edits = new ArrayList<Map<String, Object>>();
            Object rawEdits = a.get("edits");
            if (rawEdits instanceof List<?> list) {
                for (Object item : list)
                    if (item instanceof Map<?, ?> m)
                        edits.add((Map<String, Object>) m);
            }
            if (edits.isEmpty() && a.get("oldText") instanceof String && a.get("newText") instanceof String) {
                edits.add(Map.of("oldText", a.get("oldText"), "newText", a.get("newText")));
            }
            if (edits.isEmpty() && a.get("oldString") instanceof String && a.get("newString") instanceof String) {
                edits.add(Map.of("oldText", a.get("oldString"), "newText", a.get("newString")));
            }
            String result = oldText;
            for (var edit : edits) {
                Object oldObj = edit.get("oldText");
                Object newObj = edit.get("newText");
                if (!(oldObj instanceof String oldPart) || !(newObj instanceof String newPart))
                    return null;
                if (Util.countOccurrences(result, oldPart) != 1)
                    return null;
                result = result.replace(oldPart, newPart);
            }
            return result;
        } catch (Exception ignored) {
            return null;
        }
    }
}
