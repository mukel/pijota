package org.pijota.tui;

import org.pijota.llm.ThinkingLevel;
import org.pijota.theme.AnsiTheme;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import static org.pijota.util.Util.fmtCount;
import static org.pijota.util.Util.fmtCountPrecise;

/**
 * Composes the prompt/status-bar decoration for {@link AppendOnlyTui}: given the editor's {@link PromptSnapshot} it
 * folds in the model/cwd line, token usage, status override, working animation, pending follow-ups, and extension
 * status lines. Reads live TUI state through {@link Host} and holds no mutable state of its own.
 */
final class PromptPresenter {

    /** Live view of the mutable TUI state the decoration reads on each repaint. */
    interface Host {
        AnsiTheme theme();

        String cwd();

        String sessionName();

        String provider();

        String model();

        ThinkingLevel thinkingLevel();

        long contextWindow();

        int messageCount();

        /** Streaming tail to show under the prompt, or {@code null} when not streaming. */
        String liveStreamTail();
    }

    private final Host host;
    private final TurnActivity activity;
    private final StatusOverride statusOverride;
    private final DialogHandler dialogs;
    private final WorkingAnimation animation;
    private final Supplier<List<String>> queuedFollowUps;
    private final ContextTokenMeter tokenMeter;
    private final Supplier<List<String>> extensionLines;

    PromptPresenter(Host host, TurnActivity activity, StatusOverride statusOverride, DialogHandler dialogs,
                                    WorkingAnimation animation, Supplier<List<String>> queuedFollowUps,
                                    ContextTokenMeter tokenMeter, Supplier<List<String>> extensionLines) {
        this.host = host;
        this.activity = activity;
        this.statusOverride = statusOverride;
        this.dialogs = dialogs;
        this.animation = animation;
        this.queuedFollowUps = queuedFollowUps;
        this.tokenMeter = tokenMeter;
        this.extensionLines = extensionLines;
    }

    PromptSnapshot decorate(PromptSnapshot snapshot) {
        String tokens = tokenStatus();
        String status = statusOverride.current();

        AnsiTheme theme = host.theme();
        // Two-line bar, built on TuiCopy's two separators: CHIP joins facts of one kind, GROUP opens another.
        // Line 1 (cwd slot): the workspace, then the session name as its own group.
        // Line 2 (status slot): the token count, then "provider/model · reasoning", then the activity during a turn.
        // Both lines wrap (not truncate) so a narrow terminal spills to the next row instead of losing the tail.
        var cwd = new StringBuilder(theme.styled("commandMeta", host.cwd()));
        String sessionName = host.sessionName();
        if (sessionName != null && !sessionName.isBlank())
            cwd.append(theme.styled("commandMeta", TuiCopy.GROUP + sessionName));

        var stats = new StringBuilder(theme.styled("statusText", tokens));
        stats.append(theme.styled("commandMeta", TuiCopy.GROUP + modelLabel(host.provider(), host.model())));
        // Every level, including OFF. Hiding it made the chip vanish on the one press that turns thinking off, so a
        // piece of the bar disappeared where a setting should have appeared -- and Shift+Tab has no other feedback.
        // Themes give OFF a near-invisible tone for exactly this: present and readable, saying nothing loudly.
        ThinkingLevel thinking = host.thinkingLevel();
        stats.append(theme.styled("commandMeta", TuiCopy.CHIP))
                                        .append(theme.styled(thinking.themeSlot(), thinking.label()));
        if (activity.isBusy()) {
            stats.append(theme.styled("statusText", TuiCopy.GROUP + animation.frame()));
            String phase = activity.phase();
            if (phase != null && !phase.isBlank())
                stats.append(theme.styled("statusText", TuiCopy.CHIP + phase));
            long elapsed = activity.elapsedMillis();
            if (elapsed >= 1000) // a running seconds counter so long turns visibly progress (not stuck)
                stats.append(theme.styled("statusText", TuiCopy.CHIP + (elapsed / 1000) + "s"));
        }
        // One list, so the count and the command can no longer disagree: what is offered is exactly what /undo will
        // find. The hint used to read off a counter the pending list had already been cleared out from under.
        var queued = queuedFollowUps.get();
        if (!queued.isEmpty())
            stats.append(theme.styled("statusText",
                                            TuiCopy.GROUP + "queued " + queued.size() + TuiCopy.CHIP + "/undo"));
        else if (!activity.isBusy() && host.messageCount() == 0)
            stats.append(theme.styled("dim", TuiCopy.GROUP + TuiCopy.START_HINT));

        var decorated = snapshot.withCwd(cwd.toString()).withStatus(stats.toString()).withContextPct(tokenPct())
                                        .withPendingMessages(queued).withExtensionLines(mergedExtensionLines())
                                        .withPromptState(promptState(snapshot, status))
                                        .withStreamTail(host.liveStreamTail()).withWarning(status);
        // Only into an empty box: the affordance shares the ghost slot with the editor's own suggestion, and the
        // editor's is the one that matters once there is something to complete.
        String affordance = dialogs.caretAffordance();
        return affordance != null && snapshot.lines().stream().allMatch(String::isEmpty)
                                        ? decorated.withSuggestion(affordance)
                                        : decorated;
    }

