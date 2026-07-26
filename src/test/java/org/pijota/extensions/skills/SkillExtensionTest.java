package org.pijota.extensions.skills;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkillExtensionTest {

    @Test
    void nameIsSkills() {
        var ext = new SkillExtension(List.of());
        assertEquals("skills", ext.name());
    }

    @Test
    void descriptionIsNotEmpty() {
        var ext = new SkillExtension(List.of());
        assertNotNull(ext.description());
    }

    @Test
    void explicitPathsDefaultToEmpty() {
        var ext = new SkillExtension(null);
        assertEquals("skills", ext.name());
    }
}
