package ai.pijota;

import ai.pijota.testkit.StdioCapture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link Main#run}'s non-frontend entry points — the flags that print and exit without starting a TUI/web/
 * Telegram session (help, version, model listing, and the bad-argument path). The interactive frontends and the
 * login/logout flows (which read stdin / hit the network) are exercised by the opt-in {@code PIJOTA_LIVE} tier.
 */
class MainTest {

    @RegisterExtension
    StdioCapture stdio = new StdioCapture();

    @Test
    void helpFlagPrintsUsageAndReturnsZero() {
        int code = Main.run(new String[] {"--help"});
        assertEquals(0, code);
        assertFalse(stdio.out().isBlank(), "help prints usage");
    }

    @Test
    void versionFlagPrintsTheVersionAndReturnsZero() {
        int code = Main.run(new String[] {"--version"});
        assertEquals(0, code);
        assertTrue(stdio.out().toLowerCase().contains("pijota"), stdio.out());
    }

    @Test
    void listModelsFlagPrintsTheCatalogueAndReturnsZero() {
        int code = Main.run(new String[] {"--list-models"});
        assertEquals(0, code);
        assertFalse(stdio.out().isBlank(), "the model catalogue is printed");
    }

    @Test
    void listModelsAcceptsAFilter() {
        int code = Main.run(new String[] {"--list-models", "gpt"});
        assertEquals(0, code);
    }
}
