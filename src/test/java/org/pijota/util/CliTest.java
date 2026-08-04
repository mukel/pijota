package org.pijota.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The binary installs as {@code pj}: {@code jbang app install --name pj}, {@code make native} writing
 * {@code target/pj}, and the README all agree. Every message that named a command said {@code pijota}, so a new user
 * read {@code `pijota --list-models`} off their first screen and got "command not found" on their first minute.
 */
class CliTest {

    @Test
    void theNameIsWhatTheDocumentedInstallProduces() {
        assertEquals("pj", Cli.name(), "on the JVM the current process is `java`, so the documented name is the answer");
    }

    /**
     * The product name is not the command name. "pijota v0.2.0" and "pijota: unknown option" are the program
     * identifying itself and are correct; only text telling the user what to type has to match the binary.
     */
    @Test
    void noUserFacingStringStillTellsTheUserToTypePijota() throws Exception {
        var offenders = new ArrayList<String>();
        // A backtick or "USAGE:" before it means the text is quoting a command to run, not naming the program.
        Pattern command = Pattern.compile("(`|USAGE: |\\| )pijota[ `]");

        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String[] lines = Files.readString(file).split("\\R");
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i];
                    if (isComment(line) || !line.contains("\"")) // javadoc and comments are for readers of the source
                        continue;
                    if (command.matcher(line).find())
                        offenders.add("src/main/java/" + Path.of("src/main/java").relativize(file) + ":" + (i + 1)
                                                        + "  " + line.strip());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "these tell the user to run a command that is not installed; use Cli.name(): "
                                        + String.join("\n  ", offenders));
    }

    private static boolean isComment(String line) {
        String t = line.strip();
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*");
    }
}
