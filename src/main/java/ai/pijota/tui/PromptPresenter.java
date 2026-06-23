package ai.pijota.tui;

import ai.pijota.llm.ThinkingLevel;
import ai.pijota.theme.AnsiTheme;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import static ai.pijota.util.Util.fmtCount;

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

        /** Streaming tail to show under the prompt, or {@code null} when not streaming. */
        String liveStreamTail();
    }

    private final Host host;
    private final TurnActivity activity;
    private final StatusOverride statusOverride;
    private final DialogHandler dialogs;
    private final WorkingAnimation animation;
    private final FollowUpQueue followUps;
    private final ContextTokenMeter tokenMeter;
    private final Supplier<List<String>> extensionLines;

    PromptPresenter(Host host, TurnActivity activity, StatusOverride statusOverride, DialogHandler dialogs,
                                    WorkingAnimation animation, FollowUpQueue followUps, ContextTokenMeter tokenMeter,
                                    Supplier<List<String>> extensionLines) {
        this.host = host;
        this.activity = activity;
        this.statusOverride = statusOverride;
        this.dialogs = dialogs;
        this.animation = animation;
        this.followUps = followUps;
        this.tokenMeter = tokenMeter;
        this.extensionLines = extensionLines;
    }

    PromptSnapshot decorate(PromptSnapshot snapshot) {
        String tokens = tokenStatus();
        String status = statusOverride.current();
        String promptLine = dialogs.promptStatusLine();
        if (promptLine != null) {
            status = status != null ? status + "\n" + promptLine : promptLine;
        }
        AnsiTheme theme = host.theme();
        String cwdLine = theme.styled("commandMeta", host.cwd());
        String sessionName = host.sessionName();
        if (sessionName != null && !sessionName.isBlank()) {
            cwdLine += theme.styled("commandMeta", "  " + sessionName);
        }
        cwdLine += theme.styled("commandMeta", "  " + compactModelName(host.provider(), host.model()));
        if (host.thinkingLevel() != ThinkingLevel.OFF) {
            cwdLine += theme.styled("commandMeta", "  " + host.thinkingLevel().label());
        }
        snapshot = snapshot.withCwd(cwdLine).withContextPct(tokenPct()).withPendingMessages(followUps.pending())
                                        .withExtensionLines(mergedExtensionLines())
                                        .withPromptState(promptState(snapshot, status))
                                        .withStreamTail(host.liveStreamTail());
        if (!activity.isBusy()) {
            return snapshot.withStatus(tokens).withWarning(status);
        }
        String frame = animation.frame();
        int queued = followUps.queuedCount();
        String bar = tokens + "  " + frame;
        String phase = activity.phase();
        if (phase != null && !phase.isBlank())
            bar += " " + phase;
        long elapsed = activity.elapsedMillis();
        if (elapsed >= 1000) // a running seconds counter so long turns visibly progress (not stuck)
            bar += "  " + (elapsed / 1000) + "s";
        if (queued > 0)
            bar += "  queued " + queued;
        return snapshot.withStatus(bar).withWarning(status);
    }

    static String compactModelName(String provider, String model) {
        if (provider == null || provider.isBlank())
            return model;
        if (model == null || model.isBlank())
            return provider;
        String shortModel = model;
        String prefix = provider + "-";
        if (shortModel.startsWith(prefix))
            shortModel = shortModel.substring(prefix.length());
        return provider + "/" + shortModel;
    }

    private PromptSnapshot.PromptState promptState(PromptSnapshot snapshot, String status) {
        if (activity.isBusy())
            return PromptSnapshot.PromptState.BUSY;
        if (dialogs.questionPending())
            return PromptSnapshot.PromptState.QUESTION;
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
        var seen = new LinkedHashSet<String>();
        for (String raw : extensionLines.get()) {
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
        String pctStr = host.theme().styled(pct >= 90 ? "toolError" : pct >= 70 ? "toolLabel" : "statusText",
                                        "(" + pct + "%)");
        return fmtCount((int) tokens) + "/" + fmtCount((int) contextWindow) + " " + pctStr;
    }

    int tokenPct() {
        return (int) Math.min(99, (tokenMeter.tokens() * 100) / Math.max(1, host.contextWindow()));
    }
}
