package org.pijota.agent;

import org.pijota.llm.ThinkingLevel;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ChildEnvironmentTest {

    /**
     * The variable names are a contract with whatever runs on the other side -- a hook script someone wrote a year ago
     * reads these. They were built in two places that had to agree without either saying so; this is the test that
     * notices when they stop.
     */
    @Test
    void everyVariableAChildIsToldAbout() {
        var env = ChildEnvironment.of("anthropic", "claude-fable-5", ThinkingLevel.MEDIUM,
                                        Path.of("/home/u/.pijota/sessions/proj/abc-123.jsonl"));

        assertEquals("anthropic", env.get("PIJOTA_PROVIDER"));
        assertEquals("claude-fable-5", env.get("PIJOTA_MODEL"));
        assertEquals("medium", env.get("PIJOTA_REASONING_LEVEL"), "lowercased: scripts compare it");
        assertEquals("/home/u/.pijota/sessions/proj/abc-123.jsonl", env.get("PIJOTA_SESSION_FILE"));
        assertEquals("abc-123", env.get("PIJOTA_SESSION_ID"), "the id is the file name without the extension");
        assertEquals("true", env.get("PIJOTA_CODING_AGENT"));
        assertEquals(6, env.size(), "a new variable belongs in the test that documents them: " + env.keySet());
    }

    /**
     * A missing value is null, not absent. ProcessRunner reads null as "unset this", which is what stops an outer
     * pijota's PIJOTA_MODEL showing through as this session's when there is no session of our own.
     */
    @Test
    void whatIsUnknownIsUnsetRatherThanInherited() {
        var env = ChildEnvironment.of(null, null, null, null);

        assertEquals(java.util.Set.of("PIJOTA_PROVIDER", "PIJOTA_MODEL", "PIJOTA_REASONING_LEVEL",
                                        "PIJOTA_SESSION_FILE", "PIJOTA_SESSION_ID", "PIJOTA_CODING_AGENT"),
                                        env.keySet(), "every key is present so every one can be unset");
        assertNull(env.get("PIJOTA_PROVIDER"));
        assertNull(env.get("PIJOTA_REASONING_LEVEL"));
        assertNull(env.get("PIJOTA_SESSION_FILE"));
        assertNull(env.get("PIJOTA_SESSION_ID"), "no session file means no id, not a crash");
        assertEquals("true", env.get("PIJOTA_CODING_AGENT"), "this one is always true: it says what we are");
    }

    /** A session file without the usual extension still yields an id rather than losing its tail. */
    @Test
    void anIdIsTheFileNameWhateverItsExtension() {
        assertEquals("plain", ChildEnvironment.sessionId(Path.of("/tmp/plain")));
        assertEquals("a.b", ChildEnvironment.sessionId(Path.of("/tmp/a.b")));
        assertEquals("a.b", ChildEnvironment.sessionId(Path.of("/tmp/a.b.jsonl")));
    }
}
