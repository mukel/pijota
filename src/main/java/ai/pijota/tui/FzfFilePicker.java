package ai.pijota.tui;

import ai.pijota.util.Util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds the shell command for {@code @}-file completion via fzf. Pure (launches nothing) so it's unit-testable; the
 * TUI suspends the terminal, runs the script with {@code ProcessRunner.runInteractive}, then reads the chosen path from
 * the temp file. fzf draws its UI on {@code /dev/tty}, so the {@code > out} redirect captures only the selection.
 *
 * <p>
 * The file list is produced <em>once</em> and fzf filters it in-memory — no per-keystroke reload, so no flicker. Inside
 * a git repo the list is {@code git ls-files :/} (tracked + untracked-not-ignored, so no {@code .git}/build junk),
 * which emits paths <strong>relative to cwd, with {@code ../} for files above it</strong> — so {@code @../file} works
 * with no re-rooting, and selections are already correct for {@link FileReferenceResolver}. Outside a repo it falls
 * back to {@code find} from cwd (parent search isn't available there). fzf runs full-screen (alternate screen) for a
 * clean enter/exit rather than inline overdraw.
 */
final class FzfFilePicker {
    private FzfFilePicker() {}

    static boolean available() {
        return onPath("fzf");
    }

    /**
     * The initial file list: the whole repo, cwd-relative ({@code ../} for in-repo parents), gitignore-aware; find is
     * the non-repo fallback. <strong>NUL-delimited</strong> ({@code -z} / {@code -print0}) so filenames with spaces,
     * quotes or other specials survive verbatim — {@code git ls-files} would otherwise C-quote them (e.g.
     * {@code "a \"b\" c.txt"}). Reused by tests to exercise the exact pipeline non-interactively via {@code fzf -f}.
     */
    static String listCommand() {
        // git first: it uniquely lists the whole repo cwd-relative (../ for in-repo parents). Outside a repo, prefer fd
        // (gitignore/.ignore-aware, fast; `fdfind` is the Debian binary name) and fall back to find. Each tool exits
        // non-zero when absent (127) so `||` advances; all emit NUL so odd filenames survive.
        return "git ls-files -z --cached --others --exclude-standard :/ 2>/dev/null"
                                        + " || fd --type f --hidden --exclude .git -0 2>/dev/null"
                                        + " || fdfind --type f --hidden --exclude .git -0 2>/dev/null"
                                        + " || find . -type f -not -path '*/.git/*' -print0 2>/dev/null";
    }

    /**
     * The shell script: seed a static list → fzf (in-memory filter) → re-root only when the query ends in {@code /}.
     */
    static String script(Path out) {
        // fzf filters the static list in-memory → no per-keystroke reload → no flicker. Re-root ONLY when the query
        // completes a directory (ends with /), e.g. `../` or `src/`: this reaches the real filesystem dir — including
        // parents above / outside the repo that the static list can't see — and fires at most once per directory hop.
        String reroot = "q={q}; case \"$q\" in */) echo \"reload(find \\\"${q%/}\\\" -type f"
                                        + " -not -path '*/.git/*' -print0 2>/dev/null)\";; esac";
        return "fzf --read0 --print0 --reverse --prompt='@ ' --no-multi" + " --bind "
                                        + Util.shellQuote("start:reload:" + listCommand()) + " --bind "
                                        + Util.shellQuote("change:transform:" + reroot) + " > "
                                        + Util.shellQuote(out.toString());
    }

    /** The chosen path from fzf's {@code --print0} output: the bytes before the first NUL, or {@code ""} on cancel. */
    static String selectionFrom(String fzfOutput) {
        int nul = fzfOutput.indexOf('\0');
        return nul >= 0 ? fzfOutput.substring(0, nul) : fzfOutput.strip(); // keep inner whitespace; NUL is the
                                                                           // delimiter
    }

    /**
     * Formats a selected path for the input buffer. {@code asReference} → an {@code @}-reference (quoted/escaped, read
     * as a file); otherwise the raw path shell-encoded (a leading {@code ./} trimmed), for use as plain text — the
     * Ctrl+T behavior.
     */
    static String insertion(String selection, boolean asReference) {
        if (asReference)
            return FileReferenceResolver.reference(selection);
        String path = selection.startsWith("./") ? selection.substring(2) : selection;
        return Util.shellQuoteIfNeeded(path);
    }

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null)
            return false;
        for (String dir : path.split(File.pathSeparator))
            if (!dir.isBlank() && Files.isExecutable(Path.of(dir, exe)))
                return true;
        return false;
    }
}
