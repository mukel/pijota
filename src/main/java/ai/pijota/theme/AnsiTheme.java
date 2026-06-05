package ai.pijota.theme;

import ai.pijota.util.Util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AnsiTheme {

    // -- slots ----------------------------------------

    public String name = "custom";
    public boolean enabled = true;

    public String heading      = "bold;#00758F";
    public String bold         = "bold";
    public String italic       = "italic";
    public String highlight    = "inverse";
    public String dim          = "dim";
    public String strikethrough = "strikethrough";

    public String codeFence    = "dim;cyan";
    public String codeBody     = "cyan";
    public String blockquote   = "dim";
    public String hr           = "dim";
    public String inlineCode   = "yellow";

    public String synKeyword   = "bold;yellow";
    public String synType      = "cyan";
    public String synString    = "green";
    public String synNumber    = "magenta";
    public String synComment   = "dim";
    public String synFunction  = "bold;cyan";
    public String synVariable  = "";
    public String synOperator  = "bright_black";
    public String synPunctuation = "bright_black";

    public String prompt       = "#F29111";
    public String promptEcho   = "dim";
    public String toolLabel    = "#F29111";
    public String toolResult   = "dim";
    public String toolError    = "red";
    public String commandMeta  = "dim;#7AA6B8";
    public String statusText   = "dim";
    public String statusBar    = "bright_black";
    public String statusWarn   = "dim;yellow";
    public String bannerName   = "bold;#00758F";
    public String bannerMeta   = "dim";
    public String cwdLine      = "bright_black";
    public String bashMode      = "#00FF66";

    public String diffHeader    = "#7AA6B8";
    public String diffMeta      = "bright_black";
    public String diffAdd       = "#73C991";
    public String diffDelete    = "#E06C75";
    public String diffContext   = "";
    public String diffHunk      = "dim;#F29111";
    public String diffAddLine   = "bg:#08180F";
    public String diffDeleteLine = "bg:#1D1411";

    public Map<String, String> extra = new HashMap<>();

    // -- factory presets -------------------------------

    public static AnsiTheme dark() {
        var t = new AnsiTheme();
        t.name = "dark";
        return t;
    }

    public static AnsiTheme light() {
        var t = new AnsiTheme();
        t.name = "light";
        t.heading = "bold;#005F87";
        t.dim = "#666666";
        t.codeFence = "#666666";
        t.codeBody = "#005F87";
        t.inlineCode = "#875F00";
        t.synKeyword = "bold;#875F00";
        t.synFunction = "bold;#005F87";
        t.synOperator = "#666666";
        t.synPunctuation = "#666666";
        t.prompt = "#AF5F00";
        t.toolLabel = "#AF5F00";
        t.toolResult = "#666666";
        t.toolError = "#AF0000";
        t.statusText = "#666666";
        t.statusBar = "#666666";
        t.bannerName = "bold;#005F87";
        t.cwdLine = "#666666";
        return t;
    }

    public AnsiTheme muted() {
        var parent = this;
        var t = new AnsiTheme() {
            @Override
            public String style(String slot) {
                String base = parent.style(slot);
                if (base == null || base.isEmpty()) return base;
                if (base.contains("dim")) return base;
                return "dim;" + base;
            }
        };
        t.enabled = true;
        t.name = name + "-muted";
        return t;
    }

    public static AnsiTheme load(Path path) throws IOException {
        String raw = Files.readString(path);
        return loadRaw(raw, path.getFileName().toString());
    }

    public static AnsiTheme loadResource(String resource) throws IOException {
        try (InputStream in = AnsiTheme.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Theme resource not found: " + resource);
            return loadRaw(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), resource);
        }
    }

    private static AnsiTheme loadRaw(String raw, String fallbackName) throws IOException {
        JsonNode root = Util.MAPPER.readTree(raw);
        if (root.has("colors")) return fromPiTheme(root, fallbackName);
        AnsiTheme t = Util.MAPPER.readValue(raw, AnsiTheme.class);
        if (t.name == null) t.name = fallbackName;
        return t;
    }

    private static AnsiTheme fromPiTheme(JsonNode root, String fallbackName) {
        var t = dark();
        t.name = text(root.get("name"), fallbackName.replaceFirst("\\.json$", ""));
        Map<String, String> vars = new HashMap<>();
        JsonNode varNode = root.get("vars");
        if (varNode != null && varNode.isObject()) {
            varNode.fields().forEachRemaining(e -> vars.put(e.getKey(), colorSpec(e.getValue(), Map.of())));
        }
        JsonNode c = root.get("colors");
        if (c == null || !c.isObject()) return t;

        t.heading = styledColor(c, vars, "mdHeading", t.heading);
        t.dim = color(c, vars, "dim", t.dim);
        t.codeFence = styledColor(c, vars, "mdCodeBlockBorder", t.codeFence);
        t.codeBody = color(c, vars, "mdCodeBlock", t.codeBody);
        t.blockquote = styledColor(c, vars, "mdQuote", t.blockquote);
        t.hr = styledColor(c, vars, "mdHr", t.hr);
        t.inlineCode = color(c, vars, "mdCode", t.inlineCode);
        t.synKeyword = styledColor(c, vars, "syntaxKeyword", t.synKeyword);
        t.synType = color(c, vars, "syntaxType", t.synType);
        t.synString = color(c, vars, "syntaxString", t.synString);
        t.synNumber = color(c, vars, "syntaxNumber", t.synNumber);
        t.synComment = styledColor(c, vars, "syntaxComment", t.synComment);
        t.synFunction = styledColor(c, vars, "syntaxFunction", t.synFunction);
        t.synVariable = color(c, vars, "syntaxVariable", t.synVariable);
        t.synOperator = color(c, vars, "syntaxOperator", t.synOperator);
        t.synPunctuation = color(c, vars, "syntaxPunctuation", t.synPunctuation);
        t.prompt = color(c, vars, "accent", t.prompt);
        t.toolLabel = color(c, vars, "toolTitle", color(c, vars, "accent", t.toolLabel));
        t.toolResult = styledColor(c, vars, "toolOutput", t.toolResult);
        t.toolError = color(c, vars, "error", t.toolError);
        t.statusText = styledColor(c, vars, "muted", t.statusText);
        t.statusBar = color(c, vars, "dim", t.statusBar);
        t.statusWarn = styledColor(c, vars, "warning", t.statusWarn);
        t.bannerName = styledColor(c, vars, "accent", t.bannerName);
        t.bannerMeta = styledColor(c, vars, "muted", t.bannerMeta);
        t.cwdLine = color(c, vars, "dim", t.cwdLine);
        t.bashMode = color(c, vars, "bashMode", t.bashMode);
        t.diffAdd = color(c, vars, "toolDiffAdded", t.diffAdd);
        t.diffDelete = color(c, vars, "toolDiffRemoved", t.diffDelete);
        t.diffContext = color(c, vars, "toolDiffContext", t.diffContext);
        t.diffHeader = color(c, vars, "borderAccent", t.diffHeader);
        t.diffMeta = color(c, vars, "muted", t.diffMeta);
        t.diffHunk = styledColor(c, vars, "accent", t.diffHunk);
        t.diffAddLine = bgColor(c, vars, "toolSuccessBg", t.diffAddLine);
        t.diffDeleteLine = bgColor(c, vars, "toolErrorBg", t.diffDeleteLine);

        addExtra(t, c, vars, "mdLink", "mdLinkUrl", "mdListBullet", "success", "warning",
            "selectedBg", "userMessageBg", "userMessageText", "customMessageBg", "customMessageText",
            "customMessageLabel", "toolPendingBg", "toolSuccessBg", "toolErrorBg", "thinkingText",
            "thinkingOff", "thinkingMinimal", "thinkingLow", "thinkingMedium", "thinkingHigh", "thinkingXhigh");
        return t;
    }

    private static void addExtra(AnsiTheme t, JsonNode colors, Map<String, String> vars, String... names) {
        for (String name : names) t.extra.put(name, color(colors, vars, name, ""));
    }

    private static String color(JsonNode colors, Map<String, String> vars, String name, String fallback) {
        JsonNode value = colors.get(name);
        if (value == null || value.isNull()) return fallback;
        String spec = colorSpec(value, vars);
        return spec == null ? fallback : spec;
    }

    private static String styledColor(JsonNode colors, Map<String, String> vars, String name, String fallback) {
        JsonNode value = colors.get(name);
        if (value == null || value.isNull()) return fallback;
        String spec = colorSpec(value, vars);
        if (spec == null || spec.isBlank()) return fallback;
        String prefix = stylePrefix(fallback);
        return prefix.isEmpty() ? spec : prefix + ";" + spec;
    }

    private static String stylePrefix(String spec) {
        if (spec == null || spec.isBlank()) return "";
        var sb = new StringBuilder();
        for (String part : spec.split(";")) {
            String p = part.strip();
            if (p.equals("bold") || p.equals("dim") || p.equals("italic")) {
                if (!sb.isEmpty()) sb.append(';');
                sb.append(p);
            }
        }
        return sb.toString();
    }

    private static String bgColor(JsonNode colors, Map<String, String> vars, String name, String fallback) {
        String spec = color(colors, vars, name, "");
        if (spec == null || spec.isBlank()) return fallback;
        if (spec.startsWith("#")) return "bg:" + spec;
        if (spec.startsWith("ansi:")) return "bg-" + spec;
        return fallback;
    }

    private static String colorSpec(JsonNode value, Map<String, String> vars) {
        if (value == null || value.isNull()) return "";
        if (value.isNumber()) return "ansi:" + value.intValue();
        String s = value.asText("");
        if (s.isBlank()) return "";
        return vars.getOrDefault(s, s);
    }

    private static String text(JsonNode node, String fallback) {
        return node == null || node.asText().isBlank() ? fallback : node.asText();
    }

    public String style(String slotName) {
        if (slotName == null || slotName.isBlank() || !enabled) return "";
        return specToAnsi(slotSpec(slotName));
    }

    public String reset() {
        return enabled ? "\033[0m" : "";
    }

    public String styled(String slotName, String text) {
        if (text == null || text.isEmpty()) return "";
        if (!enabled) return text;
        String a = specToAnsi(slotSpec(slotName));
        return a.isEmpty() ? text : a + text + "\033[0m";
    }

    private String slotSpec(String name) {
        return switch (name) {
            case "heading"       -> heading;
            case "bold"          -> bold;
            case "italic"        -> italic;
            case "dim"           -> dim;
            case "strikethrough" -> strikethrough;
            case "codeFence"     -> codeFence;
            case "codeBody"      -> codeBody;
            case "blockquote"    -> blockquote;
            case "hr"            -> hr;
            case "highlight"     -> highlight;
            case "inlineCode"    -> inlineCode;
            case "synKeyword"    -> synKeyword;
            case "synType"       -> synType;
            case "synString"     -> synString;
            case "synNumber"     -> synNumber;
            case "synComment"    -> synComment;
            case "synFunction"   -> synFunction;
            case "synVariable"   -> synVariable;
            case "synOperator"   -> synOperator;
            case "synPunctuation" -> synPunctuation;
            case "prompt"        -> prompt;
            case "promptEcho"    -> promptEcho;
            case "toolLabel"     -> toolLabel;
            case "toolResult"    -> toolResult;
            case "toolError"     -> toolError;
            case "commandMeta"   -> commandMeta;
            case "statusText"    -> statusText;
            case "statusBar"     -> statusBar;
            case "statusWarn"    -> statusWarn;
            case "bannerName"    -> bannerName;
            case "bannerMeta"    -> bannerMeta;
            case "cwdLine"       -> cwdLine;
            case "bashMode"      -> bashMode;
            case "diffHeader"    -> diffHeader;
            case "diffMeta"      -> diffMeta;
            case "diffAdd"       -> diffAdd;
            case "diffDelete"    -> diffDelete;
            case "diffContext"   -> diffContext;
            case "diffHunk"      -> diffHunk;
            case "diffAddLine"   -> diffAddLine;
            case "diffDeleteLine" -> diffDeleteLine;
            default -> extra.getOrDefault(name, "");
        };
    }

    // -- internal -------------------------------------

    private static String specToAnsi(String spec) {
        if (spec == null || spec.isBlank()) return "";
        var codes = new java.util.ArrayList<Integer>();
        String hex = null;
        String bgHex = null;
        for (String part : spec.split(";")) {
            part = part.strip();
            if (part.isEmpty()) continue;
            if (part.startsWith("bg:#") && part.length() == 10) { bgHex = part.substring(4); continue; }
            if (part.startsWith("ansi:")) { appendAnsiColor(codes, part.substring(5), false); continue; }
            if (part.startsWith("bg-ansi:")) { appendAnsiColor(codes, part.substring(8), true); continue; }
            if (part.startsWith("#") && part.length() == 7) { hex = part.substring(1); continue; }
            Integer c = CODE_MAP.get(part);
            if (c != null) codes.add(c);
        }
        if (codes.isEmpty() && hex == null && bgHex == null) return "";
        var sb = new StringBuilder("\033[");
        if (!codes.isEmpty()) {
            for (int i = 0; i < codes.size(); i++) {
                if (i > 0) sb.append(';');
                sb.append(codes.get(i));
            }
        }
        if (hex != null) {
            if (!codes.isEmpty()) sb.append(';');
            try {
                int r = Integer.parseInt(hex.substring(0, 2), 16);
                int g = Integer.parseInt(hex.substring(2, 4), 16);
                int b = Integer.parseInt(hex.substring(4, 6), 16);
                sb.append("38;2;").append(r).append(";").append(g).append(";").append(b);
            } catch (NumberFormatException ignored) {}
        }
        if (bgHex != null) {
            if (!codes.isEmpty() || hex != null) sb.append(';');
            try {
                int r = Integer.parseInt(bgHex.substring(0, 2), 16);
                int g = Integer.parseInt(bgHex.substring(2, 4), 16);
                int b = Integer.parseInt(bgHex.substring(4, 6), 16);
                sb.append("48;2;").append(r).append(";").append(g).append(";").append(b);
            } catch (NumberFormatException ignored) {}
        }
        sb.append('m');
        return sb.toString();
    }

    private static void appendAnsiColor(java.util.List<Integer> codes, String raw, boolean bg) {
        try {
            int n = Integer.parseInt(raw);
            if (n >= 0 && n <= 255) {
                codes.add(bg ? 48 : 38);
                codes.add(5);
                codes.add(n);
            }
        } catch (NumberFormatException ignored) {}
    }

    private static final Map<String, Integer> CODE_MAP = Map.ofEntries(
        Map.entry("bold",       1),
        Map.entry("dim",        2),
        Map.entry("italic",     3),
        Map.entry("underline",  4),
        Map.entry("inverse",    7),
        Map.entry("strikethrough", 9),
        Map.entry("black",     30),
        Map.entry("red",       31),
        Map.entry("green",     32),
        Map.entry("yellow",    33),
        Map.entry("blue",      34),
        Map.entry("magenta",   35),
        Map.entry("cyan",      36),
        Map.entry("white",     37),
        Map.entry("default",   39),
        Map.entry("bright_black",   90),
        Map.entry("bright_red",     91),
        Map.entry("bright_green",   92),
        Map.entry("bright_yellow",  93),
        Map.entry("bright_blue",    94),
        Map.entry("bright_magenta", 95),
        Map.entry("bright_cyan",    96),
        Map.entry("bright_white",   97)
    );
}
