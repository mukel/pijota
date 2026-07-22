package ai.pijota.modes;

import ai.pijota.theme.AnsiTheme;

import java.util.Map;
import java.util.Set;

public final class SyntaxHighlighter {
    private static final Set<String> KEYWORDS = Set.of("abstract", "as", "async", "await", "break", "case", "catch",
                                    "class", "const", "continue", "def", "default", "do", "else", "enum", "export",
                                    "extends", "false", "final", "finally", "fn", "for", "from", "func", "function",
                                    "if", "implements", "import", "in", "interface", "let", "match", "module", "new",
                                    "nil", "null", "package", "private", "protected", "public", "record", "return",
                                    "sealed", "static", "struct", "switch", "throws", "trait", "true", "try", "type",
                                    "var", "void", "while", "yield");

    private static final Set<String> TS_KEYWORDS = Set.of("abstract", "as", "async", "await", "break", "case", "catch",
                                    "class", "const", "continue", "debugger", "declare", "default", "delete", "do",
                                    "else", "enum", "export", "extends", "finally", "for", "function", "if",
                                    "implements", "import", "in", "infer", "instanceof", "interface", "is", "keyof",
                                    "let", "module", "namespace", "new", "of", "override", "private", "protected",
                                    "public", "readonly", "return", "satisfies", "super", "switch", "this", "throw",
                                    "try", "type", "typeof", "var", "void", "while", "with", "yield", "true", "false",
                                    "null", "undefined");
    private static final Set<String> JS_KEYWORDS = Set.of("async", "await", "break", "case", "catch", "class", "const",
                                    "continue", "debugger", "default", "delete", "do", "else", "export", "extends",
                                    "finally", "for", "function", "if", "import", "in", "instanceof", "let", "new",
                                    "of", "return", "super", "switch", "this", "throw", "try", "typeof", "var", "void",
                                    "while", "with", "yield", "true", "false", "null", "undefined");

