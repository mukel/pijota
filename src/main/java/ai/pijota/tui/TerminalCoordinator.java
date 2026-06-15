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
        record Tail(String tail) implements UiOp {
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

        // Coalesce the whole batch into ONE frame: erase the prompt once, append every committed line in order, then
        // redraw the prompt once. Rendering each append as its own frame (erase early, redraw prompt only on the last)
        // briefly leaves the prompt region erased between frames — visible flicker when ops interleave during
        // streaming. Streaming motion now comes from the live tail, not per-line frame emission, so one frame suffices.
        PromptSnapshot finalSnapshot = prompt;
        var lines = new ArrayList<StyledLine>();
        boolean fullFrame = false;
        String tailUpdate = null;
        for (var op : ops) {
            switch (op) {
                case UiOp.Append append -> {
                    lines.addAll(append.lines());
                    fullFrame = true;
                }
                case UiOp.Prompt p -> {
                    prompt = p.snapshot();
                    finalSnapshot = p.snapshot();
                    fullFrame = true;
                }
                case UiOp.Tail t -> tailUpdate = t.tail();
                default -> {}
            }
        }

        // A batch of only tail updates repaints the single live-tail row in place — no whole-region blank, so streaming
        // tokens cost one line instead of a full erase+redraw and never strobe. Falls back to a full frame the first
        // time the tail appears (no row to overwrite yet) so the geometry is established before in-place updates take
        // over; any committed line (Append) or prompt change forces a full frame too (correct for wrapping).
        if (!fullFrame && tailUpdate != null) {
            // In-place only while the tail still fits one row; once it would wrap, fall back to a (flicker-free) full
            // repaint that lays it out wrapped, instead of horizontally scrolling a single truncated line.
            if (layout.canUpdateTailInPlace() && AnsiWidth.visibleWidth(tailUpdate) <= width) {
                emitTailFrame(width, tailUpdate);
            } else {
                prompt = prompt.withStreamTail(tailUpdate);
                emitFrame(width, prompt, List.of(), true, true);
            }
            return;
        }
        emitFrame(width, finalSnapshot, lines, true, true);
    }

    private void emitTailFrame(int width, String tail) {
        terminal.writer().print(frameRenderer.renderTailFrame(width, tail, theme));
        terminal.writer().flush();
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
