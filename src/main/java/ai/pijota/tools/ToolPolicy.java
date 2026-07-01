package ai.pijota.tools;

import java.util.Set;

/**
 * Which tools are active, resolved by <em>name</em> across all sources (built-in, extension, custom) — origin only
 * seeds the default. Mirrors Pi: a base scope ({@code --no-tools} / {@code --no-builtin-tools}) sets what's on by
 * default, then {@code --tools} (allowlist) and {@code --exclude-tools} (denylist) refine it.
 */
public record ToolPolicy(Scope base, Set<String> allow, Set<String> deny) {

    /** What is enabled before the allow/deny lists apply. */
    public enum Scope {
        ALL, // everything on (default)
        EXTENSION_ONLY, // --no-builtin-tools: only extension/custom tools
        NONE // --no-tools: nothing on unless allowlisted back
    }

    /** No restriction: every default-enabled tool stays on. */
    public static final ToolPolicy UNRESTRICTED = new ToolPolicy(Scope.ALL, Set.of(), Set.of());

    public ToolPolicy {
        allow = Set.copyOf(allow);
        deny = Set.copyOf(deny);
    }

    /**
     * Whether a tool stays active. Denylist wins; a non-empty allowlist is authoritative (force-enables exactly its
     * members, ignoring {@code defaultEnabled}); otherwise the base scope decides, gated by the tool's own default.
     */
    public boolean keep(String name, boolean builtin, boolean defaultEnabled) {
        if (deny.contains(name))
            return false;
        if (!allow.isEmpty())
            return allow.contains(name);
        boolean byDefault = switch (base) {
            case ALL -> true;
            case EXTENSION_ONLY -> !builtin;
            case NONE -> false;
        };
        return byDefault && defaultEnabled;
    }
}