    private static final Map<String, Set<String>> LANG_KEYWORDS = Map.ofEntries(Map.entry("java", Set.of("abstract",
                                    "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
                                    "continue", "default", "do", "double", "else", "enum", "extends", "final",
                                    "finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
                                    "int", "interface", "long", "native", "new", "package", "private", "protected",
                                    "public", "record", "return", "sealed", "short", "static", "strictfp", "super",
                                    "switch", "synchronized", "this", "throw", "throws", "transient", "try", "var",
                                    "void", "volatile", "while", "yield", "true", "false", "null")),
                                    Map.entry("python", Set.of("and", "as", "assert", "async", "await", "break",
                                                                    "class", "continue", "def", "del", "elif", "else",
                                                                    "except", "finally", "for", "from", "global", "if",
                                                                    "import", "in", "is", "lambda", "nonlocal", "not",
                                                                    "or", "pass", "raise", "return", "try", "while",
                                                                    "with", "yield", "True", "False", "None")),
                                    Map.entry("javascript", JS_KEYWORDS), Map.entry("typescript", TS_KEYWORDS),
                                    Map.entry("ts", TS_KEYWORDS), Map.entry("jsx", JS_KEYWORDS),
                                    Map.entry("tsx", TS_KEYWORDS),
                                    Map.entry("rust", Set.of("as", "async", "await", "break", "const", "continue",
                                                                    "crate", "dyn", "else", "enum", "extern", "false",
                                                                    "fn", "for", "if", "impl", "in", "let", "loop",
                                                                    "match", "mod", "move", "mut", "pub", "ref",
                                                                    "return", "self", "static", "struct", "super",
                                                                    "trait", "true", "type", "unsafe", "use", "where",
                                                                    "while", "yield")),
                                    Map.entry("go", Set.of("break", "case", "chan", "const", "continue", "default",
                                                                    "defer", "else", "fallthrough", "for", "func", "go",
                                                                    "goto", "if", "import", "interface", "map",
                                                                    "package", "range", "return", "select", "struct",
                                                                    "switch", "type", "var", "true", "false", "nil")),
                                    Map.entry("c", Set.of("auto", "break", "case", "char", "const", "continue",
                                                                    "default", "do", "double", "else", "enum", "extern",
                                                                    "float", "for", "goto", "if", "inline", "int",
                                                                    "long", "register", "restrict", "return", "short",
                                                                    "signed", "sizeof", "static", "struct", "switch",
                                                                    "typedef", "union", "unsigned", "void", "volatile",
                                                                    "while")),
                                    Map.entry("cpp", Set.of("alignas", "alignof", "auto", "bool", "break", "case",
                                                                    "catch", "char", "char8_t", "char16_t", "char32_t",
                                                                    "class", "concept", "const", "consteval",
                                                                    "constexpr", "constinit", "const_cast", "continue",
                                                                    "co_await", "co_return", "co_yield", "decltype",
                                                                    "default", "delete", "do", "double", "dynamic_cast",
                                                                    "else", "enum", "explicit", "export", "extern",
                                                                    "false", "float", "for", "friend", "goto", "if",
                                                                    "inline", "int", "long", "mutable", "namespace",
                                                                    "new", "noexcept", "nullptr", "operator", "private",
                                                                    "protected", "public", "register",
                                                                    "reinterpret_cast", "requires", "return", "short",
                                                                    "signed", "sizeof", "static", "static_assert",
                                                                    "static_cast", "struct", "switch", "template",
                                                                    "this", "thread_local", "throw", "true", "try",
                                                                    "typedef", "typeid", "typename", "union",
                                                                    "unsigned", "using", "virtual", "void", "volatile",
                                                                    "wchar_t", "while")),
                                    Map.entry("csharp", Set.of("abstract", "as", "base", "bool", "break", "byte",
                                                                    "case", "catch", "char", "checked", "class",
                                                                    "const", "continue", "decimal", "default",
                                                                    "delegate", "do", "double", "else", "enum", "event",
                                                                    "explicit", "extern", "false", "finally", "fixed",
                                                                    "float", "for", "foreach", "goto", "if", "implicit",
                                                                    "in", "int", "interface", "internal", "is", "lock",
                                                                    "long", "namespace", "new", "null", "object",
                                                                    "operator", "out", "override", "params", "private",
                                                                    "protected", "public", "readonly", "record", "ref",
                                                                    "return", "sbyte", "sealed", "short", "sizeof",
                                                                    "stackalloc", "static", "string", "struct",
                                                                    "switch", "this", "throw", "true", "try", "typeof",
                                                                    "uint", "ulong", "unchecked", "unsafe", "ushort",
                                                                    "using", "var", "virtual", "void", "volatile",
                                                                    "while")),
                                    Map.entry("kotlin", Set.of("abstract", "actual", "annotation", "as", "break", "by",
                                                                    "catch", "class", "companion", "const",
                                                                    "constructor", "continue", "data", "do", "dynamic",
                                                                    "else", "enum", "expect", "external", "false",
                                                                    "final", "finally", "for", "fun", "if", "import",
                                                                    "in", "infix", "init", "inline", "inner",
                                                                    "interface", "internal", "is", "lateinit",
                                                                    "noinline", "null", "object", "open", "operator",
                                                                    "out", "override", "package", "private",
                                                                    "protected", "public", "reified", "return",
                                                                    "sealed", "super", "suspend", "tailrec", "this",
                                                                    "throw", "true", "try", "typealias", "val", "var",
                                                                    "vararg", "when", "while")),
                                    Map.entry("scala", Set.of("abstract", "case", "catch", "class", "def", "do", "else",
                                                                    "enum", "extends", "false", "final", "finally",
                                                                    "for", "forSome", "given", "if", "implicit",
                                                                    "import", "lazy", "match", "new", "null", "object",
                                                                    "override", "package", "private", "protected",
                                                                    "return", "sealed", "super", "this", "throw",
                                                                    "trait", "true", "try", "type", "using", "val",
                                                                    "var", "while", "with", "yield")),
                                    Map.entry("ruby", Set.of("BEGIN", "END", "alias", "and", "begin", "break", "case",
                                                                    "class", "def", "defined", "do", "else", "elsif",
                                                                    "end", "ensure", "false", "for", "if", "in",
                                                                    "module", "next", "nil", "not", "or", "redo",
                                                                    "rescue", "retry", "return", "self", "super",
                                                                    "then", "true", "undef", "unless", "until", "when",
                                                                    "while", "yield")),
                                    Map.entry("php", Set.of("abstract", "and", "array", "as", "break", "callable",
                                                                    "case", "catch", "class", "clone", "const",
                                                                    "continue", "declare", "default", "die", "do",
                                                                    "echo", "else", "elseif", "empty", "enddeclare",
                                                                    "endfor", "endforeach", "endif", "endswitch",
                                                                    "endwhile", "eval", "exit", "extends", "final",
                                                                    "finally", "fn", "for", "foreach", "function",
                                                                    "global", "goto", "if", "implements", "include",
                                                                    "include_once", "instanceof", "insteadof",
                                                                    "interface", "isset", "list", "match", "namespace",
                                                                    "new", "null", "or", "print", "private",
                                                                    "protected", "public", "readonly", "require",
                                                                    "require_once", "return", "static", "switch",
                                                                    "throw", "trait", "true", "try", "unset", "use",
                                                                    "var", "while", "xor", "yield")),
                                    Map.entry("sql", Set.of("add", "all", "alter", "and", "any", "as", "asc", "backup",
                                                                    "between", "case", "check", "column", "constraint",
                                                                    "create", "database", "default", "delete", "desc",
                                                                    "distinct", "drop", "exec", "exists", "foreign",
                                                                    "from", "full", "group", "having", "in", "index",
                                                                    "inner", "insert", "into", "is", "join", "left",
                                                                    "like", "limit", "not", "null", "on", "or", "order",
                                                                    "outer", "primary", "procedure", "right", "select",
                                                                    "set", "table", "top", "truncate", "union",
                                                                    "unique", "update", "values", "view", "where")),
                                    Map.entry("json", Set.of()),
                                    Map.entry("yaml", Set.of("true", "false", "null", "yes", "no", "on", "off")),
                                    Map.entry("xml", Set.of()), Map.entry("html", Set.of()),
                                    Map.entry("css", Set.of("align", "all", "animation", "background", "border",
                                                                    "bottom", "box", "color", "column", "content",
                                                                    "cursor", "display", "filter", "flex", "float",
                                                                    "font", "gap", "grid", "height", "justify", "left",
                                                                    "margin", "max", "min", "opacity", "overflow",
                                                                    "padding", "position", "right", "row", "text",
                                                                    "top", "transform", "transition", "width", "z")),
                                    Map.entry("bash", Set.of("case", "do", "done", "elif", "else", "esac", "fi", "for",
                                                                    "function", "if", "in", "select", "then", "time",
                                                                    "until", "while")),
                                    Map.entry("sh", Set.of("case", "do", "done", "elif", "else", "esac", "fi", "for",
                                                                    "function", "if", "in", "select", "then", "time",
                                                                    "until", "while")));

