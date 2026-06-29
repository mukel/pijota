package ai.pijota.loop;

import ai.pijota.AppBootstrap;
import ai.pijota.agent.AgentEvent;
import ai.pijota.agent.PijotaSession;
import ai.pijota.cli.ArgParser;
import ai.pijota.cli.Args;
import ai.pijota.messages.Message;
import ai.pijota.util.Executables;
import ai.pijota.util.ProcessRunner;
import ai.pijota.util.Util;
import ai.pijota.worktree.WorktreePool;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code pijota loop "<objective>"} — run pijota unattended against an objective until a budget or natural-done
 * condition is hit (the gnhf "good night, have fun" workflow). Thin CLI shell around {@link LoopRunner}: parse the loop
 * flags, build a headless session via {@link AppBootstrap}, optionally isolate in a {@link WorktreePool} worktree, then
 * run the loop and print a summary.
 *
 * <pre>
 *   pijota loop "&lt;objective&gt;"        # or:  cat spec.md | pijota loop
 *     --max-iterations N   cap loop re-prompts (default: unlimited)
 *     --max-tokens N       cap cumulative input+output tokens (default: unlimited)
 *     --commit             commit each successful iteration; roll back the tree on failure
 *     --push               push after each committed iteration (implies --commit)
 *     --worktree           run isolated in a pooled git worktree
 *     [-m/--model, --provider, --thinking, …]  standard session flags, passed through
 * </pre>
 */
public final class LoopCommand {

    private static final long BACKOFF_BASE_MS = 2_000;

    private LoopCommand() {}

    /** The loop config extracted from argv plus the {@link Args} carrying the pass-through session flags. */
    record LoopConfig(String objective, LoopRunner.Options options, boolean worktree, Args sessionArgs) {
    }

    /**
     * Parse loop argv (everything after the {@code loop} word). Reuses {@link ArgParser}: loop flags land in
     * {@code extensionFlags} (ignored by the session, no extension claims them) and the objective in
     * {@code promptArgs}.
     */
    static LoopConfig parse(String[] argv) {
        Args a = ArgParser.parse(argv);
        Map<String, String> flags = a.extensionFlags();
        int maxIterations = (int) numericFlag(flags, "max-iterations");
        long maxTokens = numericFlag(flags, "max-tokens");
        boolean push = flags.containsKey("push");
        boolean commit = push || flags.containsKey("commit");
        boolean worktree = flags.containsKey("worktree");
        String objective = String.join(" ", a.promptArgs()).strip();
        var options = new LoopRunner.Options(maxIterations, maxTokens, commit, push, BACKOFF_BASE_MS);
        return new LoopConfig(objective, options, worktree, a);
    }

    public static int run(String[] args) {
        LoopConfig cfg = parse(Arrays.copyOfRange(args, 1, args.length));
        String objective = cfg.objective().isEmpty() ? Util.readStdin() : cfg.objective();
        if (objective == null || objective.isBlank()) {
            System.err.println("pijota loop: provide an objective as an argument or on stdin");
            System.err.println("  e.g. pijota loop \"tidy the README\" --max-iterations 3 --commit");
            return 2;
        }

        WorktreePool pool = null;
        Path acquired = null;
        try {
            if (cfg.worktree()) {
                if (!Executables.isAvailable("git")) {
                    System.err.println("pijota loop --worktree: git is not available on PATH");
                    return 2;
                }
                pool = WorktreePool.open(Path.of(System.getProperty("user.dir")));
                acquired = pool.acquire(true);
                // The session's cwd is read from user.dir at bootstrap, so point it at the worktree before
                // bootstrapping.
                System.setProperty("user.dir", acquired.toString());
                if (cfg.options().commit())
                    createLoopBranch(acquired);
                System.out.println("⌂ loop worktree: " + acquired);
            }

            var b = AppBootstrap.bootstrap(cfg.sessionArgs());
            PijotaSession session = b.session();
            var agentLoop = session.agentLoop();
            Path loopCwd = Path.of(b.cwd());

            // Stream the assistant text per iteration, PrintMode-style.
            agentLoop.addEventListener(e -> {
                if (e instanceof AgentEvent.MessageStreamDelta d) {
                    System.out.print(d.delta());
                    System.out.flush();
                }
            });

            var interrupted = new AtomicBoolean();
            Thread hook = new Thread(() -> {
                interrupted.set(true);
                agentLoop.abort();
                System.err.println("\n⏹ interrupted — stopping after the current iteration");
            });
            Runtime.getRuntime().addShutdownHook(hook);

            LoopRunner.Git git = cfg.options().commit() ? new LoopGit(loopCwd) : new LoopRunner.NoGit();
            LoopRunner.Step step = prompt -> {
                List<Message> messages = session.prompt(prompt).get();
                System.out.println();
                return lastAssistantText(messages);
            };

            long start = System.nanoTime();
            var summary = LoopRunner.run(objective, cfg.options(), step,
                                            () -> b.llm().totalInputTokens() + b.llm().totalOutputTokens(),
                                            () -> b.llm().totalCost(), git, interrupted::get,
                                            msg -> System.out.println("  · " + msg));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            removeHook(hook);
            printSummary(summary, elapsedMs);
            return summary.stopReason() != null && summary.stopReason().startsWith("too many") ? 1 : 0;
        } catch (Exception e) {
            System.err.println("pijota loop failed: " + e.getMessage());
            return 1;
        } finally {
            if (pool != null && acquired != null) {
                try {
                    pool.release(acquired);
                } catch (Exception e) {
                    System.err.println("pijota loop: failed to release worktree " + acquired + ": " + e.getMessage());
                }
            }
        }
    }

    private static void createLoopBranch(Path worktree) throws Exception {
        String branch = "pijota/loop-" + System.currentTimeMillis();
        var r = ProcessRunner.command("git", "switch", "-c", branch).workDir(worktree).run();
        if (r.exitCode() != 0)
            throw new RuntimeException("failed to create branch " + branch + ": " + r.stderr().strip());
        System.out.println("⌂ loop branch: " + branch);
    }

    private static String lastAssistantText(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--)
            if (messages.get(i) instanceof Message.Assistant a)
                return a.content();
        return null;
    }

    private static void printSummary(LoopRunner.Summary s, long elapsedMs) {
        System.out.println();
        System.out.println("── loop summary ─────────────────────────");
        System.out.println("  iterations : " + s.iterations());
        System.out.println("  tokens     : " + s.tokens());
        System.out.printf("  cost       : $%.4f%n", s.cost());
        System.out.println("  commits    : " + s.commits());
        System.out.println("  elapsed    : " + Util.formatDuration(elapsedMs));
        System.out.println("  stopped    : " + (s.stopReason() != null ? s.stopReason() : "completed"));
    }

    /**
     * A non-negative numeric loop flag (e.g. {@code --max-iterations}, {@code --max-tokens}). Missing/blank or a bare
     * {@code --flag} (parsed as {@code "true"}) means "unlimited" → {@code 0}; an unparseable value warns and falls
     * back to {@code 0}. Returned as {@code long}; callers needing an {@code int} cap cast it.
     */
    private static long numericFlag(Map<String, String> flags, String name) {
        String v = flags.get(name);
        if (v == null || v.isBlank() || "true".equals(v))
            return 0;
        try {
            return Math.max(0, Long.parseLong(v.trim()));
        } catch (NumberFormatException e) {
            System.err.println("pijota loop: ignoring invalid --" + name + " '" + v + "'");
            return 0;
        }
    }

    private static void removeHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // JVM already shutting down — nothing to remove.
        }
    }
}
