package ai.pijota.tui;

import ai.pijota.theme.AnsiTheme;
import org.jline.terminal.Terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class TerminalCoordinator implements AutoCloseable {
    private sealed interface UiOp {
        record Append(List<StyledLine> lines) implements UiOp {
        }
        record Prompt(PromptSnapshot snapshot) implements UiOp {
        }
        record Stop() implements UiOp {
        }
    }

    private final Terminal terminal;
    private final PromptLayout layout;
    private final TerminalFrameRenderer frameRenderer;
    private volatile AnsiTheme theme;
    private final BlockingQueue<UiOp> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread writerThread;
    private PromptSnapshot prompt = new PromptSnapshot(List.of(""), 0, 0, List.of());

    public TerminalCoordinator(Terminal terminal, AnsiTheme theme, PromptLayout layout) {
        this.terminal = terminal;
        this.theme = theme;
        this.layout = layout;
        this.frameRenderer = new TerminalFrameRenderer(layout);
    }

    public void start(PromptSnapshot initialPrompt) {
        prompt = initialPrompt;
        running.set(true);
        writerThread = Thread.ofPlatform().name("pijota-tui-writer").start(this::writerLoop);
        redrawPrompt(initialPrompt);
    }

    public void appendLines(List<StyledLine> lines) {
        if (!lines.isEmpty())
            queue.offer(new UiOp.Append(lines));
    }

    public void redrawPrompt(PromptSnapshot snapshot) {
        queue.offer(new UiOp.Prompt(snapshot));
    }

    public void setTheme(AnsiTheme newTheme) {
        this.theme = newTheme;
    }

    @Override
    public void close() {
        if (!running.getAndSet(false))
            return;
        queue.offer(new UiOp.Stop());
        try {
            if (writerThread != null)
                writerThread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        restore();
    }

    private void writerLoop() {
        try {
            var batch = new ArrayList<UiOp>();
            while (running.get() || !queue.isEmpty()) {
                UiOp first = queue.poll(5, TimeUnit.MILLISECONDS);
                if (first == null)
                    continue;
                if (first instanceof UiOp.Stop)
                    return;
                batch.clear();
                batch.add(first);
                queue.drainTo(batch);
                try {
                    processBatch(batch);
                } catch (Exception e) {
                    restore();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            restore();
        }
    }

    private void processBatch(List<UiOp> ops) {
        int width = Math.max(20, terminal.getWidth());

        PromptSnapshot finalSnapshot = prompt;
        var appends = new ArrayList<UiOp.Append>();
        for (var op : ops) {
            switch (op) {
                case UiOp.Append append -> appends.add(append);
                case UiOp.Prompt p -> {
                    prompt = p.snapshot();
                    finalSnapshot = p.snapshot();
                }
                default -> {}
            }
        }

        if (appends.isEmpty()) {
            emitFrame(width, finalSnapshot, List.of(), true, true);
            return;
        }

        // Render each Append in its own sync frame for line-by-line streaming.
        // The first frame erases the prompt area; the last redraws the prompt.
        for (int i = 0; i < appends.size(); i++) {
            boolean doErase = (i == 0);
            boolean doPrompt = (i == appends.size() - 1);
            emitFrame(width, doPrompt ? finalSnapshot : null, appends.get(i).lines(), doErase, doPrompt);
        }
    }

    private void emitFrame(int width, PromptSnapshot snapshot, List<StyledLine> lines, boolean erase,
                                    boolean showPrompt) {
        terminal.writer().print(frameRenderer.renderFrame(width, snapshot, lines, erase, showPrompt, theme));
        terminal.writer().flush();
    }

    private void restore() {
        try {
            terminal.writer().print("\033[0m" + TerminalFrameRenderer.SHOW_CURSOR + "\r\n");
            terminal.writer().flush();
        } catch (Exception ignored) {}
    }

}
