package ai.pijota.llm;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolSchemaTest {

    @Test
    void objectFromMap_stringProperty() {
        var schema = Map.of("type", "object", "properties",
                                        Map.of("name", Map.of("type", "string", "description", "the name")));

        var obj = ToolSchema.objectFromMap(schema);
        assertNotNull(obj);
        assertTrue(obj.required().isEmpty());
    }

    @Test
    void objectFromMap_withRequired() {
        var schema = Map.of("type", "object", "required", List.of("name"), "properties",
                                        Map.of("name", Map.of("type", "string")));

        var obj = ToolSchema.objectFromMap(schema);
        assertEquals(List.of("name"), obj.required());
    }

    @Test
    void objectFromMap_withAdditionalProperties() {
        var schema = Map.of("type", "object", "properties", Map.of());

        var obj = ToolSchema.objectFromMap(schema);
        assertFalse(obj.additionalProperties());
    }

    @Test
    void elementFromMap_string() {
        var elem = ToolSchema.elementFromMap(Map.of("type", "string", "description", "text field"));
        assertNotNull(elem);
    }

    @Test
    void elementFromMap_integer() {
        var elem = ToolSchema.elementFromMap(Map.of("type", "integer"));
        assertNotNull(elem);
    }

    @Test
    void elementFromMap_number() {
        var elem = ToolSchema.elementFromMap(Map.of("type", "number"));
        assertNotNull(elem);
    }

    @Test
    void elementFromMap_boolean() {
        var elem = ToolSchema.elementFromMap(Map.of("type", "boolean"));
        assertNotNull(elem);
    }

    @Test
    void elementFromMap_defaultToStringWhenTypeMissing() {
        var elem = ToolSchema.elementFromMap(Map.of());
        assertNotNull(elem);
    }

    @Test
    void elementFromMap_unknownTypeDefaultsToString() {
        var elem = ToolSchema.elementFromMap(Map.of("type", "unknown"));
        assertNotNull(elem);
    }

    @Test
    void elementFromMap_array_withItems() {
        var schema = Map.of("type", "array", "items", Map.of("type", "string"));

        var elem = ToolSchema.elementFromMap(schema);
        assertNotNull(elem);
    }

    @Test
    void elementFromMap_array_withoutItemsDefaultsToStringItems() {
        var schema = Map.<String, Object>of("type", "array");
        var elem = ToolSchema.elementFromMap(schema);
        assertNotNull(elem);
    }

    @Test
    void elementFromMap_nestedObject() {
        var schema = Map.of("type", "object", "properties", Map.of("inner", Map.of("type", "object", "properties",
                                        Map.of("val", Map.of("type", "integer")))));

        var obj = ToolSchema.objectFromMap(schema);
        assertNotNull(obj);
    }

    @Test
    void elementFromMap_stringWithoutDescription() {
        var elem = ToolSchema.elementFromMap(Map.of("type", "string"));
        assertNotNull(elem);
    }

    @Test
    void elementFromMap_integerWithoutDescription() {
        var elem = ToolSchema.elementFromMap(Map.of("type", "integer"));
        assertNotNull(elem);
    }

    @Test
    void elementFromMap_booleanWithoutDescription() {
        var elem = ToolSchema.elementFromMap(Map.of("type", "boolean"));
        assertNotNull(elem);
    }
}
