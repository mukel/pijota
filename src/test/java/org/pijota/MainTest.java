package org.pijota;

import org.pijota.testkit.StdioCapture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link Main#run}'s non-frontend entry points, the flags that print and exit without starting a TUI/web/
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
        assertEquals("pijota v" + PijotaVersion.current(), stdio.out().strip());
    }

    @Test
    void listModelsFlagPrintsTheCatalogueAndReturnsZero() {
        int code = Main.run(new String[] {"--list-models"});
        assertEquals(0, code);
        assertFalse(stdio.out().isBlank(), "the model catalogue is printed");
    }

    @Test
    void listModelsIncludesCapabilitiesAndPricing() {
        int code = Main.run(new String[] {"--list-models", "gpt"});
        assertEquals(0, code);
        assertTrue(stdio.out().contains("input:"), stdio.out());
        assertTrue(stdio.out().contains("price:"), stdio.out());
    }

    @Test
    void listModelsShowsFormattedContextForEveryModelLine() {
        int code = Main.run(new String[] {"--list-models", "gpt-4o"});

        assertEquals(0, code);
        assertTrue(stdio.out().lines().filter(line -> line.startsWith("  ")).allMatch(
                                        line -> line.contains("context:") && line.matches(".*context: +[0-9]+K.*")),
                                        stdio.out());
    }

    @Test
    void listModelsShowsKnownPricingOrUnknownPricing() {
        int code = Main.run(new String[] {"--list-models", "gpt-4o"});

        assertEquals(0, code);
        assertTrue(stdio.out().lines().filter(line -> line.startsWith("  ")).allMatch(line -> line.contains("price: ")
                                        && (line.contains("/MTok") || line.contains("price: unknown"))), stdio.out());
    }

    @Test
    void listModelsShowsInputModalitiesForEveryModelLine() {
        int code = Main.run(new String[] {"--list-models", "gpt-4o"});

        assertEquals(0, code);
        assertTrue(stdio.out().lines().filter(line -> line.startsWith("  ")).allMatch(
                                        line -> line.contains("input: ") && !line.matches(".*input: +price:.*")),
                                        stdio.out());
    }

    @Test
    void listModelsFilterRestrictsPrintedModels() {
        int code = Main.run(new String[] {"--list-models", "deepseek-v4-flash"});

        assertEquals(0, code);
        assertTrue(stdio.out().contains("deepseek/deepseek-v4-flash"), stdio.out());
        assertFalse(stdio.out().contains("openai/gpt-4o"), stdio.out());
    }
}
