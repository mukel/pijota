package ai.pijota.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small, terminal-facing error reporter. Keeps user-facing messages concise while routing full diagnostics to the
 * configured SLF4J backend (file/JSON). Used for startup and fatal errors where the TUI is not yet available.
 */
public final class CliErrorReporter {

    private static final Logger logger = LoggerFactory.getLogger(CliErrorReporter.class);

    private CliErrorReporter() {}

    /** Logs the full error and prints a concise message to stderr. Returns a non-zero exit code. */
    public static int fatal(String message, Throwable cause) {
        logger.error(message, cause);
        System.err.println("Error: " + message);
        if (cause != null) {
            // route through ErrorUtil: unwraps Completion/ExecutionException wrappers and scrubs secrets (API keys,
            // bearer tokens) so a fatal never leaks credentials to the terminal — same contract as every other path.
            String detail = ErrorUtil.message(cause);
            if (!detail.isBlank())
                System.err.println("Cause: " + detail);
        }
        return 1;
    }

    /** Logs and prints a concise fatal message. Returns a non-zero exit code. */
    public static int fatal(String message) {
        logger.error(message);
        System.err.println("Error: " + message);
        return 1;
    }
}
