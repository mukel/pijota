package org.pijota.extensions.skills;

import org.pijota.config.ProjectTrust;
import org.pijota.util.PijotaHome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Reads a skill from its markdown: the front matter says when to use it, the body is what the model is given. */
public final class SkillLoader {

    private SkillLoader() {}

    public record Skill(String name, String description, String content, Path source) {
    }

    /**
     * The skills shipped in resources ({@code src/main/resources/skills}), loaded by name. The classpath directory
     * cannot be enumerated in a native image, so this list is the authority -- the same reason {@code SubAgent} keeps
     * one for its built-in agents.
     */
    private static final List<String> BUILTIN_SKILLS = List.of("pijota");

    /**
     * Project skills load only once the folder is trusted: a skill's description sits in the system prompt of every
     * turn and its body is instructions the model follows, so an untrusted repository could otherwise steer the session
     * before the user had agreed to anything.
     */
    public static List<Skill> load(String cwd, List<String> explicitPaths) {
        var skills = new LinkedHashMap<String, Skill>();
        discoverIn(skills, PijotaHome.resolve("skills"));
        if (ProjectTrust.isTrusted(cwd))
            discoverIn(skills, Path.of(cwd, ".pijota", "skills"));
        for (String pathStr : explicitPaths) {
            Path p = Path.of(pathStr);
            if (!p.isAbsolute())
                p = Path.of(cwd).resolve(p);
            loadSkill(p).ifPresent(s -> skills.putIfAbsent(s.name(), s));
        }
        // Last, because putIfAbsent means first wins: a user's own skill of the same name must shadow the shipped one.
        loadBuiltins(skills);
        return new ArrayList<>(skills.values());
    }

    private static void loadBuiltins(Map<String, Skill> skills) {
        for (String name : BUILTIN_SKILLS) {
            if (skills.containsKey(name))
                continue;
            try (var in = SkillLoader.class.getResourceAsStream("/skills/" + name + ".md")) {
                if (in == null)
                    continue;
                String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                var parsed = parseFrontmatter(raw);
                String description = String.valueOf(parsed.frontmatter().getOrDefault("description", ""));
                if (!description.isBlank())
                    skills.put(name, new Skill(name, description, parsed.body(), Path.of("(built-in)", name + ".md")));
            } catch (IOException ignored) {
                // a missing or unreadable built-in must not stop the user's own skills from loading
            }
        }
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

    private static Optional<Skill> loadSkill(Path path) {
        try {
            if (Files.isDirectory(path)) {
                Path skillMd = path.resolve("SKILL.md");
                if (!Files.isRegularFile(skillMd))
                    return Optional.empty();
                return loadFromFile(skillMd, path.getFileName().toString());
            }
            if (Files.isRegularFile(path) && path.toString().endsWith(".md")) {
                String name = path.getFileName().toString();
                int dot = name.lastIndexOf('.');
                return loadFromFile(path, dot > 0 ? name.substring(0, dot) : name);
            }
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<Skill> loadFromFile(Path file, String defaultName) throws IOException {
        String raw = Files.readString(file);
        var parsed = parseFrontmatter(raw);
        String name = parsed.frontmatter().containsKey("name")
                                        ? parsed.frontmatter().get("name").toString()
                                        : defaultName;
        String description = parsed.frontmatter().containsKey("description")
                                        ? parsed.frontmatter().get("description").toString()
                                        : "";
        if (description.isBlank())
            return Optional.empty();
        return Optional.of(new Skill(name, description, parsed.body(), file));
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
