package org.pijota.tui;

import org.pijota.util.PijotaHome;
import org.pijota.util.Util;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Loads simple action -> key mappings from {@code ~/.pijota/keybindings.json}. */
final class UserKeybindings {
    private static final Logger log = LoggerFactory.getLogger(UserKeybindings.class);

    private final Map<String, List<Key.Special>> bindings;

    private UserKeybindings(Map<String, List<Key.Special>> bindings) {
        this.bindings = bindings;
    }

    static UserKeybindings load() {
        return load(PijotaHome.resolve("keybindings.json"));
    }

    static UserKeybindings load(Path path) {
        var map = new LinkedHashMap<String, List<Key.Special>>();
        if (path == null || !Files.isRegularFile(path))
            return new UserKeybindings(map);
        try {
            JsonNode root = Util.MAPPER.readTree(Files.readString(path));
            if (!root.isObject())
                return new UserKeybindings(map);
            root.fields().forEachRemaining(entry -> {
                var keys = parseKeys(entry.getKey(), entry.getValue());
                if (!keys.isEmpty())
                    map.put(entry.getKey(), keys);
            });
        } catch (Exception ignored) {
            // Bad user keybinding files should not prevent the TUI from starting.
        }
        return new UserKeybindings(map);
    }

    List<Key.Special> keysFor(String action, Key.Special... defaults) {
        var configured = bindings.get(action);
        if (configured != null)
            return configured;
        return List.of(defaults);
    }

    Map<Key.Special, Key.Special> remaps(Map<String, Key.Special> actions) {
        var out = new LinkedHashMap<Key.Special, Key.Special>();
        actions.forEach((action, target) -> {
            var configured = bindings.get(action);
            if (configured != null)
                configured.forEach(key -> out.put(key, target));
        });
        return Map.copyOf(out);
    }

    private static List<Key.Special> parseKeys(String action, JsonNode node) {
        var out = new ArrayList<Key.Special>();
        if (node == null)
            return out;
        if (node.isTextual()) {
            take(action, node.asText(), out);
            return out;
        }
        if (node.isArray()) {
            for (JsonNode item : node)
                if (item.isTextual())
                    take(action, item.asText(), out);
        }
        return List.copyOf(out);
    }

    /**
     * Keeps a key that parses, and says so when one does not.
     *
     * <p>
     * An unusable name used to be dropped without a word, so the binding silently did not exist and the default kept
     * working -- the one outcome that looks exactly like "my file was ignored". Not every {@code ctrl+} name is
     * bindable and that is deliberate: {@code ctrl+m} is Enter, {@code ctrl+h} is backspace, {@code ctrl+c},
     * {@code ctrl+d} and {@code ctrl+z} are how a terminal interrupts, ends input and suspends. Binding those would
     * take the keyboard apart. Saying which names do work is the difference between a rule and a mystery.
     */
    private static void take(String action, String raw, List<Key.Special> out) {
        parseKey(raw).ifPresentOrElse(out::add,
                                        () -> log.warn("keybindings.json: {} is not a key pijota can bind, so {} keeps"
                                                                        + " its default. Bindable names: {}", raw,
                                                                        action, String.join(", ", BINDABLE.keySet())));
    }

    /**
     * The names a binding may use, and the keys they mean.
     *
     * <p>
     * One table, so the parser and the message that lists the choices cannot disagree. Written as a switch, the two
     * were kept in step by hand, which is how a settings warning elsewhere in this codebase ended up offering three
     * values that did not exist and rejecting one that did.
     *
     * <p>
     * Not every {@code ctrl+} name is here, and that is deliberate rather than missing: {@code ctrl+m} is Enter,
     * {@code ctrl+h} is backspace, and {@code ctrl+c}, {@code ctrl+d} and {@code ctrl+z} are how a terminal interrupts,
     * ends input and suspends. Binding those would take the keyboard apart.
     */
    private static final Map<String, Key.Special> BINDABLE = bindable();

    private static Map<String, Key.Special> bindable() {
        var m = new LinkedHashMap<String, Key.Special>();
        m.put("ctrl+g", Key.Special.CTRL_G);
        m.put("ctrl+l", Key.Special.CTRL_L);
        m.put("ctrl+o", Key.Special.CTRL_O);
        m.put("ctrl+p", Key.Special.CTRL_P);
        m.put("ctrl+t", Key.Special.CTRL_T);
        m.put("ctrl+a", Key.Special.CTRL_A);
        m.put("ctrl+e", Key.Special.CTRL_E);
        m.put("ctrl+k", Key.Special.CTRL_K);
        m.put("ctrl+r", Key.Special.CTRL_R);
        m.put("ctrl+u", Key.Special.CTRL_U);
        m.put("ctrl+w", Key.Special.CTRL_W);
        m.put("ctrl+y", Key.Special.CTRL_Y);
        m.put("ctrl+left", Key.Special.CTRL_LEFT);
        m.put("ctrl+right", Key.Special.CTRL_RIGHT);
        m.put("ctrl+shift+p", Key.Special.CTRL_SHIFT_P);
        m.put("shift+ctrl+p", Key.Special.CTRL_SHIFT_P);
        m.put("shift+tab", Key.Special.SHIFT_TAB);
        m.put("alt+up", Key.Special.ALT_UP);
        m.put("alt+enter", Key.Special.ALT_ENTER);
        m.put("alt+return", Key.Special.ALT_ENTER);
        m.put("alt+d", Key.Special.ALT_D);
        m.put("alt+y", Key.Special.ALT_Y);
        m.put("up", Key.Special.UP);
        m.put("down", Key.Special.DOWN);
        m.put("left", Key.Special.LEFT);
        m.put("right", Key.Special.RIGHT);
        m.put("home", Key.Special.HOME);
        m.put("end", Key.Special.END);
        m.put("pageup", Key.Special.PG_UP);
        m.put("page-up", Key.Special.PG_UP);
        m.put("pagedown", Key.Special.PG_DOWN);
        m.put("page-down", Key.Special.PG_DOWN);
        m.put("enter", Key.Special.ENTER);
        m.put("return", Key.Special.ENTER);
        m.put("tab", Key.Special.TAB);
        m.put("backspace", Key.Special.BACKSPACE);
        m.put("delete", Key.Special.DELETE);
        m.put("escape", Key.Special.ESC);
        m.put("esc", Key.Special.ESC);
        return Map.copyOf(m);
    }

    private static Optional<Key.Special> parseKey(String raw) {
        if (raw == null)
            return Optional.empty();
        return Optional.ofNullable(BINDABLE.get(raw.trim().toLowerCase(Locale.ROOT).replace(" ", "")));
    }
}
