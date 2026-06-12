package ai.pijota.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TuiCopyTest {

    @Test
    void selectorHintIsNotEmpty() {
        assertNotNull(TuiCopy.SELECTOR_HINT);
        assertFalse(TuiCopy.SELECTOR_HINT.isBlank());
    }
}
