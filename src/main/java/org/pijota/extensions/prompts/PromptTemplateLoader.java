package org.pijota.extensions.prompts;

import org.pijota.util.PijotaHome;
import org.pijota.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Reads prompt templates from markdown, resolving the argument placeholders they declare. */
public final class PromptTemplateLoader {

    private PromptTemplateLoader() {}

    /** Non-recursive scan of *.md files in the prompts directory. */
    public static List<PromptTemplate> load(String cwd, List<String> explicitPaths) {
        return load(cwd, explicitPaths, true);
    }

    /** Non-recursive scan of prompt directories, optionally skipping project-local templates until trust is granted. */
    public static List<PromptTemplate> load(String cwd, List<String> explicitPaths, boolean includeProjectTemplates) {
        var templates = new LinkedHashMap<String, PromptTemplate>();
        discoverIn(templates, PijotaHome.resolve("prompts"));
        if (includeProjectTemplates)
            discoverIn(templates, Path.of(cwd, ".pijota", "prompts"));
        for (String pathStr : explicitPaths) {
            Path p = Path.of(pathStr);
            if (!p.isAbsolute())
                p = Path.of(cwd).resolve(p);
            loadTemplate(p).ifPresent(t -> templates.putIfAbsent(t.name(), t));
        }
        return new ArrayList<>(templates.values());
    }

    /** Replace shell-style variables: $1..$9, $@, $ARGUMENTS, defaults, ${@:N}, ${@:N:L}. */
    public static String expand(String content, String[] args) {
        String expanded = content;

        expanded = expandDefaults(expanded, args);

        // positional: $1..$9 (do first to avoid re-substitution)
        for (int i = 0; i < args.length && i < 9; i++) {
            expanded = expanded.replace("$" + (i + 1), Matcher.quoteReplacement(args[i]));
        }

        // slice patterns: ${@:N} and ${@:N:L}
        expanded = expandSlices(expanded, args);

        // $@ and $ARGUMENTS, all args joined with space
        String allArgs = String.join(" ", args);
        expanded = expanded.replace("$@", Matcher.quoteReplacement(allArgs));
        expanded = expanded.replace("$ARGUMENTS", Matcher.quoteReplacement(allArgs));

        return expanded;
    }

    private static final Pattern SLICE = Pattern.compile("\\$\\{@:(\\d+)(?::(\\d+))?\\}");
    private static final Pattern DEFAULT = Pattern.compile("\\$\\{(\\d+|@|ARGUMENTS):-([^}]*)\\}");

