package ai.pijota.tui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The animated "working" spinner shown while the agent is busy. Owns the frame counter and the selected animation
 * style; frame advancement is driven by the caller's tick. Pure and unit-testable. Styles are held in insertion order
 * so {@link #toggle()} cycles deterministically.
 */
final class WorkingAnimation {

    private static final Map<String, String[]> ANIMATIONS = new LinkedHashMap<>();

    static {
        ANIMATIONS.put("dots", new String[] {"●  ●●  ", " ● ●●  ", "  ●●●  ", "  ●● ● ", "  ●●  ●", "  ●● ● ",
                "  ●●●  ", " ● ●●  "});
        ANIMATIONS.put("moon", new String[] {"🌑 ", "🌒 ", "🌓 ", "🌔 ", "🌕 ", "🌖 ", "🌗 ", "🌘 "});
        ANIMATIONS.put("spinner", new String[] {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"});
    }

    static final List<String> NAMES = List.copyOf(ANIMATIONS.keySet());

    private final AtomicInteger frameIndex = new AtomicInteger();
    private volatile String style = "dots";

    String style() {
        return style;
    }

    /** Selects an animation style by name; unknown names are ignored. */
    void set(String name) {
        if (name != null && ANIMATIONS.containsKey(name))
            style = name;
    }

    /** Advances to the next style and returns its name. */
    String toggle() {
        int idx = NAMES.indexOf(style);
        style = NAMES.get((idx + 1) % NAMES.size());
        return style;
    }

    /** Advances the frame counter; call once per animation tick. */
    void advance() {
        frameIndex.incrementAndGet();
    }

    String frame() {
        String[] frames = ANIMATIONS.getOrDefault(style, ANIMATIONS.get("dots"));
        return frames[Math.floorMod(frameIndex.get(), frames.length)];
    }
}
