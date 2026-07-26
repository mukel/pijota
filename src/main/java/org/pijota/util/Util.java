package org.pijota.util;

import org.pijota.tools.ToolOutputStore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Util {
    private static final Logger LOG = LoggerFactory.getLogger(Util.class);
    public static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
                                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * Best-effort restrict a path to its owner — files to {@code 0600}, directories to {@code 0700} — so secrets (API
     * keys, OAuth tokens) and session transcripts under {@code ~/.pijota} aren't readable by other local users. No-op
     * on non-POSIX filesystems (e.g. Windows), where filesystem ACLs govern access instead.
     */
    public static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions
                                            .fromString(Files.isDirectory(path) ? "rwx------" : "rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // non-POSIX filesystem, or the path vanished — best effort, not a hard guarantee.
        }
    }

    /** Atomic replace where supported; plain replace fallback for filesystems that reject atomic moves. */
    public static void replaceFile(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * SSRF guard: true if {@code host} resolves to a loopback, link-local (incl. {@code 169.254.169.254} cloud
     * metadata), site-local/private, wildcard, or multicast address. Used to stop a prompt-injected agent from steering
     * an outbound fetcher (webfetch, the headless browser) at internal services or the metadata endpoint. Fails open on
     * resolution errors — an unresolvable host just fails the fetch with a normal network error.
     */
    public static boolean isInternalAddress(String host) {
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                                                || addr.isAnyLocalAddress() || addr.isMulticastAddress())
                    return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static final int DEFAULT_MAX_CHARS = 100_000;

    private Util() {}

    public static String truncate(String s, int maxLen) {
        if (s == null)
            return "";
        if (s.length() <= maxLen)
            return s;
        return s.substring(0, maxLen) + "...";
    }

    public static String truncateWords(String s, int maxLen) {
        if (s == null || s.length() <= maxLen)
            return truncate(s, maxLen);
        int cut = maxLen - 3;
        if (cut <= 0)
            return s.substring(0, maxLen) + "...";
        int lastSpace = s.lastIndexOf(' ', cut);
        if (lastSpace > cut / 2)
            return s.substring(0, lastSpace) + "...";
        return s.substring(0, cut) + "...";
    }

    public static String escapeJson(String s) {
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20)
                        sb.append(String.format("\\u%04x", (int) c));
                    else
                        sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    public static int getInt(Map<String, Object> args, String key, int defaultVal) {
        Object val = args.get(key);
        return val instanceof Number n ? n.intValue() : defaultVal;
    }

    public static long getLong(Map<String, Object> args, String key, long defaultVal) {
        Object val = args.get(key);
        return val instanceof Number n ? n.longValue() : defaultVal;
    }

    public static String getString(Map<String, Object> args, String key) {
        return getString(args, key, null);
    }

    public static String getString(Map<String, Object> args, String key, String defaultVal) {
        Object val = args.get(key);
        return val instanceof String s && !s.isBlank() ? s : defaultVal;
    }

    public static String getFirstString(Map<String, Object> args, String... keys) {
        for (String key : keys) {
            String val = getString(args, key, null);
            if (val != null)
                return val;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseJsonArgs(String jsonArgs) {
        try {
            return MAPPER.readValue(jsonArgs, Map.class);
        } catch (Exception e) {
            // a tool then runs with NO arguments, which looks like a mysterious tool bug — record why
            LOG.debug("Could not parse tool arguments as JSON ({}); treating as empty", e.getMessage());
            return Map.of();
        }
    }

    public static String coalesce(String... values) {
        for (String v : values)
            if (v != null && !v.isBlank())
                return v;
        return null;
    }

    public static String stripSystemReminders(String text) {
        if (text == null || text.isBlank())
            return "";
        return text.replaceAll("(?s)<system-reminder>.*?</system-reminder>", "");
    }

    /** The project-wide rough token estimate from a character count (~4 characters per token). */
    public static long approxTokens(long chars) {
        return (long) Math.ceil(chars / 4.0);
    }

    public static Path resolveSearchPath(Path base, String rawPath) {
        if (rawPath == null || rawPath.isBlank())
            return base.toAbsolutePath().normalize();
        Path path = Path.of(rawPath);
        return path.isAbsolute() ? path.normalize() : base.resolve(path).normalize();
    }

    /** The dominant line ending of {@code s}: {@code "\r\n"}, {@code "\r"}, or {@code "\n"} (default). */
    public static String detectLineEnding(String s) {
        if (s.contains("\r\n"))
            return "\r\n";
        if (s.contains("\r"))
            return "\r";
        return "\n";
    }

    /** Convert LF-normalized {@code s} back to {@code lineEnding}. */
    public static String restoreLineEndings(String s, String lineEnding) {
        return "\n".equals(lineEnding) ? s : s.replace("\n", lineEnding);
    }

    /** Normalize CRLF/CR line endings to LF. */
    public static String normalizeToLF(String s) {
        return s.replace("\r\n", "\n").replace("\r", "\n");
    }

    public static int countOccurrences(String haystack, String needle) {
        if (needle == null || needle.isEmpty() || haystack == null)
            return 0;
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    public static String resolvePath(Map<String, Object> args) {
        return getFirstString(args, "path", "filePath", "file_path");
    }

    public static String resolvePath(Map<String, Object> args, String cwd) {
        String path = resolvePath(args);
        if (path == null)
            return null;
        Path p = Path.of(path);
        if (p.isAbsolute() || cwd == null || cwd.isBlank())
            return p.normalize().toString();
        return Path.of(cwd).resolve(p).normalize().toString();
    }

    public static String relativePath(Path path, String cwd) {
        try {
            Path base = Path.of(cwd).toAbsolutePath().normalize();
            Path abs = path.toAbsolutePath().normalize();
            if (abs.startsWith(base))
                return base.relativize(abs).toString();
        } catch (Exception ignored) {}
        return path.toString();
    }

    public static int countNewlines(String s) {
        if (s == null || s.isEmpty())
            return 0;
        int c = 0;
        for (int i = 0; i < s.length(); i++)
            if (s.charAt(i) == '\n')
                c++;
        return c;
    }

    public static Path resolveDir(String cwd) {
        return cwd != null ? Path.of(cwd) : Path.of(System.getProperty("user.dir"));
    }

    public record TruncatedResult(String text, Map<String, Object> details) {
    }

    public static TruncatedResult truncateToFile(String text, int maxChars, String tempPrefix) throws IOException {
        if (text == null)
            text = "";
        if (text.length() <= maxChars)
            return new TruncatedResult(text, Map.of());
        var stored = ToolOutputStore.bound(text, maxChars, tempPrefix);
        return new TruncatedResult(stored.text(), stored.details());
    }

    public static String truncateHeadTail(String text, int maxChars, String fullOutputPath) {
        if (text == null || text.isBlank())
            return "";
        if (text.length() <= maxChars)
            return text;
        int headChars = maxChars * 3 / 4;
        int tailChars = maxChars - headChars;
        int skipped = text.length() - maxChars;
        String fullClause = fullOutputPath != null ? ". Full output: " + fullOutputPath : "";
        return text.substring(0, headChars) + "\n\n[...truncated " + formatSize(skipped) + fullClause + "...]\n\n"
                                        + text.substring(text.length() - tailChars);
    }

    public static String truncateHeadTail(String text, int maxChars) {
        return truncateHeadTail(text, maxChars, null);
    }

    /** Human-readable size (B/KB/MB), locale-independent (always a {@code .} decimal). */
    public static String formatSize(int bytes) {
        if (bytes < 1024)
            return bytes + "B";
        if (bytes < 1024 * 1024)
            return String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024.0));
    }

    public static String fmtCount(int value) {
        return fmtCount((long) value);
    }

    public static String fmtCount(long value) {
        if (value >= 1_000_000)
            return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
        if (value >= 1_000)
            return (value / 1_000) + "K";
        return Long.toString(value);
    }

    /**
     * Like {@link #fmtCount} but keeps two significant fractional digits (trailing zeros trimmed): {@code 600},
     * {@code 1.22K}, {@code 12.3K}, {@code 1.05M}. For live counts where the integer-K rounding loses too much.
     */
    public static String fmtCountPrecise(long value) {
        if (value >= 1_000_000)
            return trimZeros(String.format(Locale.ROOT, "%.2f", value / 1_000_000.0)) + "M";
        if (value >= 1_000)
            return trimZeros(String.format(Locale.ROOT, "%.2f", value / 1_000.0)) + "K";
        return Long.toString(value);
    }

    private static String trimZeros(String s) {
        if (s.indexOf('.') < 0)
            return s;
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    public static String formatDuration(long ms) {
        long seconds = ms / 1000;
        if (seconds < 60)
            return seconds + "s";
        long minutes = seconds / 60;
        seconds %= 60;
        if (minutes < 60)
            return minutes + "m" + (seconds > 0 ? seconds + "s" : "");
        long hours = minutes / 60;
        minutes %= 60;
        return hours + "h" + (minutes > 0 ? minutes + "m" : "") + (seconds > 0 ? seconds + "s" : "");
    }

    /** Reads all of stdin as UTF-8 and strips it; returns {@code null} on any read error. */
    public static String readStdin() {
        try {
            return new String(System.in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (Exception e) {
            return null;
        }
    }

    public static String formatRelative(long epochMs) {
        long diff = epochMs - System.currentTimeMillis();
        long abs = Math.abs(diff);
        String prefix = diff >= 0 ? "in " : "";
        String suffix = diff >= 0 ? "" : " ago";
        if (abs < 60_000)
            return prefix + (abs / 1000) + "s" + suffix;
        if (abs < 3600_000)
            return prefix + (abs / 60_000) + "m" + suffix;
        if (abs < 86_400_000)
            return prefix + (abs / 3600_000) + "h" + suffix;
        return prefix + (abs / 86_400_000) + "d" + suffix;
    }

    public static String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public static String shortUuid(int len) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, Math.min(len, 32));
    }

    /**
     * Tool names whose {@code command} argument is a shell command — so security/permission gates (which key on this
     * set) inspect them like the {@code bash} tool: the bash tool, tmux's runner, and the background-jobs runner.
     */
    public static final Set<String> SHELL_TOOL_NAMES = Set.of("bash", "tmux_bash", "jobs_run");

    public static String parseBashArgs(String argsJson) {
        return parseJsonArgs(argsJson).get("command") instanceof String s ? s : null;
    }

    public static String shortenCwd(String cwd) {
        String home = System.getProperty("user.home");
        if (home == null)
            return cwd;
        String resolved = Path.of(cwd).toAbsolutePath().toString();
        String resolvedHome = Path.of(home).toAbsolutePath().toString();
        if (!resolved.startsWith(resolvedHome))
            return cwd;
        if (resolved.equals(resolvedHome))
            return "~";
        return "~" + resolved.substring(resolvedHome.length());
    }

    public static String deltaPrefix(String full, String previous) {
        if (full == null || full.isEmpty())
            return "";
        if (previous == null || previous.isEmpty())
            return full;
        return full.startsWith(previous) ? full.substring(previous.length()) : full;
    }

    public static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Shell-encodes a value as a shell expects a path argument, quoting only when needed (like zsh's {@code ${(q)}} /
     * {@code printf %q}): a value of only safe characters is returned bare; anything with spaces, quotes, {@code $},
     * globs, etc. is wrapped in single quotes (embedded {@code '} escaped as {@code '\''}), making spaces/{@code "}/
     * {@code '} all literal. {@code ~} is kept unquoted so a leading {@code ~/} still expands.
     */
    public static String shellQuoteIfNeeded(String value) {
        if (value.isEmpty())
            return "''";
        return value.matches("[A-Za-z0-9._/@%+,:=~-]+") ? value : shellQuote(value);
    }

    /** "1 item" / "3 items" — a count with its regularly-pluralized noun (the recurring {@code n==1?"":"s"} idiom). */
    public static String plural(long n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    public static String firstLine(String text) {
        if (text == null || text.isBlank())
            return "";
        return text.stripTrailing().lines().findFirst().orElse("");
    }

    /**
     * Wrap {@code text} in an OSC 8 terminal hyperlink to {@code url} (terminals without support just show the text).
     */
    public static String osc8Link(String url, String text) {
        return "\033]8;;" + url + "\033\\" + text + "\033]8;;\033\\";
    }

}
