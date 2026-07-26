package org.pijota.tui;

import org.pijota.util.PijotaHome;
import org.pijota.util.Util;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Loads simple action -> key mappings from {@code ~/.pijota/keybindings.json}. */
final class UserKeybindings {
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
                var keys = parseKeys(entry.getValue());
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

    private static List<Key.Special> parseKeys(JsonNode node) {
        var out = new ArrayList<Key.Special>();
        if (node == null)
            return out;
        if (node.isTextual()) {
            parseKey(node.asText()).ifPresent(out::add);
            return out;
        }
        if (node.isArray()) {
            for (JsonNode item : node)
                if (item.isTextual())
                    parseKey(item.asText()).ifPresent(out::add);
        }
        return List.copyOf(out);
    }

    private static java.util.Optional<Key.Special> parseKey(String raw) {
        if (raw == null)
            return java.util.Optional.empty();
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        return java.util.Optional.ofNullable(switch (s) {
            case "ctrl+g" -> Key.Special.CTRL_G;
            case "shift+tab" -> Key.Special.SHIFT_TAB;
            case "ctrl+l" -> Key.Special.CTRL_L;
            case "ctrl+p" -> Key.Special.CTRL_P;
            case "ctrl+t" -> Key.Special.CTRL_T;
            case "ctrl+o" -> Key.Special.CTRL_O;
            case "ctrl+shift+p", "shift+ctrl+p" -> Key.Special.CTRL_SHIFT_P;
            case "alt+up" -> Key.Special.ALT_UP;
            case "alt+enter", "alt+return" -> Key.Special.ALT_ENTER;
            case "up" -> Key.Special.UP;
            case "down" -> Key.Special.DOWN;
            case "left" -> Key.Special.LEFT;
            case "right" -> Key.Special.RIGHT;
            case "home" -> Key.Special.HOME;
            case "end" -> Key.Special.END;
            case "pageup", "page-up" -> Key.Special.PG_UP;
            case "pagedown", "page-down" -> Key.Special.PG_DOWN;
            case "enter", "return" -> Key.Special.ENTER;
            case "tab" -> Key.Special.TAB;
            case "backspace" -> Key.Special.BACKSPACE;
            case "delete" -> Key.Special.DELETE;
            case "escape", "esc" -> Key.Special.ESC;
            case "ctrl+a" -> Key.Special.CTRL_A;
            case "ctrl+e" -> Key.Special.CTRL_E;
            case "ctrl+k" -> Key.Special.CTRL_K;
            case "ctrl+r" -> Key.Special.CTRL_R;
            case "ctrl+u" -> Key.Special.CTRL_U;
            case "ctrl+w" -> Key.Special.CTRL_W;
            case "ctrl+y" -> Key.Special.CTRL_Y;
            case "alt+d" -> Key.Special.ALT_D;
            case "alt+y" -> Key.Special.ALT_Y;
            case "ctrl+left" -> Key.Special.CTRL_LEFT;
            case "ctrl+right" -> Key.Special.CTRL_RIGHT;
            default -> null;
        });
    }
}