    private static String expandDefaults(String content, String[] args) {
        Matcher m = DEFAULT.matcher(content);
        if (!m.find())
            return content;
        String allArgs = String.join(" ", args);
        StringBuilder sb = new StringBuilder();
        do {
            String target = m.group(1);
            String value;
            if ("@".equals(target) || "ARGUMENTS".equals(target)) {
                value = allArgs;
            } else {
                int idx = Integer.parseInt(target) - 1;
                value = idx >= 0 && idx < args.length ? args[idx] : "";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value == null || value.isBlank() ? m.group(2) : value));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    private static String expandSlices(String content, String[] args) {
        Matcher m = SLICE.matcher(content);
        if (!m.find())
            return content;
        StringBuilder sb = new StringBuilder();
        do {
            int start = Integer.parseInt(m.group(1)) - 1; // 1-indexed
            int len = m.group(2) != null ? Integer.parseInt(m.group(2)) : args.length - start;
            if (start < 0)
                start = 0;
            int end = Math.min(start + len, args.length);
            var slice = new ArrayList<String>();
            for (int i = start; i < end; i++)
                slice.add(args[i]);
            m.appendReplacement(sb, Matcher.quoteReplacement(String.join(" ", slice)));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    /** Parse shell-style quoted arguments, same as Pi's parseCommandArgs. */
    public static String[] parseArgs(String input) {
        if (input == null || input.isBlank())
            return new String[0];
        var args = new ArrayList<String>();
        boolean inSingle = false, inDouble = false;
        var current = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (inSingle) {
                if (c == '\'')
                    inSingle = false;
                else
                    current.append(c);
            } else if (inDouble) {
                if (c == '"')
                    inDouble = false;
                else
                    current.append(c);
            } else if (c == '\'') {
                inSingle = true;
            } else if (c == '"') {
                inDouble = true;
            } else if (c == ' ' || c == '\t' || c == '\n') {
                if (!current.isEmpty()) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty())
            args.add(current.toString());
        return args.toArray(new String[0]);
    }

    public static String buildPrompt(List<PromptTemplate> templates) {
        if (templates.isEmpty())
            return "";
        var sb = new StringBuilder();
        sb.append("\n<available_prompt_templates>\n");
        sb.append("The following prompt templates are available. Expand them via /<name> <args>.\n");
        sb.append("Templates support $1, $2, ..., $@, $ARGUMENTS, ${@:N}, ${@:N:L} for argument substitution.\n\n");
        for (var t : templates) {
            sb.append("  <template>\n");
            sb.append("    <name>/").append(t.name()).append("</name>\n");
            sb.append("    <description>").append(t.description()).append("</description>\n");
            if (t.argumentHint() != null && !t.argumentHint().isBlank())
                sb.append("    <argument-hint>").append(t.argumentHint()).append("</argument-hint>\n");
            sb.append("  </template>\n");
        }
        sb.append("</available_prompt_templates>\n");
        return sb.toString();
    }

    // ---- internal discovery ----

    private static void discoverIn(Map<String, PromptTemplate> templates, Path dir) {
        if (!Files.isDirectory(dir))
            return;
        try (Stream<Path> stream = Files.list(dir)) {
            stream.sorted().forEach(p -> loadTemplate(p).ifPresent(t -> templates.putIfAbsent(t.name(), t)));
        } catch (IOException ignored) {}
    }

    private static Optional<PromptTemplate> loadTemplate(Path path) {
        try {
            if (!Files.isRegularFile(path))
                return Optional.empty();
            String fileName = path.getFileName().toString();
            if (!fileName.endsWith(".md"))
                return Optional.empty();
            String name = fileName.substring(0, fileName.length() - 3);
            if (name.isEmpty())
                return Optional.empty();

            String raw = Files.readString(path);
            var parsed = parseFrontmatter(raw);
            String description = parsed.frontmatter().getOrDefault("description", "").toString();
            if (description.isBlank()) {
                // fall back to first non-empty line of body, truncated to 60 chars
                String firstLine = parsed.body().lines().filter(l -> !l.isBlank()).findFirst().orElse("");
                description = Util.ellipsize(firstLine, 60);
            }
            String argumentHint = parsed.frontmatter().getOrDefault("argument-hint", "").toString();
            if (argumentHint.isBlank())
                argumentHint = null;

            return Optional.of(new PromptTemplate(name, description, argumentHint, parsed.body(), path));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    // ---- minimal YAML frontmatter parser ----

    record Parsed(Map<String, String> frontmatter, String body) {
    }

    static Parsed parseFrontmatter(String raw) { // package-private for tests
        var frontmatter = new LinkedHashMap<String, String>();
        if (!raw.stripLeading().startsWith("---"))
            return new Parsed(frontmatter, raw.strip());
        int start = raw.indexOf("---") + 3;
        int end = raw.indexOf("---", start);
        if (end <= start)
            return new Parsed(frontmatter, raw.strip());

        for (String line : raw.substring(start, end).strip().split("\n"))
            putField(frontmatter, line);
        return new Parsed(frontmatter, raw.substring(end + 3).strip());
    }

    /**
     * One {@code key: value} line of frontmatter, or nothing when the line is not one.
     *
     * <p>
     * Emptiness is judged before the quotes come off, which is what keeps {@code key: ""} a deliberate empty value
     * rather than a line that says nothing: strip first and it would be indistinguishable from {@code key:}.
     */
    private static void putField(Map<String, String> into, String line) {
        int colon = line.indexOf(':');
        if (colon <= 0)
            return;
        String key = line.substring(0, colon).trim();
        String value = line.substring(colon + 1).trim();
        if (key.isEmpty() || value.isEmpty())
            return;
        into.put(key, unquote(value));
    }

    private static String unquote(String value) {
        boolean quoted = value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                                        || (value.startsWith("'") && value.endsWith("'")));
        return quoted ? value.substring(1, value.length() - 1) : value;
    }
}
