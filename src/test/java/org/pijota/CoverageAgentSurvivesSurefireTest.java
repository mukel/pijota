package org.pijota;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code jacoco:prepare-agent} works by setting the {@code argLine} property, which is the same property surefire's
 * {@code <argLine>} sets. Giving surefire a literal value discards the agent, and the only symptom is
 * "Skipping JaCoCo execution due to missing execution data file" in the middle of a passing build, followed by an
 * empty report. Coverage silently read as nothing for as long as that went unnoticed.
 *
 * <p>
 * Nothing compiles the POM, so the rule is a string: interpolate the existing value with {@code @{argLine}} late
 * binding, and give it an empty default so the normal build has something to interpolate.
 */
class CoverageAgentSurvivesSurefireTest {

    private static final Path POM = Path.of("pom.xml");

    @Test
    void surefireKeepsWhateverPrepareAgentPutInArgLine() throws IOException {
        String pom = Files.readString(POM);
        Matcher m = Pattern.compile("<argLine>([^<]*)</argLine>").matcher(pom);

        String surefireArgLine = null;
        while (m.find())
            if (!m.group(1).isBlank()) // the other one is the empty default in <properties>
                surefireArgLine = m.group(1);

        assertNotNull(surefireArgLine, "no surefire <argLine>; if it is gone, so is this guard's reason to exist");
        assertTrue(surefireArgLine.contains("@{argLine}"),
                                        "this replaces the agent instead of extending it, so coverage reports empty: "
                                                                        + surefireArgLine);
    }

    /** {@code @{argLine}} needs something to resolve to when the coverage profile is off, or surefire fails to start. */
    @Test
    void thePropertyHasAnEmptyDefaultForBuildsWithoutCoverage() throws IOException {
        assertTrue(Files.readString(POM).contains("<argLine></argLine>"),
                                        "without an empty default, @{argLine} is unresolved in the normal build");
    }
}
