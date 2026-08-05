package org.pijota.tools;

import org.pijota.llm.ToolSchema;
import org.pijota.util.DiffAlgorithm;
import org.pijota.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Replaces exact text in a file. Each match must be unique, so an ambiguous edit fails instead of changing the wrong
 * line.
 */
public final class EditTool implements Tool {

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public String glyph() {
        return ToolGlyphs.MUTATE;
    }

    @Override
    public boolean hasResultView() {
        return true;
    }

    @Override
    public String description() {
        return "Edit a file by exact text replacement. Each edits[].oldText must match a unique, non-overlapping "
                                        + "region of the file; merge nearby changes into one edit rather than overlapping, and don't pad "
                                        + "with large unchanged regions to bridge distant edits.";
    }

    @Override
    public Map<String, Object> schema() {
        var editItem = ToolSchema.object().str("oldText",
                                        "Exact text for one targeted replacement. It must be unique in the original file and must not overlap with any other edits[].oldText in the same call.",
                                        true).str("newText", "Replacement text for this targeted edit.", true)
                                        .buildOpen();
        return ToolSchema.object().str("path", "Path to the file to edit (relative or absolute)", true).array("edits",
                                        "One or more targeted replacements. Each edit is matched against the original file, not incrementally. Do not include overlapping or nested edits. If two changes touch the same block or nearby lines, merge them into one edit instead.",
                                        true, editItem).build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(String toolCallId, Args args, String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String pathStr = Util.resolvePath(args.map(), cwd);
                if (pathStr == null)
                    return ToolResult.error("No path provided");

                Path path = Path.of(pathStr);
                return FileMutationQueue.run(path, () -> editFile(path, args.map()));
            } catch (Exception e) {
                return ToolResult.error("Edit error: " + e.getMessage());
            }
        });
    }

    private static ToolResult editFile(Path path, Map<String, Object> args) throws IOException {
        if (!Files.isReadable(path) || !Files.isWritable(path))
            return ToolResult.error("Cannot read/write: " + path);

        var edits = extractEdits(args);
        if (edits.isEmpty())
            return ToolResult.error("Edit tool input is invalid. " + "edits must contain at least one replacement.");

        String original = Files.readString(path);
        String rawContent = stripBom(original);
        String lineEnding = Util.detectLineEnding(original);
        String content = Util.normalizeToLF(rawContent);

        var matches = new ArrayList<Match>();
        var errors = new ArrayList<String>();
        for (int i = 0; i < edits.size(); i++) {
            var edit = edits.get(i);
            String oldLF = Util.normalizeToLF(edit.oldText());
            String newLF = Util.normalizeToLF(edit.newText());

            int exactCount = Util.countOccurrences(content, oldLF);
            if (oldLF.isEmpty()) {
                errors.add("edit[" + i + "]: oldText must not be empty.");
            } else if (exactCount == 1) {
                matches.add(new Match(i, newLF, content.indexOf(oldLF), oldLF.length(), null));
            } else if (exactCount > 1) {
                errors.add("edit[" + i + "]: oldText matches " + exactCount
                                                + " locations (must be unique). Add more surrounding context.");
            } else {
                var fuzzy = fuzzyMatch(content, oldLF, newLF, i);
                if (fuzzy != null)
                    matches.add(fuzzy);
                else
                    errors.add("edit[" + i + "]: oldText not found (tried exact and whitespace/indentation-insensitive"
                                                    + " matching). Re-read the file and copy the text to replace verbatim.");
            }
        }

        if (!errors.isEmpty())
            return ToolResult.error(String.join("\n", errors));

        matches.sort(Comparator.comparingInt(Match::start));
        for (int i = 1; i < matches.size(); i++) {
            var previous = matches.get(i - 1);
            var current = matches.get(i);
            if (previous.start() + previous.length() > current.start()) {
                errors.add("edits[" + previous.index() + "] and edits[" + current.index()
                                                + "] overlap. Merge them into one edit or target disjoint regions.");
            }
        }
        if (!errors.isEmpty())
            return ToolResult.error(String.join("\n", errors));

        // Apply right-to-left so earlier offsets stay valid. Each match (exact or fuzzy) is a real [start, start+len)
        // span in the original content, so only the matched regions change and the rest of the file is byte-preserved.
        String result = content;
        matches.sort(Comparator.comparingInt(Match::start).reversed());
        for (var m : matches) {
            result = result.substring(0, m.start()) + m.newText() + result.substring(m.start() + m.length());
        }

        if (content.equals(result)) {
            return ToolResult.error("No changes made to " + path + ". The replacements produced identical content.");
        }

        Files.writeString(path, Util.restoreLineEndings(result, lineEnding), StandardOpenOption.TRUNCATE_EXISTING);

        var details = Map.<String, Object>of("diff", displayDiff(content, result), "patch",
                                        unifiedPatch(path.toString(), content, result), "firstChangedLine",
                                        firstChangedLine(content, result), "blocks", matches.size());
        // No path here: the tool-call detail already shows the file, so repeating it duplicates the path in the TUI.
        return ToolResult.success("Successfully replaced " + matches.size() + " block(s).", details);
    }

    @SuppressWarnings("unchecked")
    private static List<Edit> extractEdits(Map<String, Object> args) {
        var edits = new ArrayList<Edit>();
        Object editsObj = args.get("edits");
        if (editsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    String oldT = (String) m.get("oldText");
                    String newT = (String) m.get("newText");
                    if (oldT != null && newT != null)
                        edits.add(new Edit(oldT, newT));
                }
            }
        }
        // Also handle edits sent as JSON string
        if (edits.isEmpty() && editsObj instanceof String s) {
            try {
                @SuppressWarnings("unchecked")
                var parsed = (List<Map<String, Object>>) Util.MAPPER.readValue(s, List.class);
                for (var m : parsed) {
                    String oldT = (String) m.get("oldText");
                    String newT = (String) m.get("newText");
                    if (oldT != null && newT != null)
                        edits.add(new Edit(oldT, newT));
                }
            } catch (Exception e) {
                // Edits sent as JSON string failed to parse; fall through to legacy mode
            }
        }
        // Legacy single-edit: fold into edits[], stripping from args (Pi behavior)
        String oldStr = Util.getFirstString(args, "oldText", "oldString");
        String newStr = Util.getFirstString(args, "newText", "newString");
        if (oldStr != null && newStr != null) {
            args.remove("oldText");
            args.remove("oldString");
            args.remove("newText");
            args.remove("newString");
            edits.add(new Edit(oldStr, newStr));
        }
        return edits;
    }

    private record Edit(String oldText, String newText) {
    }

    /** A located replacement: overwrite {@code content[start, start+length)} with {@code newText}. */
    private record Match(int index, String newText, int start, int length, String fuzzyReason) {
    }

    /** Half-open char range of one line within the content, excluding its trailing newline. */
    private record Line(int start, int end) {
    }

    private static List<Line> splitLines(String s) {
        var lines = new ArrayList<Line>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                lines.add(new Line(start, i));
                start = i + 1;
            }
        }
        lines.add(new Line(start, s.length())); // trailing line (possibly empty)
        return lines;
    }

    /** Canonical key for one line: indentation/whitespace-insensitive, folding Unicode quote/dash/space variants. */
    private static String canonLine(String line) {
        return normalizeForFuzzyMatch(line).strip();
    }

    /**
     * Whole-line fuzzy match: locates the unique run of file lines whose canonical form equals oldText's lines,
     * ignoring leading/trailing whitespace (so a block copied at a different indent still matches) and Unicode
     * variants. Returns a real span in {@code content}, so only the matched region is rewritten. Null when there is no
     * match, more than one, or the pattern is blank.
     */
    private static Match fuzzyMatch(String content, String oldLF, String newLF, int idx) {
        var contentLines = splitLines(content);
        String[] patternLines = oldLF.split("\n", -1);
        String[] patternKey = new String[patternLines.length];
        boolean allBlank = true;
        for (int j = 0; j < patternLines.length; j++) {
            patternKey[j] = canonLine(patternLines[j]);
            if (!patternKey[j].isEmpty())
                allBlank = false;
        }
        if (allBlank)
            return null; // never anchor a fuzzy match on blank/whitespace-only oldText

        int foundLine = -1, count = 0;
        for (int i = 0; i + patternKey.length <= contentLines.size(); i++) {
            boolean ok = true;
            for (int j = 0; j < patternKey.length; j++) {
                var ln = contentLines.get(i + j);
                if (!canonLine(content.substring(ln.start(), ln.end())).equals(patternKey[j])) {
                    ok = false;
                    break;
                }
            }
            if (ok && ++count == 1)
                foundLine = i;
            if (count > 1)
                return null; // ambiguous; caller asks the model to add context
        }
        if (count != 1)
            return null;
        int start = contentLines.get(foundLine).start();
        int end = contentLines.get(foundLine + patternKey.length - 1).end();
        return new Match(idx, newLF, start, end - start, "whitespace/indentation");
    }

    private static String unifiedPatch(String path, String oldText, String newText) {
        var sb = new StringBuilder();
        sb.append("--- ").append(path).append('\n');
        sb.append("+++ ").append(path).append('\n');
        var rows = DiffAlgorithm.diff(oldText, newText);
        for (var hunk : DiffAlgorithm.hunks(rows, 4)) {
            int os = DiffAlgorithm.firstOld(hunk.rows());
            long oc = DiffAlgorithm.countOld(hunk.rows());
            int ns = DiffAlgorithm.firstNew(hunk.rows());
            long nc = DiffAlgorithm.countNew(hunk.rows());
            sb.append("@@ -").append(os).append(',').append(oc).append(" +").append(ns).append(',').append(nc)
                                            .append(" @@\n");
            for (var row : hunk.rows()) {
                char mark = switch (row.kind()) {
                    case ADD -> '+';
                    case DELETE -> '-';
                    case CONTEXT -> ' ';
                };
                sb.append(mark).append(row.text()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String displayDiff(String oldText, String newText) {
        var sb = new StringBuilder();
        var rows = DiffAlgorithm.diff(oldText, newText);
        int width = Integer.toString(Math.max(oldText != null ? oldText.split("\\R", -1).length : 0,
                                        newText != null ? newText.split("\\R", -1).length : 0)).length();
        for (var hunk : DiffAlgorithm.hunks(rows, 4)) {
            for (var row : hunk.rows()) {
                if (row.kind() == DiffAlgorithm.Kind.CONTEXT)
                    continue;
                int line = row.kind() == DiffAlgorithm.Kind.ADD ? row.newLine() : row.oldLine();
                sb.append(row.kind() == DiffAlgorithm.Kind.ADD ? '+' : '-')
                                                .append(String.format(Locale.ROOT, "%" + width + "d", line)).append(' ')
                                                .append(row.text()).append('\n');
            }
        }
        return sb.toString();
    }

    private static int firstChangedLine(String oldText, String newText) {
        return DiffAlgorithm.diff(oldText, newText).stream().filter(r -> r.kind() != DiffAlgorithm.Kind.CONTEXT)
                                        .mapToInt(r -> r.newLine() > 0 ? r.newLine() : Math.max(1, r.oldLine()))
                                        .findFirst().orElse(1);
    }

    private static String stripBom(String s) {
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    @Override
    public ToolResultView renderResult(Args args, ToolResult result, String cwd) {
        if (result.isError())
            return ToolResultView.of(result.content());
        // The content is written for the model ("Successfully replaced 1 block(s)."). The transcript counts instead,
        // like every other tool line there: "1 block" next to "5 lines" and "1 result", not a capitalised sentence.
        String summary = result.details().get("blocks") instanceof Number n
                                        ? Util.count(n.longValue(), "block", "blocks")
                                        : result.content();
        Object diffObj = result.details().get("diff");
        if (!(diffObj instanceof String diff) || diff.isBlank())
            return ToolResultView.of(summary);
        var body = new ArrayList<String>();
        int count = 0;
        for (String line : diff.split("\n")) {
            if (count++ >= 20) {
                body.add("… " + (diff.split("\n").length - 20) + " more changed lines");
                break;
            }
            body.add(line);
        }
        return ToolResultView.of(summary, body);
    }

    private static String normalizeForFuzzyMatch(String s) {
        s = Normalizer.normalize(s, Normalizer.Form.NFKC);
        s = s.replaceAll("(?m)[ \\t]+$", "");
        s = s.replaceAll("[\u2018\u2019\u201A\u201B]", "'");
        s = s.replaceAll("[\u201C\u201D\u201E\u201F]", "\"");
        s = s.replaceAll("[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]", "-");
        s = s.replaceAll("[\u00A0\u2002-\u200A\u202F\u205F\u3000]", " ");
        return s;
    }

}