    private static final Set<String> TYPE_KEYWORDS = Set.of("class", "extends", "implements", "new", "interface",
                                    "enum", "record", "struct", "trait", "type", "throws", "throw", "instanceof",
                                    "typeof", "as", "is");

    private static final Set<Character> OPERATOR_CHARS = Set.of('+', '-', '*', '/', '%', '=', '<', '>', '!', '&', '|',
                                    '^', '~', '?', ':');

    private static final Set<Character> PUNCTUATION_CHARS = Set.of('.', ',', ';', '(', ')', '{', '}', '[', ']', '@');

    private final AnsiTheme theme;
    private final boolean shellLike;
    private final String language;
    private final Set<String> keywords;

    public SyntaxHighlighter(String language, AnsiTheme theme) {
        this.theme = theme;
        this.language = language == null ? "" : language.toLowerCase().strip();
        this.shellLike = Set.of("sh", "bash", "shell", "zsh", "yaml", "yml", "make", "makefile")
                                        .contains(this.language);
        this.keywords = LANG_KEYWORDS.getOrDefault(this.language, KEYWORDS);
    }

    public static String languageForFilename(String filename) {
        if (filename == null || filename.isBlank())
            return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0)
            return "";
        String ext = filename.substring(dot + 1).toLowerCase();
        return switch (ext) {
            case "java", "kt", "scala", "groovy" -> "java";
            case "js", "ts", "jsx", "tsx", "json", "jsonc" -> "javascript";
            case "py", "pyi" -> "python";
            case "rs" -> "rust";
            case "go" -> "go";
            case "c", "h" -> "c";
            case "cpp", "hpp", "cc", "hh", "cxx" -> "cpp";
            case "md", "mdx" -> "markdown";
            case "sh", "bash", "zsh" -> "bash";
            case "yaml", "yml" -> "yaml";
            case "xml", "html", "htm" -> "xml";
            case "sql" -> "sql";
            case "rb" -> "ruby";
            default -> ext;
        };
    }

    public String highlightLine(String line) {
        if (!theme.enabled || line == null || line.isEmpty())
            return line;
        if ("diff".equals(language) || "patch".equals(language))
            return highlightDiff(line);
        int comment = commentStart(line);
        String code = comment >= 0 ? line.substring(0, comment) : line;
        String suffix = comment >= 0 ? style("synComment", line.substring(comment)) : "";
        return highlightCode(code) + suffix + theme.reset();
    }

    private String highlightDiff(String line) {
        if (line.isEmpty())
            return line;
        char first = line.charAt(0);
        return switch (first) {
            case '+' -> style("diffAdd", line);
            case '-' -> style("diffDelete", line);
            case '@' -> style("diffHunk", line);
            default -> style("diffContext", line);
        };
    }

    private String highlightCode(String line) {
        var out = new StringBuilder();
        String prevWord = null;
        for (int i = 0; i < line.length();) {
            char ch = line.charAt(i);

            if (OPERATOR_CHARS.contains(ch) && !isPartOfCommentOrString(line, i)) {
                int end = i + 1;
                while (end < line.length() && OPERATOR_CHARS.contains(line.charAt(end)))
                    end++;
                if (prevWord != null && (ch == '<' || ch == '>'))
                    out.append(style("synPunctuation", line.substring(i, end)));
                else
                    out.append(style("synOperator", line.substring(i, end)));
                i = end;
                prevWord = null;
                continue;
            }

            if (PUNCTUATION_CHARS.contains(ch)) {
                out.append(style("synPunctuation", String.valueOf(ch)));
                i++;
                prevWord = null;
                continue;
            }

            if (ch == '"' || ch == '\'') {
                int end = quotedEnd(line, i, ch);
                out.append(highlightString(line.substring(i, end)));
                i = end;
                prevWord = null;
                continue;
            }

            if (Character.isDigit(ch)) {
                int end = i + 1;
                while (end < line.length() && isNumberChar(line.charAt(end)))
                    end++;
                out.append(style("synNumber", line.substring(i, end)));
                i = end;
                prevWord = null;
                continue;
            }

            if (Character.isJavaIdentifierStart(ch)) {
                int end = i + 1;
                while (end < line.length() && Character.isJavaIdentifierPart(line.charAt(end)))
                    end++;
                String word = line.substring(i, end);

                if (keywords.contains(word)) {
                    out.append(style("synKeyword", word));
                    prevWord = word;
                } else if (isTypeName(word, prevWord, i)) {
                    out.append(style("synType", word));
                    prevWord = word;
                } else if (isFunction(prevWord, line, end)) {
                    out.append(style("synFunction", word));
                    prevWord = word;
                } else {
                    out.append(style("synVariable", word));
                    prevWord = word;
                }
                i = end;
                continue;
            }

            out.append(ch);
            i++;
            prevWord = null;
        }
        return out.toString();
    }

    private boolean isTypeName(String word, String prevWord, int start) {
        if (prevWord != null && TYPE_KEYWORDS.contains(prevWord))
            return true;
        // a capitalized word: always a type at line start, otherwise only if it's more than a single letter
        return Character.isUpperCase(word.charAt(0)) && (start == 0 || word.length() > 1);
    }

    private boolean isFunction(String prevWord, String line, int end) {
        if ("def".equals(prevWord))
            return true;
        int i = end;
        while (i < line.length() && Character.isWhitespace(line.charAt(i)))
            i++;
        return i < line.length() && line.charAt(i) == '('; // name immediately followed by "(" → a call/def
    }

    private String highlightString(String text) {
        return theme.styled("synString", text);
    }

    private boolean isPartOfCommentOrString(String line, int pos) {
        for (int i = 0; i < pos; i++) {
            char ch = line.charAt(i);
            if (ch == '"' || ch == '\'')
                i = quotedEnd(line, i, ch) - 1;
            else if (ch == '#')
                return true;
            else if (!shellLike && i + 1 < line.length() && ch == '/' && line.charAt(i + 1) == '/')
                return true;
        }
        return false;
    }

    private int commentStart(String line) {
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"' || ch == '\'')
                i = quotedEnd(line, i, ch) - 1;
            else if (ch == '#')
                return i;
            else if (!shellLike && i + 1 < line.length() && ch == '/' && line.charAt(i + 1) == '/')
                return i;
        }
        return -1;
    }

    private static int quotedEnd(String line, int start, char quote) {
        int i = start + 1;
        while (i < line.length()) {
            if (line.charAt(i) == '\\')
                i += 2;
            else if (line.charAt(i++) == quote)
                break;
        }
        return Math.min(i, line.length());
    }

    private static boolean isNumberChar(char ch) {
        // hex digits (0-9, a-f, A-F) plus the '.', '_' separators and the '0x' marker
        return Character.digit(ch, 16) >= 0 || ch == '.' || ch == '_' || ch == 'x' || ch == 'X';
    }

    private String style(String slot, String text) {
        return theme.style(slot) + text + theme.reset();
    }
}
