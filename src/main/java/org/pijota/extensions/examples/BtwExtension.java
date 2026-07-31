package org.pijota.extensions.examples;

import org.pijota.agent.SubAgentLauncher;
import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.ExtensionRuntime;
import org.pijota.messages.Message;
import org.pijota.modes.SlashCommand;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@code /btw}: a "by the way" side conversation. Asks a read-only sub-agent a question while the main agent keeps
 * working: it runs concurrently (separate process), can read/search the codebase but never edit, and its answer is
 * shown to the user as a custom message that is kept OUT of the main agent's context. {@code /btw:inject} feeds the
 * last answer back into the main thread when it's worth keeping.
 */
public final class BtwExtension implements Extension {

    /**
     * Tools the side agent may use, read-only, so it can never race the main agent's edits. Names must match the
     * registered tool names (see {@code ToolRegistry.createDefault}).
     */
    private static final List<String> READ_ONLY_TOOLS = List.of("read", "grep", "glob", "ls", "webfetch", "websearch",
                                    "web_google_search", "web_visit_page");
    private static final String SIDE_PROMPT = "You are a focused side assistant answering a quick question in parallel "
                                    + "with the main session. You can read and search the project but must not modify anything. "
                                    + "Answer concisely and directly.";
    private static final int CONTEXT_MESSAGES = 6; // recent turns seeded as context for a contextual /btw
    private static final int MAX_MSG_CHARS = 600;
    private static final Duration TIMEOUT = Duration.ofMinutes(3);

    /** Runs a side-agent task and returns its answer. Injected in tests; defaults to the real process launcher. */
    @FunctionalInterface
    interface Runner {
        String run(String task) throws Exception;
    }

    private final Runner runner; // null → real SubAgentLauncher
    private final Executor executor; // background runner; null → a daemon pool
    private ExtensionAPI api;
    private volatile String lastAnswer = "";

    public BtwExtension() {
        this(null, null);
    }

    BtwExtension(Runner runner, Executor executor) { // test seam
        this.runner = runner;
        this.executor = executor != null ? executor : Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "btw"); // lazily created on first /btw; daemon so it never blocks shutdown
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public String name() {
        return "btw";
    }

    @Override
    public String description() {
        return "Concurrent read-only side questions: /btw, /btw:tangent, /btw:inject.";
    }

    @Override
    public void init(ExtensionAPI api) {
        this.api = api;
        api.registerSlashCommand(new SlashCommand("btw",
                                        "Ask a read-only side question (runs concurrently, hidden from the main thread)",
                                        List.of(), a -> ask(a, true)));
        api.registerSlashCommand(new SlashCommand("btw:tangent", "Side question with no inherited context", List.of(),
                                        a -> ask(a, false)));
        api.registerSlashCommand(new SlashCommand("btw:inject", "Send the last /btw answer to the main agent",
                                        List.of(), this::inject));
    }

    private void ask(String args, boolean contextual) {
        String question = args == null ? "" : args.strip();
        if (question.isBlank()) {
            note("Usage: /btw <question>");
            return;
        }
        String digest = contextual ? contextDigest() : "";
        String task = (digest.isBlank() ? "" : digest + "\n\n") + "Side question: " + question;
        note("🤔 btw: " + question + " · running…");
        executor.execute(() -> {
            try {
                String answer = runSide(task);
                lastAnswer = answer == null ? "" : answer.strip();
                note(lastAnswer.isBlank() ? "btw: (no answer)" : "💡 btw answer:\n\n" + lastAnswer);
            } catch (Exception e) {
                note("btw failed: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            }
        });
    }

    private void inject(String args) {
        if (lastAnswer.isBlank()) {
            note("Nothing to inject yet. Run /btw first.");
            return;
        }
        String prefix = args != null && !args.isBlank() ? args.strip() + "\n\n" : "";
        api.sendUserMessage(prefix + "[from /btw]\n" + lastAnswer);
    }

    private String runSide(String task) throws Exception {
        if (runner != null)
            return runner.run(task);
        var llm = api.runtime().llm();
        return SubAgentLauncher.run(new SubAgentLauncher.Spec(task, SIDE_PROMPT, READ_ONLY_TOOLS,
                                        api.runtime().cwd().toString(), llm.provider(), llm.model(), llm.apiKey(),
                                        TIMEOUT));
    }

    /** A short digest of the recent main-session turns, so a contextual /btw knows what we're working on. */
    private String contextDigest() {
        List<Message> msgs = api.runtime().messages();
        if (msgs == null || msgs.isEmpty())
            return "";
        var sb = new StringBuilder("Context from the current session:\n");
        int headerLen = sb.length();
        for (Message m : msgs.subList(Math.max(0, msgs.size() - CONTEXT_MESSAGES), msgs.size())) {
            String role, text;
            if (m instanceof Message.User u) {
                role = "User";
                text = u.content();
            } else if (m instanceof Message.Assistant a) {
                role = "Assistant";
                text = a.content();
            } else {
                continue;
            }
            if (text == null || text.isBlank())
                continue;
            if (text.length() > MAX_MSG_CHARS)
                text = text.substring(0, MAX_MSG_CHARS) + "…";
            sb.append(role).append(": ").append(text).append('\n');
        }
        return sb.length() > headerLen ? sb.toString() : "";
    }

    /** Shows text to the user as a custom message, displayed everywhere, never added to the main agent's context. */
    private void note(String text) {
        api.sendMessage(new Message.Custom("btw", text));
    }

    @Override
    public void onShutdown(ExtensionRuntime runtime) {
        if (executor instanceof ExecutorService es)
            es.shutdownNow();
    }
}
