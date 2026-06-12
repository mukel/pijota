package ai.pijota.extensions.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class SkillLoader {

    private static final Pattern VALID_NAME = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private SkillLoader() {}

    public record Skill(String name, String description, String content, Path source) {
    }

    public static List<Skill> load(String cwd, List<String> explicitPaths) {
        var skills = new LinkedHashMap<String, Skill>();
        discoverIn(skills, Path.of(System.getProperty("user.home"), ".pijota", "skills"));
        discoverIn(skills, Path.of(cwd, ".pijota", "skills"));
        for (String pathStr : explicitPaths) {
            Path p = Path.of(pathStr);
            if (!p.isAbsolute())
                p = Path.of(cwd).resolve(p);
            loadSkill(p).ifPresent(s -> skills.putIfAbsent(s.name(), s));
        }
        return new ArrayList<>(skills.values());
    }

    public static String buildPrompt(List<Skill> skills) {
        if (skills.isEmpty())
            return "";
        var sb = new StringBuilder();
        sb.append("\n<available_skills>\n");
        sb.append("The following skills provide specialized instructions for specific tasks.\n");
        sb.append("Use the skill tool to load a skill when the task matches its description.\n\n");
        for (var skill : skills) {
            sb.append("  <skill>\n");
            sb.append("    <name>").append(skill.name()).append("</name>\n");
            sb.append("    <description>").append(skill.description()).append("</description>\n");
            sb.append("    <location>").append(skill.source()).append("</location>\n");
            sb.append("  </skill>\n");
        }
        sb.append("</available_skills>\n");
        return sb.toString();
    }

    public static Skill findByName(List<Skill> skills, String name) {
        return skills.stream().filter(s -> s.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    private static void discoverIn(Map<String, Skill> skills, Path dir) {
        if (!Files.isDirectory(dir))
            return;
        try (Stream<Path> stream = Files.list(dir)) {
            stream.sorted().forEach(p -> loadSkill(p).ifPresent(s -> skills.putIfAbsent(s.name(), s)));
        } catch (IOException ignored) {}
    }

    private static java.util.Optional<Skill> loadSkill(Path path) {
        try {
            if (Files.isDirectory(path)) {
                Path skillMd = path.resolve("SKILL.md");
                if (!Files.isRegularFile(skillMd))
                    return java.util.Optional.empty();
                return loadFromFile(skillMd, path.getFileName().toString());
            }
            if (Files.isRegularFile(path) && path.toString().endsWith(".md")) {
                String name = path.getFileName().toString();
                int dot = name.lastIndexOf('.');
                return loadFromFile(path, dot > 0 ? name.substring(0, dot) : name);
            }
            return java.util.Optional.empty();
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    private static java.util.Optional<Skill> loadFromFile(Path file, String defaultName) throws IOException {
        String raw = Files.readString(file);
        var parsed = parseFrontmatter(raw);
        String name = parsed.frontmatter().containsKey("name")
                                        ? parsed.frontmatter().get("name").toString()
                                        : defaultName;
        String description = parsed.frontmatter().containsKey("description")
                                        ? parsed.frontmatter().get("description").toString()
                                        : "";
        if (description.isBlank())
            return java.util.Optional.empty();
        return java.util.Optional.of(new Skill(name, description, parsed.body(), file));
    }

    record Parsed(Map<String, Object> frontmatter, String body) {
    }

    static Parsed parseFrontmatter(String raw) {
        var frontmatter = new LinkedHashMap<String, Object>();
        String body = raw;
        if (raw.stripLeading().startsWith("---")) {
            int start = raw.indexOf("---") + 3;
            int end = raw.indexOf("---", start);
            if (end > start) {
                body = raw.substring(end + 3);
                for (String line : raw.substring(start, end).strip().split("\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String key = line.substring(0, colon).trim();
                        String value = line.substring(colon + 1).trim();
                        if (!key.isEmpty() && !value.isEmpty())
                            frontmatter.put(key, value);
                    }
                }
            }
        }
        return new Parsed(frontmatter, body.strip());
    }
}
