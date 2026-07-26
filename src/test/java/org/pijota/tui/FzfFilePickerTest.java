package org.pijota.tui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FzfFilePickerTest {

    @Test
    void scriptSeedsAStaticListAndRerootsOnlyOnADirectory() {
        String s = FzfFilePicker.script(Path.of("/tmp/sel.txt"));
        assertTrue(s.contains("fzf"), s);
        assertTrue(s.contains("--read0") && s.contains("--print0"), "NUL-delimited so odd filenames survive verbatim");
        assertTrue(s.contains("git ls-files -z"), "static, raw (un-quoted) git list, cwd-relative incl ../");
        assertTrue(s.contains("-type d") && s.contains("find"), "lists directories too (fd/find), not only files");
        assertTrue(s.contains("change:transform"), "re-root via transform, not per-keystroke reload"); // flicker fix
        assertTrue(s.contains("*/)"), "re-root only when the query ends in / → reaches @../ outside the repo");
        assertFalse(s.contains("change:reload"), "no per-keystroke reload → no flicker");
        assertTrue(s.contains("/tmp/sel.txt"), "selection captured to the temp file");
    }

    @Test
    void availableDoesNotThrow() {
        FzfFilePicker.available(); // PATH-dependent; just exercise the lookup
    }

    @Test
    void listCommandIncludesDirectoriesNotOnlyFiles(@TempDir Path tmp) throws Exception {
        run(tmp, "git", "init", "-q", "."); // exercise the in-repo branch (git files + fd/find dirs)
        Files.createDirectory(tmp.resolve("mymodule"));
        Files.writeString(tmp.resolve("mymodule/x.txt"), "x");

        var p = new ProcessBuilder("sh", "-c", "{ " + FzfFilePicker.listCommand() + " ; }").directory(tmp.toFile())
                                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        byte[] out = p.getInputStream().readAllBytes();
        p.waitFor();
        var entries = List.of(new String(out, StandardCharsets.UTF_8).split("\0"));

        assertTrue(entries.stream().map(e -> e.replaceFirst("^\\./", "").replaceFirst("/$", ""))
                                        .anyMatch(e -> e.equals("mymodule")),
                                        "the folder itself is selectable, not only its files: " + entries);
        assertTrue(entries.stream().anyMatch(e -> e.endsWith("x.txt")), "files still listed: " + entries);
    }

    @Test
    void selectionFromTakesThePathBeforeTheNul() {
        assertEquals("a/b.txt", FzfFilePicker.selectionFrom("a/b.txt\0"));
        assertEquals("my file.txt", FzfFilePicker.selectionFrom("my file.txt\0")); // inner whitespace preserved
        assertEquals("  pad  ", FzfFilePicker.selectionFrom("  pad  \0")); // NUL delimits → keep surrounding spaces
        assertEquals("first", FzfFilePicker.selectionFrom("first\0second\0")); // first of multiple
        assertEquals("", FzfFilePicker.selectionFrom("")); // cancel (Esc) → empty
        assertEquals("nonul", FzfFilePicker.selectionFrom("  nonul  ")); // no NUL → stripped fallback
    }

    @Test
    void insertionIsAnAtReferenceForAtAndAShellPathForCtrlT() {
        // @ mode → @-reference: slash-less gets ./, spaces quoted+escaped, resolved as a file
        assertEquals("@src/Foo.java", FzfFilePicker.insertion("src/Foo.java", true));
        assertEquals("@./pom.xml", FzfFilePicker.insertion("pom.xml", true));
        assertEquals("@\"./my file.txt\"", FzfFilePicker.insertion("my file.txt", true));

        // Ctrl+T mode → shell-encoded raw path: leading ./ trimmed, quote-when-needed, no @
        assertEquals("pom.xml", FzfFilePicker.insertion("./pom.xml", false));
        assertEquals("src/Foo.java", FzfFilePicker.insertion("src/Foo.java", false));
        assertEquals("../sib/x.txt", FzfFilePicker.insertion("../sib/x.txt", false)); // safe → bare
        assertEquals("'my file.txt'", FzfFilePicker.insertion("./my file.txt", false)); // space → quoted
        assertEquals("'John'\\''s.txt'", FzfFilePicker.insertion("John's.txt", false)); // apostrophe escaped
        assertEquals("'a \"b\" c.txt'", FzfFilePicker.insertion("a \"b\" c.txt", false)); // quotes literal in '...'
    }

    // --- end-to-end against the REAL fzf (non-interactive --filter); skipped when fzf isn't installed --------------

    private record Case(String filename, String query) {
    }

    @Test
    void oddFilenamesRoundTripThroughRealFzfAndTheResolver(@TempDir Path tmp) throws Exception {
        assumeTrue(FzfFilePicker.available(), "fzf not installed");
        run(tmp, "git", "init", "-q", "."); // exercise the git-ls-files lister path

        for (var c : List.of(new Case("pom.xml", "pom"), // slash-less top-level
                                        new Case("my file.txt", "my file"), // space
                                        new Case("John's.txt", "John"), // apostrophe
                                        new Case("re@port.txt", "re@"), // @ inside the name
                                        new Case("a \"b\" c.txt", "b c"), // double-quote + space (git would C-quote it)
                                        new Case("data (1).txt", "data ("))) { // parens + space
            Files.writeString(tmp.resolve(c.filename()), "RT:" + c.filename());

            // 1) the real fzf returns the raw path verbatim through the NUL pipeline
            String selection = fzfFilter(tmp, c.query());
            assertEquals(c.filename(), selection, "fzf returned the wrong path for query '" + c.query() + "'");

            // 2) it round-trips through the @-syntax (reference → expand) to actually resolve the file
            String expanded = new FileReferenceResolver(tmp).expand(FileReferenceResolver.reference(selection)).text();
            assertTrue(expanded.contains("RT:" + c.filename()), "did not resolve " + selection);

            Files.delete(tmp.resolve(c.filename())); // keep each query unambiguous (one candidate at a time)
        }
    }

    @Test
    void oddFilenamesAlsoWorkOutsideAGitRepoViaTheFindFallback(@TempDir Path tmp) throws Exception {
        assumeTrue(FzfFilePicker.available(), "fzf not installed");
        // No `git init`: under the system temp dir there's no .git, so `git ls-files` fails and the find fallback runs
        // (NUL-delimited, ./-prefixed). Asserts on round-trip resolution, which holds for either lister.
        for (var c : List.of(new Case("my file.txt", "my file"), new Case("re@port.txt", "re@"),
                                        new Case("a \"b\" c.txt", "b c"))) {
            Files.writeString(tmp.resolve(c.filename()), "RT:" + c.filename());
            String selection = fzfFilter(tmp, c.query());
            assertTrue(selection.endsWith(c.filename()), "find fallback returned '" + selection + "'");
            String expanded = new FileReferenceResolver(tmp).expand(FileReferenceResolver.reference(selection)).text();
            assertTrue(expanded.contains("RT:" + c.filename()), "did not resolve " + selection);
            Files.delete(tmp.resolve(c.filename()));
        }
    }

    /** Runs the exact list pipeline through fzf's non-interactive filter and returns the top match (NUL-delimited). */
    private static String fzfFilter(Path cwd, String query) throws Exception {
        // group the `git || fd || find` chain so the WHOLE result pipes to fzf (else `A || B | C` = `A || (B | C)`)
        String cmd = "{ " + FzfFilePicker.listCommand() + " ; } | fzf --read0 --print0 --filter '" + query + "'";
        var p = new ProcessBuilder("sh", "-c", cmd).directory(cwd.toFile())
                                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        byte[] out = p.getInputStream().readAllBytes();
        p.waitFor();
        String all = new String(out, StandardCharsets.UTF_8);
        int nul = all.indexOf('\0');
        return nul >= 0 ? all.substring(0, nul) : all.strip();
    }

    private static void run(Path cwd, String... cmd) throws Exception {
        new ProcessBuilder(cmd).directory(cwd.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD)
                                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
    }
}
