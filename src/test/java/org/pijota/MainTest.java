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
        // Only models whose provider has a usable key are listed, so the outcome depends on the machine: a
        // configured one prints rows, a bare CI runner prints guidance. Both are correct; assert whichever
        // happened is well-formed, and never that the machine happens to hold a key. The formatting itself is
        // pinned hermetically in ModelDisplayTest.
        var rows = stdio.out().lines().filter(l -> l.startsWith("  ")).toList();
        if (rows.isEmpty()) {
            assertTrue(stdio.out().contains("No usable models"), stdio.out());
            return;
        }
        assertTrue(rows.stream().allMatch(l -> l.contains("input:") && l.contains("price:")), stdio.out());
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
        // Env-independent invariant: whatever is listed matches the filter, and nothing else leaks in. On a machine
        // with no deepseek key nothing is listed, and the guidance names the filter that found nothing.
        var rows = stdio.out().lines().filter(l -> l.startsWith("  ")).toList();
        if (rows.isEmpty()) {
            assertTrue(stdio.out().contains("No usable models matching \"deepseek-v4-flash\""), stdio.out());
            return;
        }
        assertTrue(rows.stream().allMatch(l -> l.contains("deepseek-v4-flash")), stdio.out());
        assertFalse(stdio.out().contains("openai/gpt-4o"), stdio.out());
    }
}
