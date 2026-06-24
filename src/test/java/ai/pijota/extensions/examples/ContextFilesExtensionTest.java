package ai.pijota.extensions.examples;

import ai.pijota.config.TrustStore;
import ai.pijota.extensions.ExtensionEvent;
import ai.pijota.extensions.testkit.FakeExtensionAPI;
import ai.pijota.extensions.testkit.FakeExtensionRuntime;
import ai.pijota.modes.SlashCommand;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextFilesExtensionTest {

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
        trust.trust(dir.toString());
        var trusted = new StringBuilder("BASE");
        api.fire(new ExtensionEvent.BeforeAgentStart(List.of(), trusted, List.of()));
        assertTrue(trusted.toString().contains("PROJECT RULES"));
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
        trust.trust(dir.toString());
        var ext = new ContextFilesExtension(dir.toString(), trust);
        var api = new FakeExtensionAPI();
        ext.init(api);
        assertTrue(injected(api).contains("DOTPIJOTA CONTEXT"));
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
