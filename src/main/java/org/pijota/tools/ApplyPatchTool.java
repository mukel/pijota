package org.pijota.tools;

import org.pijota.config.PathDisplay;
import org.pijota.llm.ToolSchema;
import org.pijota.util.Util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

/**
 * Applies a unified diff (git-style: {@code --- a/file} / {@code +++ b/file} headers, {@code @@} hunks) across one or
 * more files. Complements {@code edit} (single exact-match replacements): use this for multi-hunk or multi-file
 * changes, or when the model already produced a diff.
 *
 * <p>
 * Hunks are located by their <em>context</em> (the {@code @@} line numbers are treated only as hints), so a patch still
 * applies when line numbers have drifted. The whole patch is computed before anything is written — if any hunk fails to
 * apply, no file is modified. Existing files keep their original line endings; {@code --- /dev/null} creates a file and
 * {@code +++ /dev/null} deletes one.
 */
public final class ApplyPatchTool implements Tool {

    @Override
    public String name() {
        return "apply_patch";
    }

    @Override
    public String glyph() {
        return "🩹";
    }

    @Override
    public String description() {
        return "Apply a unified diff (git-style: '--- a/file', '+++ b/file', '@@' hunks) to one or more files. "
                                        + "Use for multi-hunk or multi-file changes; for a single targeted change prefer edit. "
                                        + "Hunks match by context (line numbers are hints); the patch is all-or-nothing.";
    }

