package org.pijota.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

    @Test
    void builder_closedObjectWithRequiredAndTypes() {
        var schema = ToolSchema.object().str("path", "the path", true).integer("limit", "max lines")
                                        .number("timeout", "seconds").bool("force", "overwrite").build();

        assertEquals("object", schema.get("type"));
        assertEquals(false, schema.get("additionalProperties"));
        assertEquals(List.of("path"), schema.get("required"));
        @SuppressWarnings("unchecked")
        var props = (Map<String, Object>) schema.get("properties");
        assertEquals(Map.of("type", "string", "description", "the path"), props.get("path"));
        assertEquals(Map.of("type", "integer", "description", "max lines"), props.get("limit"));
        assertEquals(Map.of("type", "number", "description", "seconds"), props.get("timeout"));
        assertEquals(Map.of("type", "boolean", "description", "overwrite"), props.get("force"));
    }

    @Test
    void builder_requiredKeptInDeclarationOrderAndOmittedWhenEmpty() {
        assertEquals(List.of("a", "b"), ToolSchema.object().str("a", "x", true).str("b", "y", true).build()
                                        .get("required"));
        assertFalse(ToolSchema.object().str("a", "x").build().containsKey("required"));
    }

    @Test
    void builder_openObjectOmitsAdditionalProperties() {
        var open = ToolSchema.object().str("a", "x").build();
        assertTrue(open.containsKey("additionalProperties"));
        assertFalse(ToolSchema.object().str("a", "x").buildOpen().containsKey("additionalProperties"));
    }

    @Test
    void builder_nullDescriptionOmitsDescriptionKey() {
        @SuppressWarnings("unchecked")
        var props = (Map<String, Object>) ToolSchema.object().bool("flag", null).build().get("properties");
        assertEquals(Map.of("type", "boolean"), props.get("flag"));
    }

    @Test
    void builder_arrayDescriptionOptionalAndItemsPreserved() {
        var items = Map.<String, Object>of("type", "string");
        @SuppressWarnings("unchecked")
        var props = (Map<String, Object>) ToolSchema.object().array("withDesc", "a list", true, items)
                                        .array("noDesc", null, false, items).build().get("properties");
        assertEquals(Map.of("type", "array", "description", "a list", "items", items), props.get("withDesc"));
        assertEquals(Map.of("type", "array", "items", items), props.get("noDesc"));
    }

    @Test
    void builder_outputRoundTripsThroughObjectFromMap() {
        var obj = ToolSchema.objectFromMap(ToolSchema.object().str("path", "the path", true).build());
        assertEquals(List.of("path"), obj.required());
        assertFalse(obj.additionalProperties());
    }

    @Test
    @SuppressWarnings("unchecked")
    void builder_enumStrConstrainsToTheAllowedValues() {
        var schema = ToolSchema.object().enumStr("mode", "the mode", true, List.of("read", "write")).build();
        var mode = (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get("mode");

        assertEquals("string", mode.get("type"));
        assertEquals(List.of("read", "write"), mode.get("enum"));
        assertEquals(List.of("mode"), schema.get("required"));
    }
}