    /**
     * How a model is named everywhere in the interface: {@code provider/model}, with the model's id verbatim.
     *
     * <p>
     * This used to strip a redundant-looking provider prefix from the id, rendering deepseek-v4-flash as
     * {@code deepseek/v4-flash}. That is not a name the model has: passing it back to {@code --model} is answered with
     * "the supported API model names are deepseek-v4-pro or deepseek-v4-flash, but you passed v4-flash". A name shown
     * in a coding agent gets copied into a flag, a settings file, an alias target or a {@code /model} search, so it has
     * to be the identifier and not a prettier version of it.
     */
    static String modelLabel(String provider, String model) {
        if (provider == null || provider.isBlank())
            return model;
        if (model == null || model.isBlank())
            return provider;
        return provider + "/" + model;
    }

    private PromptSnapshot.PromptState promptState(PromptSnapshot snapshot, String status) {
        if (activity.isBusy())
            return PromptSnapshot.PromptState.BUSY;
        // One state for every kind of dialog: the caret says "answer me", and which keys to press is the
        // affordance's job, inside the box. Splitting the state per kind bought no glyph and did buy a divergence,
        // because the caret and its colour slot read the enum in two different places.
        if (dialogs.anyPending())
            return PromptSnapshot.PromptState.AWAITING_ANSWER;
        if (status != null && (status.startsWith("Error:") || "Interrupted".equals(status))) {
            return PromptSnapshot.PromptState.ERROR;
        }
        return insidePromptCodeBlock(snapshot.lines())
                                        ? PromptSnapshot.PromptState.CONTINUATION
                                        : PromptSnapshot.PromptState.IDLE;
    }

    static boolean insidePromptCodeBlock(List<String> lines) {
        String marker = null;
        for (String line : lines) {
            String stripped = line.stripLeading();
            if (stripped.startsWith("```")) {
                if (marker == null)
                    marker = "```";
                else if ("```".equals(marker))
                    marker = null;
            } else if (stripped.startsWith("~~~")) {
                if (marker == null)
                    marker = "~~~";
                else if ("~~~".equals(marker))
                    marker = null;
            }
        }
        return marker != null;
    }

    private List<String> mergedExtensionLines() {
        return mergeExtensionLines(extensionLines.get());
    }

    static List<String> mergeExtensionLines(List<String> lines) {
        var seen = new LinkedHashSet<String>();
        for (String raw : lines) {
            if (!showExtensionStatusLine(raw))
                continue;
            String sanitized = raw.replace('\n', ' ').replace('\r', ' ');
            if (!sanitized.isBlank())
                seen.add(sanitized);
        }
        return List.copyOf(seen);
    }

    static boolean showExtensionStatusLine(String line) {
        if (line == null)
            return false;
        String normalized = line.toLowerCase(Locale.ROOT);
        return !(normalized.contains("filtered") || normalized.contains("secured"));
    }

    String tokenStatus() {
        long tokens = tokenMeter.tokens();
        long contextWindow = host.contextWindow();
        if (tokens <= 0)
            return "?/" + fmtCount((int) contextWindow);
        int pct = (int) Math.min(99, (tokens * 100) / Math.max(1, contextWindow));
        String pctStr = host.theme().styled(pct >= 90 ? "toolError" : pct >= 70 ? "statusWarn" : "statusText",
                                        "(" + pct + "%)");
        return fmtCountPrecise(tokens) + "/" + fmtCount((int) contextWindow) + " " + pctStr;
    }

    int tokenPct() {
        return (int) Math.min(99, (tokenMeter.tokens() * 100) / Math.max(1, host.contextWindow()));
    }
}
