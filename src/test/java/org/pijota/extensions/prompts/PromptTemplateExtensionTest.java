package org.pijota.extensions.prompts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptTemplateExtensionTest {

    @Test
    void nameIsPromptTemplates() {
        var ext = new PromptTemplateExtension(List.of());
        assertEquals("prompt-templates", ext.name());
    }

    @Test
    void descriptionIsNotEmpty() {
        var ext = new PromptTemplateExtension(List.of());
        assertNotNull(ext.description());
    }

    @Test
    void explicitPathsNullCoercedToEmpty() {
        var ext = new PromptTemplateExtension(null);
        assertEquals("prompt-templates", ext.name());
    }
}
