package ai.pijota.extensions.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlanExtensionTest {

    @Test
    void nameIsPlan() {
        var ext = new PlanExtension();
        assertEquals("plan", ext.name());
    }

    @Test
    void descriptionIsNotEmpty() {
        var ext = new PlanExtension();
        assertNotNull(ext.description());
        assertFalse(ext.description().isBlank());
    }
}
