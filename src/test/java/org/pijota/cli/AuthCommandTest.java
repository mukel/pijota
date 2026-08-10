package org.pijota.cli;

import org.pijota.config.AppConfig;
import org.pijota.config.SettingsManager;
import org.pijota.testkit.StdioCapture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuthCommandTest {

    @RegisterExtension
    final StdioCapture stdio = new StdioCapture();

    private static SettingsManager withKeys(Map<String, String> keys) {
        return new SettingsManager(new AppConfig().toBuilder().apiKeys(keys).build(), ignored -> {});
    }

    private int run(SettingsManager settings, String... args) {
        return AuthCommand.run(List.of(args), settings);
    }

    @Test
    void printsOnlyTheKeySoCommandSubstitutionWorks() {
        int code = run(withKeys(Map.of("openai", "sk-secret")), "print-api-key", "--provider", "openai");
        assertEquals(0, code);
        // The whole point is $(pijota auth ...); a label or a blank line would end up inside the header.
        assertEquals("sk-secret", stdio.out().strip());
        assertEquals("sk-secret\n", stdio.out().replace("\r\n", "\n"), "exactly the key and one newline");
        assertTrue(stdio.err().isEmpty(), "nothing on stderr on success: " + stdio.err());
    }

    @Test
    void missingKeyFailsWithTheEnvVarToSetAndNothingOnStdout() {
        int code = run(withKeys(Map.of()), "print-api-key", "--provider", "anthropic");
        assertEquals(1, code);
        assertTrue(stdio.out().isEmpty(), "a caller must not capture an empty string as if it were a key");
        assertTrue(stdio.err().contains("ANTHROPIC_API_KEY"), "say which variable to set: " + stdio.err());
    }

    @Test
    void providerIsRequired() {
        assertEquals(2, run(withKeys(Map.of()), "print-api-key"));
        assertTrue(stdio.err().contains("--provider"), stdio.err());
        assertTrue(stdio.out().isEmpty());
    }

    @Test
    void unknownSubcommandNamesTheValidOnes() {
        assertEquals(2, run(withKeys(Map.of()), "frobnicate", "--provider", "openai"));
        assertTrue(stdio.err().contains("print-api-key") && stdio.err().contains("print-bearer-token"), stdio.err());
    }

    @Test
    void noArgumentsPrintsUsageToStderrNotStdout() {
        assertEquals(2, run(withKeys(Map.of())));
        assertTrue(stdio.err().contains("USAGE"), stdio.err());
        assertTrue(stdio.out().isEmpty(), "usage on stdout would be captured as a credential");
    }

    @Test
    void helpIsSuccessNotAnError() {
        assertEquals(0, run(withKeys(Map.of()), "--help"));
        assertTrue(stdio.out().contains("print-bearer-token"), stdio.out());
        assertTrue(stdio.out().contains("pj auth"), stdio.out());
        assertTrue(stdio.err().isEmpty(), stdio.err());
    }

    @Test
    void bearerTokenIsRefusedForProvidersThatDoNotUseOAuth() {
        // Printing an API key here would produce an Authorization: Bearer header that fails like a server error.
        assertEquals(1, run(withKeys(Map.of("deepseek", "sk-secret")), "print-bearer-token", "--provider", "deepseek"));
        assertTrue(stdio.out().isEmpty(), "must not fall back to the API key: " + stdio.out());
        assertTrue(stdio.err().contains("print-api-key"), "point at the right command: " + stdio.err());
    }

    @Test
    void keylessProviderSaysSoRatherThanPrintingEmptiness() {
        assertEquals(1, run(withKeys(Map.of()), "print-api-key", "--provider", "ollama"));
        assertTrue(stdio.out().isEmpty());
    }
}
