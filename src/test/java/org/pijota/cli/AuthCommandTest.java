package org.pijota.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.pijota.config.AppConfig;
import org.pijota.config.SettingsManager;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthCommandTest {

    private final PrintStream realOut = System.out;
    private final PrintStream realErr = System.err;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void capture() {
        System.setOut(new PrintStream(out));
        System.setErr(new PrintStream(err));
    }

    @AfterEach
    void restore() {
        System.setOut(realOut);
        System.setErr(realErr);
    }

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
        assertEquals("sk-secret", out.toString().strip());
        assertEquals("sk-secret\n", out.toString().replace("\r\n", "\n"), "exactly the key and one newline");
        assertTrue(err.toString().isEmpty(), "nothing on stderr on success: " + err);
    }

    @Test
    void missingKeyFailsWithTheEnvVarToSetAndNothingOnStdout() {
        int code = run(withKeys(Map.of()), "print-api-key", "--provider", "anthropic");
        assertEquals(1, code);
        assertTrue(out.toString().isEmpty(), "a caller must not capture an empty string as if it were a key");
        assertTrue(err.toString().contains("ANTHROPIC_API_KEY"), "say which variable to set: " + err);
    }

    @Test
    void providerIsRequired() {
        assertEquals(2, run(withKeys(Map.of()), "print-api-key"));
        assertTrue(err.toString().contains("--provider"), err.toString());
        assertTrue(out.toString().isEmpty());
    }

    @Test
    void unknownSubcommandNamesTheValidOnes() {
        assertEquals(2, run(withKeys(Map.of()), "frobnicate", "--provider", "openai"));
        assertTrue(err.toString().contains("print-api-key") && err.toString().contains("print-bearer-token"),
                                        err.toString());
    }

    @Test
    void noArgumentsPrintsUsageToStderrNotStdout() {
        assertEquals(2, run(withKeys(Map.of())));
        assertTrue(err.toString().contains("USAGE"), err.toString());
        assertTrue(out.toString().isEmpty(), "usage on stdout would be captured as a credential");
    }

    @Test
    void helpIsSuccessNotAnError() {
        assertEquals(0, run(withKeys(Map.of()), "--help"));
        assertTrue(err.toString().contains("print-bearer-token"), err.toString());
    }

    @Test
    void bearerTokenIsRefusedForProvidersThatDoNotUseOAuth() {
        // Printing an API key here would produce an Authorization: Bearer header that fails like a server error.
        assertEquals(1, run(withKeys(Map.of("deepseek", "sk-secret")), "print-bearer-token", "--provider", "deepseek"));
        assertTrue(out.toString().isEmpty(), "must not fall back to the API key: " + out);
        assertTrue(err.toString().contains("print-api-key"), "point at the right command: " + err);
    }

    @Test
    void keylessProviderSaysSoRatherThanPrintingEmptiness() {
        assertEquals(1, run(withKeys(Map.of()), "print-api-key", "--provider", "ollama"));
        assertTrue(out.toString().isEmpty());
    }
}
