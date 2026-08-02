package org.pijota;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class PijotaVersionTest {

    /**
     * The fallback used when there is no manifest (a source-tree run) has to be the version the build produces, or
     * {@code pijota --version} reports one number while the artifact carries another. Read from the pom rather than
     * restated here: a hardcoded copy makes every release bump fail a test that measures nothing but whether someone
     * remembered to edit it.
     */
    @Test
    void theSourceTreeVersionIsTheVersionTheBuildProduces() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        // The project's own <version>, which is the first one after <artifactId>pijota</artifactId>.
        var m = Pattern.compile("<artifactId>pijota</artifactId>\\s*<version>([^<]+)</version>").matcher(pom);
        assertTrue(m.find(), "could not find the project version in pom.xml");
        assertEquals(m.group(1), PijotaVersion.current());
    }

    @Test
    void aVersionIsAlwaysReported() {
        assertNotNull(PijotaVersion.current());
        assertFalse(PijotaVersion.current().isBlank());
    }
}
