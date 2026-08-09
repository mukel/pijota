package org.pijota.agent;

import org.pijota.llm.ThinkingLevel;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What a process pijota starts is told about the session that started it.
 *
 * <p>
 * Every variable here is part of a contract with whatever runs on the other side -- a bash tool command, an extension's
 * {@code exec} hook, a hook script someone wrote a year ago. It was built in two places that had to agree without
 * either saying so, down to nulling the same five keys first and lowercasing the reasoning level the same way, so
 * adding a variable meant remembering both and removing one meant finding both.
 *
 * <p>
 * The keys are seeded null before they are filled: an inherited {@code PIJOTA_MODEL} from an outer pijota must not show
 * through as this session's, and a null value is how {@code ProcessRunner} is told to unset rather than pass on.
 */
public final class ChildEnvironment {
    private ChildEnvironment() {}

    /** The variables, in the order they are documented. A null value means "unset this", not "leave it alone". */
    public static Map<String, String> of(String provider, String model, ThinkingLevel thinking, Path sessionFile) {
        var env = new LinkedHashMap<String, String>();
        env.put("PIJOTA_PROVIDER", provider);
        env.put("PIJOTA_MODEL", model);
        env.put("PIJOTA_REASONING_LEVEL", thinking == null ? null : thinking.name().toLowerCase(Locale.ROOT));
        env.put("PIJOTA_SESSION_FILE", sessionFile == null ? null : sessionFile.toString());
        env.put("PIJOTA_SESSION_ID", sessionFile == null ? null : sessionId(sessionFile));
        env.put("PIJOTA_CODING_AGENT", "true");
        return env;
    }

    /** A session's id is its file name; the extension is noise to anything reading the variable. */
    static String sessionId(Path sessionFile) {
        String name = sessionFile.getFileName().toString();
        return name.endsWith(".jsonl") ? name.substring(0, name.length() - ".jsonl".length()) : name;
    }
}
