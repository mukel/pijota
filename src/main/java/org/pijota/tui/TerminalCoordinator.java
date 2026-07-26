package org.pijota.tui;

import org.pijota.theme.AnsiTheme;
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
        record Tail(String tail) implements UiOp {
        }
        record Stop() implements UiOp {
        }
    }

    private final Terminal terminal;
    private final TerminalFrameRenderer frameRenderer;
    private volatile AnsiTheme theme;
    private final BlockingQueue<UiOp> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread writerThread;
    private PromptSnapshot prompt = new PromptSnapshot(List.of(""), 0, 0, List.of());

    public TerminalCoordinator(Terminal terminal, AnsiTheme theme, PromptLayout layout) {
        this.terminal = terminal;
        this.theme = theme;
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

    /** Requests an in-place repaint of just the live streaming-tail row (falls back to a full frame when needed). */
    public void updateStreamTail(String tail) {
        queue.offer(new UiOp.Tail(tail == null ? "" : tail));
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
        int height = Math.max(4, terminal.getHeight());

        // Coalesce the whole batch into ONE frame: erase the prompt once, append every committed line in order, then
        // redraw the prompt once (including the live streaming preview). Rendering each append as its own frame would
        // briefly leave the prompt region erased between frames — visible flicker when ops interleave during streaming.
        // The flicker-free write-then-trim protocol makes one full frame per throttled update cheap (~3ms measured), so
        // streaming repaints reuse it rather than a separate single-row in-place path.
        PromptSnapshot finalSnapshot = prompt;
        var lines = new ArrayList<StyledLine>();
        String tailUpdate = null; // a Tail op always carries a non-null string (updateStreamTail coerces null to "")
        for (var op : ops) {
            switch (op) {
                case UiOp.Append append -> lines.addAll(append.lines());
                case UiOp.Prompt p -> finalSnapshot = p.snapshot();
                case UiOp.Tail t -> tailUpdate = t.tail();
                default -> {}
            }
        }
        if (tailUpdate != null)
            finalSnapshot = finalSnapshot.withStreamTail(tailUpdate);
        prompt = finalSnapshot;
        emitFrame(width, height, finalSnapshot, lines, true, true);
    }

    private void emitFrame(int width, int height, PromptSnapshot snapshot, List<StyledLine> lines, boolean erase,
                                    boolean showPrompt) {
        terminal.writer().print(frameRenderer.renderFrame(width, height, snapshot, lines, erase, showPrompt, theme));
        terminal.writer().flush();
    }

    private void restore() {
        try {
            terminal.writer().print("\033[0m" + TerminalFrameRenderer.SHOW_CURSOR + "\r\n");
            terminal.writer().flush();
        } catch (Exception ignored) {}
    }

}
