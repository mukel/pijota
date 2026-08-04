package org.pijota.extensions.examples;

import org.pijota.config.ProjectTrust;
import org.pijota.config.TrustStore;
import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.ExtensionEvent;
import org.pijota.extensions.ExtensionRuntime;
import org.pijota.modes.SlashCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Discovers project context files ({@code AGENTS.md}, {@code CLAUDE.md}, {@code .pijota/CONTEXT.md}) and injects their
 * content into the system prompt. Searches upward from the working directory to the filesystem root. Gated by the
 * {@code --no-context-files} flag and settings.
 *
 * <p>
 * Injection is also gated by <b>project trust</b>: a folder must be trusted (via {@code /trust}, remembered in
 * {@link TrustStore}) before its context files load; otherwise a cloned, untrusted repo could inject instructions into
 * the agent through its {@code AGENTS.md}/{@code CLAUDE.md}. Trusting takes effect from the next turn.
 */
public final class ContextFilesExtension implements Extension {

    /**
     * What an untrusted project is actually withholding.
     *
     * <p>
     * The hint used to name only AGENTS.md/CLAUDE.md and only appear when one of those existed. A project whose
     * resources are all under {@code .pijota/} -- skills, agents, commands -- therefore said nothing at all: they were
     * skipped in silence, and their absence reads as a missing feature rather than a decision the user made.
     */
    static String untrustedHint(boolean hasContextFiles, boolean hasProjectResources) {
        String what = hasContextFiles && hasProjectResources
                                        ? ".pijota/ and AGENTS.md"
                                        : hasProjectResources
                                                                        ? ".pijota/ (skills, agents, commands)"
                                                                        : "AGENTS.md/CLAUDE.md";
        return "🔒 untrusted project · /trust to load " + what;
    }

    private final String cwd;
    private final TrustStore trust;
    private ExtensionAPI api;
    private String content = "";

    public ContextFilesExtension(String cwd) {
        this(cwd, new TrustStore());
    }

    ContextFilesExtension(String cwd, TrustStore trust) {
        this.cwd = cwd;
        this.trust = trust;
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
        this.api = api;
        this.content = collectContextFiles();
        // Inject per-turn (not once at startup) so a trust decision takes effect immediately, and only when the
        // current folder is trusted.
        api.on(ExtensionEvent.BeforeAgentStart.class, e -> {
            if (!content.isBlank() && trust.isTrusted(cwd))
                e.systemPrompt().append('\n').append(content);
        });
        api.registerSlashCommand(new SlashCommand("trust",
                                        "Trust this folder so its AGENTS.md/CLAUDE.md load into context", List.of(),
                                        this::handleTrust));
    }

    @Override
    public void onReady(ExtensionRuntime runtime) {
        boolean hasContextFiles = !content.isBlank();
        boolean hasProjectResources = ProjectTrust.hasProtectedResources(cwd);
        if ((hasContextFiles || hasProjectResources) && !trust.isTrusted(cwd))
            runtime.setStatus("trust", untrustedHint(hasContextFiles, hasProjectResources));
    }

    private void handleTrust(String args) {
        var runtime = api.runtime();
        String cmd = args == null ? "" : args.strip().toLowerCase();
        switch (cmd) {
            case "status" -> runtime.setStatus(trust.isTrusted(cwd)
                                            ? "Project trusted: " + cwd
                                            : "Project NOT trusted: " + cwd + (content.isBlank()
                                                                            ? ""
                                                                            : " (context files present)"));
            case "revoke", "off" -> {
                trust.revoke(cwd);
                // Skills, sub-agents and MCP servers read the run's resolved answer, not the store, so a trust change
                // that only wrote the file would leave them loaded until the next launch.
                ProjectTrust.resolve(cwd, false);
                boolean resources = ProjectTrust.hasProtectedResources(cwd);
                if (!content.isBlank() || resources)
                    runtime.setStatus("trust", untrustedHint(!content.isBlank(), resources));
                runtime.setStatus("Revoked trust for " + cwd);
            }
            default -> {
                trust.trust(cwd);
                ProjectTrust.resolve(cwd, true);
                runtime.clearStatus("trust");
                runtime.setStatus(content.isBlank()
                                                ? "Trusted " + cwd
                                                : "Trusted: project context (AGENTS.md/CLAUDE.md) loads from the next turn.");
            }
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
