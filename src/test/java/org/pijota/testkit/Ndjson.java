package org.pijota.testkit;

import org.pijota.util.Util;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Map;

/**
 * Parses NDJSON output (one JSON object per line, e.g. the headless agent / sub-agent event stream) into typed maps,
 * so tests assert on fields ({@code event.get("type")}) instead of brittle raw-substring checks on the serialized JSON.
 */
public final class Ndjson {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private Ndjson() {}

    /** Every JSON-object line parsed into a map, in order; non-JSON lines (logs, banners) are skipped. */
    public static List<Map<String, Object>> parse(String output) {
        return output.lines().map(String::strip).filter(l -> l.startsWith("{")).map(Ndjson::read).toList();
    }

    /** The first event whose {@code "type"} field equals {@code type}; fails with the full output if none. */
    public static Map<String, Object> event(String output, String type) {
        return parse(output).stream().filter(m -> type.equals(m.get("type"))).findFirst().orElseThrow(
                                        () -> new AssertionError("no '" + type + "' event in:\n" + output));
    }

    private static Map<String, Object> read(String line) {
        try {
            return Util.MAPPER.readValue(line, MAP);
        } catch (Exception e) {
            throw new AssertionError("not valid JSON: " + line, e);
        }
    }
}
