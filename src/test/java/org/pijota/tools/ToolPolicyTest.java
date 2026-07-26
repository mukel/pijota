package org.pijota.tools;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the tool activation policy: denylist wins, a non-empty allowlist is authoritative, else base scope decides. */
class ToolPolicyTest {

    private static ToolPolicy policy(ToolPolicy.Scope base, Set<String> allow, Set<String> deny) {
        return new ToolPolicy(base, allow, deny);
    }

    @Test
    void unrestrictedKeepsEveryDefaultEnabledTool() {
        assertTrue(ToolPolicy.UNRESTRICTED.keep("bash", true, true));
        assertFalse(ToolPolicy.UNRESTRICTED.keep("optIn", false, false)); // a default-disabled tool stays off
    }

    @Test
    void denylistWinsOverEverything() {
        var p = policy(ToolPolicy.Scope.ALL, Set.of("bash"), Set.of("bash")); // even allowlisted
        assertFalse(p.keep("bash", true, true));
    }

    @Test
    void nonEmptyAllowlistIsAuthoritativeAndIgnoresDefaultEnabled() {
        var p = policy(ToolPolicy.Scope.NONE, Set.of("web"), Set.of());
        assertTrue(p.keep("web", false, false), "allowlisted tool is force-enabled regardless of its default");
        assertFalse(p.keep("bash", true, true), "a non-allowlisted tool is off when an allowlist is present");
    }

    @Test
    void baseScopeAllRespectsPerToolDefault() {
        var p = policy(ToolPolicy.Scope.ALL, Set.of(), Set.of());
        assertTrue(p.keep("bash", true, true));
        assertFalse(p.keep("optIn", true, false));
    }

    @Test
    void extensionOnlyDropsBuiltinsKeepsExtensions() {
        var p = policy(ToolPolicy.Scope.EXTENSION_ONLY, Set.of(), Set.of());
        assertFalse(p.keep("bash", true, true)); // built-in
        assertTrue(p.keep("myExt", false, true)); // extension/custom
    }

    @Test
    void baseScopeNoneDisablesEverythingUnlessAllowlisted() {
        var p = policy(ToolPolicy.Scope.NONE, Set.of(), Set.of());
        assertFalse(p.keep("bash", true, true));
        assertFalse(p.keep("myExt", false, true));
    }

    @Test
    void constructorDefensivelyCopiesTheLists() {
        var allow = new java.util.HashSet<>(Set.of("web"));
        var p = policy(ToolPolicy.Scope.NONE, allow, Set.of());
        allow.add("bash"); // mutating the source must not affect the policy
        assertFalse(p.keep("bash", true, true));
    }
}
