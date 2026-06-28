package ai.pijota.extensions.examples;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WhimsicalExtensionTest {

    @Test
    void nameIsWhimsical() {
        var ext = new WhimsicalExtension();
        assertEquals("whimsical", ext.name());
    }

    @Test
    void descriptionIsNotEmpty() {
        var ext = new WhimsicalExtension();
        assertNotNull(ext.description());
        assertFalse(ext.description().isBlank());
    }

    @Test
    void randomPhraseReturnsNonNull() {
        for (int i = 0; i < 50; i++) {
            assertNotNull(WhimsicalExtension.randomPhrase());
        }
    }

    @Test
    void randomPhraseIsFromKnownSet() {
        var phrases = new String[] {"pondering the orb", "consulting the spirits", "doing mental gymnastics",
                "summoning the answer", "staring into the abyss", "asking the magic conch", "rearranging neurons",
                "whispering to the machines", "searching the archives", "having a eureka moment", "connecting the dots",
                "consulting my notes", "doing a little dance", "making the donuts", "engaging brain cell",
                "the hamsters are running", "assembling thoughts", "reading the fine print", "checking my privilege",
                "powering up the crystal ball", "downloading wisdom", "performing arcane rituals",
                "untangling spaghetti", "feeding the algorithm", "one moment, human"};
        var phrase = WhimsicalExtension.randomPhrase();
        assertTrue(Arrays.asList(phrases).contains(phrase), "unexpected phrase: " + phrase);
    }
}
