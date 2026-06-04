package ai.pijota.tui;

import ai.pijota.theme.AnsiTheme;
import org.jline.terminal.Terminal;

import java.io.StringWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class TerminalCoordinator implements AutoCloseable {
    private sealed interface UiOp {
        record Append(List<StyledLine> lines) implements UiOp {}
        record Prompt(PromptSnapshot snapshot) implements UiOp {}
        record Stop() implements UiOp {}
    }

    private static final String HIDE_CURSOR = "\033[?25l";
    private static final String SHOW_CURSOR = "\033[?25h";
    private static final String CLEAR_LINE = "\r\033[K";

    // CSI 2026 / DECSET Synchronized Output — tells the terminal to buffer all
    // writes between BEGIN and END, then apply them as one atomic frame.
    // Eliminates flicker on Ghostty, Kitty, iTerm2, WezTerm, Alacritty, etc.
    // Terminals without support (xterm, older builds) ignore the sequences.
    // Spec: https://gist.github.com/christianparpart/d8a62cc1ab659194337d73e399004036
    private static final String BEGIN_SYNC = "\033[?2026h";
    private static final String END_SYNC = "\033[?2026l";

    private final Terminal terminal;
    private final PromptLayout layout;
    private volatile AnsiTheme theme;
    private final BlockingQueue<UiOp> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread writerThread;
    private PromptSnapshot prompt = new PromptSnapshot(List.of(""), 0, 0, List.of());

    public TerminalCoordinator(Terminal terminal, AnsiTheme theme, PromptLayout layout) {
        this.terminal = terminal;
        this.theme = theme;
        this.layout = layout;
    }

    public void start(PromptSnapshot initialPrompt) {
        prompt = initialPrompt;
        running.set(true);
        writerThread = Thread.ofPlatform().name("pijota-tui-writer").start(this::writerLoop);
        redrawPrompt(initialPrompt);
    }

    public void appendLines(List<StyledLine> lines) {
        if (!lines.isEmpty()) queue.offer(new UiOp.Append(lines));
    }

    public void redrawPrompt(PromptSnapshot snapshot) {
        queue.offer(new UiOp.Prompt(snapshot));
    }

    public void setTheme(AnsiTheme newTheme) {
        this.theme = newTheme;
    }

    @Override
    public void close() {
        if (!running.getAndSet(false)) return;
        queue.offer(new UiOp.Stop());
        try {
            if (writerThread != null) writerThread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        restore();
    }

    private void writerLoop() {
        try {
            var batch = new ArrayList<UiOp>();
            while (running.get() || !queue.isEmpty()) {
                UiOp first = queue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                if (first instanceof UiOp.Stop) return;
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
        var buf = new StringWriter();
        var out = new PrintWriter(buf);

        out.print(BEGIN_SYNC);
        out.print(HIDE_CURSOR);
        layout.eraseCurrent(out);

        PromptSnapshot finalSnapshot = prompt;

        for (var op : ops) {
            switch (op) {
                case UiOp.Append append -> {
                    for (var line : append.lines()) {
                        out.print(CLEAR_LINE);
                        out.print(line.text());
                        out.print("\r\n");
                    }
                }
                case UiOp.Prompt p -> {
                    prompt = p.snapshot();
                    finalSnapshot = p.snapshot();
                }
                default -> {}
            }
        }

        layout.renderAfterErase(out, width, theme, finalSnapshot);
        out.print(SHOW_CURSOR);
        out.print(END_SYNC);
        terminal.writer().print(buf.toString());
        terminal.writer().flush();
    }

    private void restore() {
        try {
            terminal.writer().print("\033[0m" + SHOW_CURSOR + "\r\n");
            terminal.writer().flush();
        } catch (Exception ignored) {}
    }

}
