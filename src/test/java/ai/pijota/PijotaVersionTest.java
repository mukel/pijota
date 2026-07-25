package ai.pijota;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PijotaVersionTest {

    @Test
    void currentReturnsProjectVersionInSourceTreeRuns() {
        assertEquals("0.1.0-SNAPSHOT", PijotaVersion.current());
    }
}
