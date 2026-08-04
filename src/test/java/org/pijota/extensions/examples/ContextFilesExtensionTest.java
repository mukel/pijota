package org.pijota.extensions.examples;

import org.pijota.config.ProjectTrust;
import org.pijota.config.TrustStore;
import org.pijota.extensions.ExtensionEvent;
import org.pijota.extensions.testkit.FakeExtensionAPI;
import org.pijota.extensions.testkit.FakeExtensionRuntime;
import org.pijota.modes.SlashCommand;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextFilesExtensionTest {

    /**
     * The hint used to name only AGENTS.md/CLAUDE.md and only appear when one existed, so a project whose resources are
     * all under .pijota/ -- skills, agents, commands -- said nothing at all. They were skipped in silence, and silence
     * reads as a missing feature rather than a decision the user made.
     */
    @Test
    void theUntrustedHintNamesWhateverIsActuallyWithheld() {
        assertTrue(ContextFilesExtension.untrustedHint(true, false).contains("AGENTS.md"));

        String resourcesOnly = ContextFilesExtension.untrustedHint(false, true);
        assertTrue(resourcesOnly.contains(".pijota/"), resourcesOnly);
        assertTrue(resourcesOnly.contains("skills"), "it should say what is in there: " + resourcesOnly);

        String both = ContextFilesExtension.untrustedHint(true, true);
        assertTrue(both.contains(".pijota/") && both.contains("AGENTS.md"), both);

        for (String hint : java.util.List.of(resourcesOnly, both, ContextFilesExtension.untrustedHint(true, false)))
            assertTrue(hint.contains("/trust"), "every form must say how to fix it: " + hint);
    }

    @Test
    void nameIsContextFiles() {
        var ext = new ContextFilesExtension("/cwd");
        assertEquals("context-files", ext.name());
    }

    @Test
    void descriptionIsNotEmpty() {
        var ext = new ContextFilesExtension("/cwd");
        assertNotNull(ext.description());
        assertFalse(ext.description().isBlank());
    }

    @Test
    void contextFilesInjectedOnlyWhenProjectIsTrusted() throws Exception {
        Path dir = Files.createTempDirectory("pijota-ctx");
        Files.writeString(dir.resolve("AGENTS.md"), "PROJECT RULES");
        var trust = newTrust();
        var ext = new ContextFilesExtension(dir.toString(), trust);
        var api = new FakeExtensionAPI();
        ext.init(api);

        // untrusted: a cloned repo's AGENTS.md must NOT reach the prompt
        var untrusted = new StringBuilder("BASE");
        api.fire(new ExtensionEvent.BeforeAgentStart(List.of(), untrusted, List.of()));
        assertFalse(untrusted.toString().contains("PROJECT RULES"));

        // once trusted, it loads from the next turn
        grantTrust(trust, dir);
        var trusted = new StringBuilder("BASE");
        api.fire(new ExtensionEvent.BeforeAgentStart(List.of(), trusted, List.of()));
        assertTrue(trusted.toString().contains("PROJECT RULES"));
    }

    /**
     * {@code -na} refuses a folder for this run whatever the store remembers. Reading the store directly meant the flag
     * stopped extensions, skills and MCP servers while AGENTS.md was injected anyway, which is the surface the flag
     * exists for.
     */
    @Test
    void aRunThatRefusesTrustIsHonouredOverTheRememberedDecision() throws Exception {
        Path dir = Files.createTempDirectory("pijota-ctx");
        Files.writeString(dir.resolve("AGENTS.md"), "PROJECT RULES");
        var trust = newTrust();
        trust.trust(dir.toString()); // remembered from an earlier session
        var ext = new ContextFilesExtension(dir.toString(), trust);
        var api = new FakeExtensionAPI();
        ext.init(api);

        ProjectTrust.resolve(dir.toString(), false); // this run said no
        assertFalse(injected(api).contains("PROJECT RULES"), "-na must outrank the store");

        var rt = new FakeExtensionRuntime();
        ext.onReady(rt);
        assertTrue(rt.keyedStatuses().getOrDefault("trust", "").contains("untrusted"),
                                        "and the status line must say so, not claim the folder is trusted");
    }

    @Test
    void trustCommandEnablesThenRevokeDisablesInjection() throws Exception {
        Path dir = Files.createTempDirectory("pijota-ctx");
        Files.writeString(dir.resolve("AGENTS.md"), "PROJECT RULES");
        var trust = newTrust();
        var ext = new ContextFilesExtension(dir.toString(), trust);
        var api = new FakeExtensionAPI();
        ext.init(api);
        SlashCommand cmd = trustCommand(api);

        assertFalse(injected(api).contains("PROJECT RULES")); // untrusted
        cmd.handler().accept(""); // /trust
        assertTrue(trust.isTrusted(dir.toString()));
        assertTrue(injected(api).contains("PROJECT RULES")); // loads now
        cmd.handler().accept("revoke"); // /trust revoke
        assertFalse(trust.isTrusted(dir.toString()));
        assertFalse(injected(api).contains("PROJECT RULES"));
    }

    @Test
    void trustStatusCommandReportsState() throws Exception {
        Path dir = Files.createTempDirectory("pijota-ctx");
        Files.writeString(dir.resolve("AGENTS.md"), "X");
        var trust = newTrust();
        var ext = new ContextFilesExtension(dir.toString(), trust);
        var api = new FakeExtensionAPI();
        ext.init(api);
        SlashCommand cmd = trustCommand(api);

        cmd.handler().accept("status");
        assertTrue(api.status().contains("NOT trusted"), api.status());
        cmd.handler().accept("");
        cmd.handler().accept("status");
        assertTrue(api.status().contains("trusted"), api.status());
    }

    @Test
    void onReadyHintsWhenUntrustedWithContextFiles() throws Exception {
        Path dir = Files.createTempDirectory("pijota-ctx");
        Files.writeString(dir.resolve("AGENTS.md"), "X");
        var trust = newTrust();
        var rt = new FakeExtensionRuntime();
        var ext = new ContextFilesExtension(dir.toString(), trust);
        ext.init(new FakeExtensionAPI(rt));
        ext.onReady(rt);
        assertTrue(rt.keyedStatuses().getOrDefault("trust", "").contains("untrusted"));
    }

    @Test
    void onReadyDoesNotHintWhenNoContextFiles() throws Exception {
        Path dir = Files.createTempDirectory("pijota-empty"); // no AGENTS.md/CLAUDE.md
        var trust = newTrust();
        var rt = new FakeExtensionRuntime();
        var ext = new ContextFilesExtension(dir.toString(), trust);
        ext.init(new FakeExtensionAPI(rt));
        ext.onReady(rt);
        assertNull(rt.keyedStatuses().get("trust"));
    }

    @Test
    void discoversPijotaContextMdWhenTrusted() throws Exception {
        Path dir = Files.createTempDirectory("pijota-ctx");
        Files.createDirectories(dir.resolve(".pijota"));
        Files.writeString(dir.resolve(".pijota/CONTEXT.md"), "DOTPIJOTA CONTEXT");
        var trust = newTrust();
        grantTrust(trust, dir);
        var ext = new ContextFilesExtension(dir.toString(), trust);
        var api = new FakeExtensionAPI();
        ext.init(api);
        assertTrue(injected(api).contains("DOTPIJOTA CONTEXT"));
    }

    /**
     * Symlinking CLAUDE.md to AGENTS.md is the usual way to keep one set of conventions for every agent, and pijota
     * reads both names. Without dedup that bought a second, identical copy of them in the system prompt of every turn.
     */
    @Test
    void aClaudeMdSymlinkedToAgentsMdIsLoadedOnce() throws Exception {
        Path dir = Files.createTempDirectory("pijota-ctx");
        Files.writeString(dir.resolve("AGENTS.md"), "PROJECT RULES");
        Files.createSymbolicLink(dir.resolve("CLAUDE.md"), Path.of("AGENTS.md"));
        var trust = newTrust();
        grantTrust(trust, dir);
        var ext = new ContextFilesExtension(dir.toString(), trust);
        var api = new FakeExtensionAPI();
        ext.init(api);

        String injected = injected(api);
        assertTrue(injected.contains("PROJECT RULES"), injected);
        assertEquals(1, injected.split("PROJECT RULES", -1).length - 1, "the same file must not be read twice");
    }

    /** Two genuinely different files still both load; dedup is by real path, not by name. */
    @Test
    void distinctAgentsAndClaudeFilesBothLoad() throws Exception {
        Path dir = Files.createTempDirectory("pijota-ctx");
        Files.writeString(dir.resolve("AGENTS.md"), "SHARED RULES");
        Files.writeString(dir.resolve("CLAUDE.md"), "CLAUDE ONLY");
        var trust = newTrust();
        grantTrust(trust, dir);
        var ext = new ContextFilesExtension(dir.toString(), trust);
        var api = new FakeExtensionAPI();
        ext.init(api);

        String injected = injected(api);
        assertTrue(injected.contains("SHARED RULES") && injected.contains("CLAUDE ONLY"), injected);
    }

    /**
     * The pair {@code /trust} performs: the store remembers the decision for next time, and {@link ProjectTrust}
     * carries it for this run. Tests grant trust the way production does, because granting it only in the store is
     * exactly the state {@code -na} produces and must not load anything.
     */
    private static void grantTrust(TrustStore trust, Path dir) {
        trust.trust(dir.toString());
        ProjectTrust.resolve(dir.toString(), true);
    }

    private static TrustStore newTrust() throws Exception {
        return new TrustStore(Files.createTempDirectory("pijota-trust").resolve("trust.json"));
    }

    private static SlashCommand trustCommand(FakeExtensionAPI api) {
        return api.slashCommands().stream().filter(c -> c.name().equals("trust")).findFirst().orElseThrow();
    }

    private static String injected(FakeExtensionAPI api) {
        var sb = new StringBuilder("BASE");
        api.fire(new ExtensionEvent.BeforeAgentStart(List.of(), sb, List.of()));
        return sb.toString();
    }
}
