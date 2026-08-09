package org.pijota;

import org.pijota.agent.SessionManager;
import org.pijota.testkit.StdioCapture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code --export} against a session home of its own.
 *
 * <p>
 * Everywhere else a session is named by its id -- that is what the picker lists, what {@code /tree} prints and what
 * {@code --session} takes when resuming. Export resolved its argument with {@code Path.of} alone, so an id, the one
 * name a user has for a session, was the one thing it refused.
 */
class ExportSessionTest {

    @RegisterExtension
    StdioCapture stdio = new StdioCapture();

    @TempDir
    Path home;
    @TempDir
    Path out;
    private String savedHome;
    private String id;
    private Path sessionFile;

    @BeforeEach
    void writeASession() throws IOException {
        savedHome = System.setProperty("pijota.home", home.toString());
        // Created through SessionManager, so this never spells the on-disk layout a second time. Where a session
        // lives for a cwd is that class's rule -- it flattens the path and strips the characters Windows forbids --
        // and a copy of it here would be a second truth to keep in step with the first.
        sessionFile = new SessionManager(home.resolve("sessions")).createSession(System.getProperty("user.dir"));
        id = sessionFile.getFileName().toString().replace(".jsonl", "");
        Files.writeString(sessionFile, "{\"id\":\"" + UUID.randomUUID() + "\",\"timestamp\":\"" + Instant.now()
                                        + "\",\"type\":\"message\",\"role\":\"user\",\"content\":\"hello there\"}\n",
                                        StandardOpenOption.APPEND);
    }

    @AfterEach
    void restoreHome() {
        if (savedHome != null)
            System.setProperty("pijota.home", savedHome);
        else
            System.clearProperty("pijota.home");
    }

    private int export(String... extra) {
        var argv = new java.util.ArrayList<String>(java.util.List.of("--export", out.resolve("o.md").toString()));
        argv.addAll(java.util.List.of(extra));
        return Main.run(argv.toArray(new String[0]));
    }

    @Test
    void aSessionIdIsAcceptedJustAsItIsForResuming() throws IOException {
        assertEquals(0, export("--session", id), "full id: " + stdio.err());
        assertTrue(Files.readString(out.resolve("o.md")).contains("hello there"));
    }

    @Test
    void soIsAUniquePrefixOfOne() throws IOException {
        assertEquals(0, export("--session", id.substring(0, 8)), "prefix: " + stdio.err());
        assertTrue(Files.readString(out.resolve("o.md")).contains("hello there"));
    }

    /** The only form that used to work has to keep working. */
    @Test
    void andAnExplicitPathStillWorks() {
        assertEquals(0, export("--session", sessionFile.toString()), stdio.err());
    }

    @Test
    void anIdThatMatchesNothingSaysSoRatherThanNamingAPathTheUserNeverTyped() {
        assertNotEquals(0, export("--session", "deadbeef"));
        assertTrue(stdio.err().contains("Session not found"), "should name the id, not a path: " + stdio.err());
    }

    /**
     * Without {@code --session} this takes the newest session in the directory, which for anyone working there is the
     * conversation they are in the middle of. It used to say only where the file went, so exporting a finished session
     * and copying out the live one printed the same line.
     */
    @Test
    void bareExportNamesTheSessionItHelpedItselfTo() {
        assertEquals(0, export(), stdio.err());
        String said = stdio.out();
        assertTrue(said.contains(id), "must name the session it picked: " + said);
        assertTrue(said.contains("newest in this directory"), "and that nobody asked for it: " + said);
    }
}
