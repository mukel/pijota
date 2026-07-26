package org.pijota.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The {@code -Dpijota.*} property overrides the matching {@code PIJOTA_*} environment variable. */
class EnvTest {

    @Test
    void nullWhenNeitherIsSet() {
        assertNull(Env.get("pijota.test.unset.prop", "PIJOTA_UNSET_VAR_XYZ"));
    }

    @Test
    void returnsTheSystemProperty() {
        System.setProperty("pijota.test.value", "from-property");
        try {
            assertEquals("from-property", Env.get("pijota.test.value", "PIJOTA_UNSET_VAR_XYZ"));
        } finally {
            System.clearProperty("pijota.test.value");
        }
    }

    @Test
    void propertyTakesPriorityOverTheEnvironmentVariable() {
        // PATH is a real env var; the property must win over it
        assumeTrue(System.getenv("PATH") != null);
        System.setProperty("pijota.test.path", "from-property");
        try {
            assertEquals("from-property", Env.get("pijota.test.path", "PATH"));
        } finally {
            System.clearProperty("pijota.test.path");
        }
    }

    @Test
    void fallsBackToTheEnvironmentVariableWhenNoProperty() {
        assumeTrue(System.getenv("PATH") != null);
        System.clearProperty("pijota.test.nopath");
        assertEquals(System.getenv("PATH"), Env.get("pijota.test.nopath", "PATH"));
    }

    @Test
    void blankPropertyIsTreatedAsUnset() {
        System.setProperty("pijota.test.blank", "   ");
        try {
            assertNull(Env.get("pijota.test.blank", "PIJOTA_UNSET_VAR_XYZ"));
        } finally {
            System.clearProperty("pijota.test.blank");
        }
    }

    // ---- seconds(): positive whole-seconds knob with fallback ----

    @Test
    void secondsDefaultsWhenUnset() {
        System.clearProperty("pijota.test.secs");
        assertEquals(42, Env.seconds("pijota.test.secs", "PIJOTA_UNSET_VAR_XYZ", 42));
    }

    @Test
    void secondsReadsTheProperty() {
        System.setProperty("pijota.test.secs", "120");
        try {
            assertEquals(120, Env.seconds("pijota.test.secs", "PIJOTA_UNSET_VAR_XYZ", 42));
        } finally {
            System.clearProperty("pijota.test.secs");
        }
    }

    @Test
    void secondsFallsBackOnNonNumericOrNonPositive() {
        for (String bad : new String[] {"abc", "0", "-5", "3.5"}) {
            System.setProperty("pijota.test.secs", bad);
            try {
                assertEquals(42, Env.seconds("pijota.test.secs", "PIJOTA_UNSET_VAR_XYZ", 42),
                                                "value \"" + bad + "\" should fall back");
            } finally {
                System.clearProperty("pijota.test.secs");
            }
        }
    }
}
