package ai.pijota.tools;

import ai.pijota.util.DiffAlgorithm;
import ai.pijota.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class EditTool implements Tool {

    @Override public String name() { return "edit"; }

    @Override
    public String description() {
        return "Edit a single file using exact text replacement. Every edits[].oldText must match "
            + "a unique, non-overlapping region of the original file. If two changes affect the same "
            + "block or nearby lines, merge them into one edit instead of emitting overlapping edits. "
            + "Do not include large unchanged regions just to connect distant changes.";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of("type", "string",
                    "description", "Path to the file to edit (relative or absolute)"),
                "edits", Map.of("type", "array",
                    "description", "One or more targeted replacements. Each edit is matched against the original file, not incrementally. Do not include overlapping or nested edits. If two changes touch the same block or nearby lines, merge them into one edit instead.",
                    "items", Map.of("type", "object",
                    "properties", Map.of(
                        "oldText", Map.of("type", "string",
                            "description", "Exact text for one targeted replacement. It must be unique in the original file and must not overlap with any other edits[].oldText in the same call."),
                        "newText", Map.of("type", "string",
                            "description", "Replacement text for this targeted edit.")
                    ),
                    "required", List.of("oldText", "newText")
                ))
            ),
            "required", List.of("path", "edits"),
            "additionalProperties", false
        );
    }

    @Override
    public CompletableFuture<ToolResult> execute(
            String toolCallId, Map<String, Object> args, String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String pathStr = Util.resolvePath(args, cwd);
                if (pathStr == null) return ToolResult.error("No path provided");

                Path path = Path.of(pathStr);
                return FileMutationQueue.run(path, () -> editFile(path, args));
        } catch (Exception e) {
            return ToolResult.error("Edit error: " + e.getMessage());
        }
        });
    }

    private static ToolResult editFile(Path path, Map<String, Object> args) throws IOException {
        if (!Files.isReadable(path) || !Files.isWritable(path))
            return ToolResult.error("Cannot read/write: " + path);

        var edits = extractEdits(args);
        if (edits.isEmpty()) return ToolResult.error("Edit tool input is invalid. "
            + "edits must contain at least one replacement.");

        String original = Files.readString(path);
        String rawContent = stripBom(original);
        String lineEnding = detectLineEnding(original);
        String content = normalizeToLF(rawContent);

        var matches = new ArrayList<MatchResult>();
        var errors = new ArrayList<String>();
        for (int i = 0; i < edits.size(); i++) {
            var edit = edits.get(i);
            String oldLF = normalizeToLF(edit.oldText());
            String newLF = normalizeToLF(edit.newText());

            int exactCount = Util.countOccurrences(content, oldLF);
            if (oldLF.isEmpty()) {
                errors.add("edit[" + i + "]: oldText must not be empty.");
            } else if (exactCount == 1) {
                matches.add(new ExactMatch(i, oldLF, newLF, content.indexOf(oldLF)));
            } else if (exactCount > 1) {
                errors.add("edit[" + i + "]: oldText matches " + exactCount
                    + " locations (must be unique). Add more surrounding context.");
            } else {
                var fuzzy = tryFuzzyMatch(content, oldLF, newLF, i);
                if (fuzzy != null) matches.add(fuzzy);
                else errors.add("edit[" + i + "]: oldText not found in file.");
            }
        }

        if (!errors.isEmpty()) return ToolResult.error(String.join("\n", errors));

        matches.sort(Comparator.comparingInt(MatchResult::start));
        for (int i = 1; i < matches.size(); i++) {
            var previous = matches.get(i - 1);
            var current = matches.get(i);
            if (previous.start() + previous.oldText().length() > current.start()) {
                errors.add("edits[" + previous.index() + "] and edits[" + current.index()
                    + "] overlap. Merge them into one edit or target disjoint regions.");
            }
        }
        if (!errors.isEmpty()) return ToolResult.error(String.join("\n", errors));

        String result = content;
        boolean usedFuzzy = matches.stream().anyMatch(m -> m instanceof FuzzyMatch);
        if (usedFuzzy) {
            result = normalizeForFuzzyMatch(content);
            for (var m : matches) {
                String oldNormalized = m instanceof FuzzyMatch ? normalizeForFuzzyMatch(m.oldText()) : m.oldText();
                result = result.substring(0, m.start()) + m.newText()
                    + result.substring(m.start() + oldNormalized.length());
            }
        } else {
            matches.sort(Comparator.comparingInt(MatchResult::start).reversed());
            for (var m : matches) {
                result = result.substring(0, m.start()) + m.newText()
                    + result.substring(m.start() + m.oldText().length());
            }
        }

        if (content.equals(result)) {
            return ToolResult.error("No changes made to " + path + ". The replacements produced identical content.");
        }

        Files.writeString(path, restoreLineEndings(result, lineEnding), StandardOpenOption.TRUNCATE_EXISTING);

        var details = Map.<String, Object>of(
            "diff", displayDiff(content, result),
            "patch", unifiedPatch(path.toString(), content, result),
            "firstChangedLine", firstChangedLine(content, result)
        );
        return ToolResult.success("Successfully replaced " + matches.size()
            + " block(s) in " + path + ".", details);
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
                    if (oldT != null && newT != null) edits.add(new Edit(oldT, newT));
                }
            }
        }
        // Also handle edits sent as JSON string
        if (edits.isEmpty() && editsObj instanceof String s) {
            try {
                @SuppressWarnings("unchecked")
                var parsed = (List<Map<String, Object>>)
                    Util.MAPPER.readValue(s, List.class);
                for (var m : parsed) {
                    String oldT = (String) m.get("oldText");
                    String newT = (String) m.get("newText");
                    if (oldT != null && newT != null) edits.add(new Edit(oldT, newT));
                }
            } catch (Exception e) {
                // Edits sent as JSON string failed to parse; fall through to legacy mode
            }
        }
        // Legacy single-edit: fold into edits[], stripping from args (Pi behavior)
        String oldStr = Util.getFirstString(args, "oldText", "oldString");
        String newStr = Util.getFirstString(args, "newText", "newString");
        if (oldStr != null && newStr != null) {
            args.remove("oldText"); args.remove("oldString");
            args.remove("newText"); args.remove("newString");
            edits.add(new Edit(oldStr, newStr));
        }
        return edits;
    }

    private record Edit(String oldText, String newText) {}

    private sealed interface MatchResult permits ExactMatch, FuzzyMatch {
        int index(); String oldText(); String newText(); int start();
    }

    private record ExactMatch(int index, String oldText, String newText, int start) implements MatchResult {}

    private record FuzzyMatch(int index, String oldText, String newText, int start, String reason)
            implements MatchResult {}

    private static FuzzyMatch tryFuzzyMatch(String content, String oldLF, String newLF, int idx) {
        String oldTrimmed = oldLF.replaceAll("(?m)\\s+$", "");
        String contentTrimmed = content.replaceAll("(?m)\\s+$", "");
        if (contentTrimmed.contains(oldTrimmed) && Util.countOccurrences(contentTrimmed, oldTrimmed) == 1) {
            return new FuzzyMatch(idx, oldTrimmed, newLF, contentTrimmed.indexOf(oldTrimmed), "trailing-whitespace");
        }
        String oldNorm = normalizeForFuzzyMatch(oldLF);
        if (!oldNorm.equals(oldLF)) {
            String contentNorm = normalizeForFuzzyMatch(content);
            if (contentNorm.contains(oldNorm) && Util.countOccurrences(contentNorm, oldNorm) == 1) {
                return new FuzzyMatch(idx, oldNorm, newLF, contentNorm.indexOf(oldNorm), "unicode-normalization");
            }
        }
        return null;
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
            sb.append("@@ -").append(os).append(',').append(oc)
                .append(" +").append(ns).append(',').append(nc).append(" @@\n");
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
        int width = Integer.toString(Math.max(
            oldText != null ? oldText.split("\\R", -1).length : 0,
            newText != null ? newText.split("\\R", -1).length : 0)).length();
        for (var hunk : DiffAlgorithm.hunks(rows, 4)) {
            for (var row : hunk.rows()) {
                if (row.kind() == DiffAlgorithm.Kind.CONTEXT) continue;
                int line = row.kind() == DiffAlgorithm.Kind.ADD ? row.newLine() : row.oldLine();
                sb.append(row.kind() == DiffAlgorithm.Kind.ADD ? '+' : '-')
                    .append(String.format(Locale.ROOT, "%" + width + "d", line))
                    .append(' ').append(row.text()).append('\n');
            }
        }
        return sb.toString();
    }

    private static int firstChangedLine(String oldText, String newText) {
        return DiffAlgorithm.diff(oldText, newText).stream()
            .filter(r -> r.kind() != DiffAlgorithm.Kind.CONTEXT)
            .mapToInt(r -> r.newLine() > 0 ? r.newLine() : Math.max(1, r.oldLine()))
            .findFirst().orElse(1);
    }

    private static String stripBom(String s) {
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    private static String detectLineEnding(String s) {
        if (s.contains("\r\n")) return "\r\n";
        if (s.contains("\r")) return "\r";
        return "\n";
    }

    private static String normalizeToLF(String s) {
        return s.replace("\r\n", "\n").replace("\r", "\n");
    }

    private static String restoreLineEndings(String s, String le) {
        return "\n".equals(le) ? s : s.replace("\n", le);
    }

    private static String normalizeForFuzzyMatch(String s) {
        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC);
        s = s.replaceAll("(?m)[ \\t]+$", "");
        s = s.replaceAll("[\u2018\u2019\u201A\u201B]", "'");
        s = s.replaceAll("[\u201C\u201D\u201E\u201F]", "\"");
        s = s.replaceAll("[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]", "-");
        s = s.replaceAll("[\u00A0\u2002-\u200A\u202F\u205F\u3000]", " ");
        return s;
    }

}
