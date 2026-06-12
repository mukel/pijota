package ai.pijota.extensions.examples;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

public final class ContextFilesExtension implements Extension {

    private final String cwd;

    public ContextFilesExtension(String cwd) {
        this.cwd = cwd;
    }

    @Override
    public String name() {
        return "context-files";
    }

    @Override
    public String description() {
        return "Discovers AGENTS.md, CLAUDE.md, and .pijota/CONTEXT.md";
    }

    @Override
    public void init(ExtensionAPI api) {
        String content = collectContextFiles();
        if (!content.isBlank()) {
            api.registerSystemPrompt(() -> content);
        }
    }

    private String collectContextFiles() {
        var sb = new StringBuilder();
        var dirs = new ArrayList<Path>();
        Path current = Path.of(cwd).toAbsolutePath().normalize();
        Path root = current.getRoot();
        while (current != null && !current.equals(root)) {
            dirs.add(current);
            current = current.getParent();
        }
        for (int i = dirs.size() - 1; i >= 0; i--) {
            loadFile(sb, dirs.get(i), "AGENTS.md");
            loadFile(sb, dirs.get(i), "CLAUDE.md");
        }
        loadFile(sb, Path.of(cwd), ".pijota/CONTEXT.md");
        return sb.toString();
    }

    private static void loadFile(StringBuilder sb, Path dir, String filename) {
        try {
            Path path = dir.resolve(filename);
            if (Files.exists(path)) {
                String content = Files.readString(path);
                if (content.length() > 8000) {
                    content = content.substring(0, 8000) + "... (truncated)";
                }
                sb.append("--- ").append(filename).append(" (from ").append(path.getParent().getFileName()).append(")")
                                                .append(" ---\n").append(content).append('\n');
            }
        } catch (IOException ignored) {}
    }
}