    @Override
    public Map<String, Object> schema() {
        return ToolSchema.object().str("patch", "A unified diff to apply (one or more file sections).", true).build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(String toolCallId, Args args, String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String patch = args.getString("patch");
                if (patch == null || patch.isBlank())
                    return ToolResult.error("No patch provided");
                List<FilePatch> files = parse(patch);
                if (files.isEmpty())
                    return ToolResult.error("No file sections found. Expected '--- ' / '+++ ' headers.");

                // Pass 1: compute every file's new state. Any failure aborts here, before a single write.
                var planned = new ArrayList<Planned>();
                for (FilePatch fp : files)
                    planned.add(plan(cwd, fp));

                // Pass 2: write. Each file's mutation is serialized through the shared queue.
                for (Planned p : planned)
                    FileMutationQueue.run(p.path(), () -> {
                        if (p.content() == null) {
                            Files.deleteIfExists(p.path());
                        } else {
                            if (p.path().getParent() != null)
                                Files.createDirectories(p.path().getParent());
                            Files.writeString(p.path(), p.content(), StandardOpenOption.CREATE,
                                                            StandardOpenOption.TRUNCATE_EXISTING);
                        }
                        return null;
                    });

                var labels = planned.stream().map(Planned::label).toList();
                var details = new LinkedHashMap<String, Object>();
                details.put("files", labels);
                return ToolResult.success("Applied patch to " + Util.count(labels.size(), "file", "files") + ": "
                                                + String.join(", ", labels), details);
            } catch (PatchException e) {
                return ToolResult.error(e.getMessage());
            } catch (Exception e) {
                return ToolResult.error("apply_patch error: " + e.getMessage());
            }
        });
    }

    // ---- planning (no I/O side effects beyond reads) -----------------------------------------------------------

    private static Planned plan(String cwd, FilePatch fp) throws Exception {
        if (fp.newPath() == null) // +++ /dev/null → delete
            return new Planned(resolve(cwd, fp.oldPath()), null, "deleted "
                                            + ToolLabels.displayPath(fp.oldPath(), cwd, PathDisplay.COMPACT));
        if (fp.oldPath() == null) { // --- /dev/null → create
            var added = fp.hunks().stream().flatMap(h -> h.lines().stream()).filter(l -> l.type() == '+')
                                            .map(Line::text).toList();
            String body = String.join("\n", added);
            return new Planned(resolve(cwd, fp.newPath()), body.isEmpty() ? "" : body + "\n", "created "
                                            + ToolLabels.displayPath(fp.newPath(), cwd, PathDisplay.COMPACT));
        }
        Path path = resolve(cwd, fp.oldPath());
        if (!Files.exists(path) || !Files.isRegularFile(path))
            throw new PatchException("apply_patch: file not found: " + fp.oldPath());
        if (!Files.isReadable(path) || !Files.isWritable(path)) // match EditTool's read/write gate
            throw new PatchException("apply_patch: cannot read/write: " + fp.oldPath());
        String original = Files.readString(path);
        String lineEnding = Util.detectLineEnding(original);
        var lines = new ArrayList<>(List.of(Util.normalizeToLF(original).split("\n", -1)));

        int cursor = 0;
        for (Hunk h : fp.hunks()) {
            List<String> before = h.lines().stream().filter(l -> l.type() != '+').map(Line::text).toList();
            List<String> after = h.lines().stream().filter(l -> l.type() != '-').map(Line::text).toList();
            int idx = indexOfSublist(lines, before, cursor);
            if (idx < 0) // hunks may be out of order relative to the cursor — retry from the top
                idx = indexOfSublist(lines, before, 0);
            if (idx < 0)
                throw new PatchException("apply_patch: hunk does not apply to " + fp.oldPath()
                                                + " (context not found). Re-read the file and regenerate the diff.");
            for (int k = 0; k < before.size(); k++)
                lines.remove(idx);
            lines.addAll(idx, after);
            cursor = idx + after.size();
        }
        return new Planned(path, Util.restoreLineEndings(String.join("\n", lines), lineEnding),
                                        "patched " + ToolLabels.displayPath(fp.oldPath(), cwd, PathDisplay.COMPACT));
    }

    /** First index >= {@code from} where {@code sub} occurs as a contiguous block in {@code list}; -1 if absent. */
    private static int indexOfSublist(List<String> list, List<String> sub, int from) {
        int start = Math.min(Math.max(from, 0), list.size());
        if (sub.isEmpty())
            return start; // pure-insertion hunk: anchor at the cursor
        int rel = Collections.indexOfSubList(list.subList(start, list.size()), sub);
        return rel < 0 ? -1 : rel + start;
    }

    // ---- parsing -----------------------------------------------------------------------------------------------

    private static List<FilePatch> parse(String patch) {
        var out = new ArrayList<FilePatch>();
        String[] lines = Util.normalizeToLF(patch).split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            if (!lines[i].startsWith("--- ")) {
                i++;
                continue;
            }
            String oldPath = stripPathPrefix(lines[i].substring(4));
            i++;
            if (i >= lines.length || !lines[i].startsWith("+++ "))
                throw new PatchException("apply_patch: malformed patch. '---' not followed by '+++'.");
            String newPath = stripPathPrefix(lines[i].substring(4));
            i++;
            var hunks = new ArrayList<Hunk>();
            while (i < lines.length && lines[i].startsWith("@@")) {
                i++; // the @@ -a,b +c,d @@ header — we match by content, so the numbers are ignored
                var hl = new ArrayList<Line>();
                while (i < lines.length && !lines[i].startsWith("@@") && !lines[i].startsWith("--- ")) {
                    String l = lines[i];
                    if (l.startsWith("\\")) { // "\ No newline at end of file"
                        i++;
                        continue;
                    }
                    if (l.isEmpty())
                        break; // a bare blank line ends the hunk (git prefixes real context with a space)
                    char t = l.charAt(0);
                    if (t == ' ' || t == '+' || t == '-')
                        hl.add(new Line(t, l.substring(1)));
                    else
                        break;
                    i++;
                }
                hunks.add(new Hunk(List.copyOf(hl)));
            }
            out.add(new FilePatch(oldPath, newPath, List.copyOf(hunks)));
        }
        return out;
    }

    private static String stripPathPrefix(String raw) {
        String p = raw.split("\t")[0].trim(); // git appends a tab + timestamp on some diffs
        if (p.equals("/dev/null"))
            return null;
        if (p.startsWith("a/") || p.startsWith("b/"))
            return p.substring(2);
        return p;
    }

    private static Path resolve(String cwd, String rel) {
        Path p = Path.of(rel);
        return p.isAbsolute() ? p : Util.resolveDir(cwd).resolve(p);
    }

    private record Line(char type, String text) {
    }

    private record Hunk(List<Line> lines) {
    }

    private record FilePatch(String oldPath, String newPath, List<Hunk> hunks) {
    }

    /** A computed file mutation: {@code content == null} means delete, otherwise write {@code content}. */
    private record Planned(Path path, String content, String label) {
    }

    private static final class PatchException extends RuntimeException {
        PatchException(String message) {
            super(message);
        }
    }
}
