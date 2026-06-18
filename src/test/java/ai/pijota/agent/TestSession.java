package ai.pijota.agent;

import ai.pijota.config.AppConfig;
import ai.pijota.extensions.ExtensionRegistry;
import ai.pijota.testkit.FauxLlm;
import ai.pijota.tools.Tool;
import ai.pijota.tools.ToolRegistry;

import java.nio.file.Path;

/**
 * Builds a {@link PijotaSession} wired to a scriptable {@link FauxLlm}, replacing the {@code createSession(...)} helper
 * that several agent tests hand-roll. The returned holder exposes both the session and its faux LLM so a test can
 * script responses and then drive the session.
 *
 * <p>
 * Lives in {@code ai.pijota.agent} (not {@code testkit}) because it relies on the package-private {@link PijotaSession}
 * constructor that accepts an {@link ai.pijota.llm.LlmClient} test double.
 *
 * <pre>{@code
 * var ts = TestSession.in(tempDir).noSession().build();
 * ts.llm().enqueue(FauxLlm.Response.contentWithUsage("hi"));
 * ts.session().prompt("hello").get(2, SECONDS);
 * }</pre>
 */
public record TestSession(PijotaSession session, FauxLlm llm, ToolRegistry tools, SessionManager sessionManager) {

    public static Builder in(Path dir) {
        return new Builder(dir);
    }

    public static final class Builder {
        private final Path dir;
        private final FauxLlm llm = new FauxLlm();
        private final ToolRegistry tools = new ToolRegistry();
        private ExtensionRegistry extensions = new ExtensionRegistry();
        private String systemPrompt = "system prompt";
        private boolean noSession = false;
        private int contextWindow = 0;
        private AppConfig.CompactionConfig compaction = null;

        private Builder(Path dir) {
            this.dir = dir;
        }

        public Builder tool(Tool tool) {
            tools.register(tool);
            return this;
        }

        public Builder extensions(ExtensionRegistry extensions) {
            this.extensions = extensions;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /** Start without persisting a session file (the {@code --no-session} mode). */
        public Builder noSession() {
            this.noSession = true;
            return this;
        }

        public Builder contextWindow(int contextWindow) {
            this.contextWindow = contextWindow;
            return this;
        }

        public Builder compaction(AppConfig.CompactionConfig compaction) {
            this.compaction = compaction;
            return this;
        }

        public TestSession build() {
            try {
                var sessionManager = new SessionManager(dir);
                var session = new PijotaSession(llm, tools, sessionManager, extensions, systemPrompt, dir.toString(),
                                                noSession, contextWindow, compaction);
                return new TestSession(session, llm, tools, sessionManager);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }
}
