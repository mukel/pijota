package ai.pijota.tools;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolManifestTest {

    @Test
    void snapshotWithNoToolsProducesHashAndJson() {
        var registry = new ToolRegistry();
        var snapshot = ToolManifest.snapshot(registry);
        assertNotNull(snapshot.json());
        assertTrue(snapshot.json().startsWith("["));
        assertTrue(snapshot.json().endsWith("]"));
        assertNotNull(snapshot.hash());
        assertEquals(64, snapshot.hash().length());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<String, Object> schemaMap(Map<String, ?> raw) {
        return (Map) raw;
    }

    @Test
    void snapshotWithSingleTool() {
        var registry = new ToolRegistry();
        registry.register(new TestTool("read", "Reads a file", schemaMap(Map.of("type", "object"))));
        var snapshot = ToolManifest.snapshot(registry);
        assertTrue(snapshot.json().contains("read"));
        assertTrue(snapshot.json().contains("Reads a file"));
    }

    @Test
    void snapshotIsDeterministic() {
        var registry = new ToolRegistry();
        registry.register(new TestTool("read", "Reads a file", schemaMap(Map.of("type", "object"))));
        var a = ToolManifest.snapshot(registry);
        var b = ToolManifest.snapshot(registry);
        assertEquals(a.json(), b.json());
        assertEquals(a.hash(), b.hash());
    }

    @Test
    void snapshotHashChangesWhenToolChanges() {
        var regA = new ToolRegistry();
        regA.register(new TestTool("read", "Reads a file", schemaMap(Map.of("type", "object"))));

        var regB = new ToolRegistry();
        regB.register(new TestTool("read", "Reads files", schemaMap(Map.of("type", "object"))));

        assertNotEquals(ToolManifest.snapshot(regA).hash(), ToolManifest.snapshot(regB).hash());
    }

    @Test
    void snapshotHashChangesWhenToolAddedOrRemoved() {
        var regA = new ToolRegistry();
        regA.register(new TestTool("read", "desc", schemaMap(Map.of())));

        var regB = new ToolRegistry();
        regB.register(new TestTool("read", "desc", schemaMap(Map.of())));
        regB.register(new TestTool("write", "desc", schemaMap(Map.of())));

        assertNotEquals(ToolManifest.snapshot(regA).hash(), ToolManifest.snapshot(regB).hash());
    }

    @Test
    void snapshotSortedByName() {
        var registry = new ToolRegistry();
        registry.register(new TestTool("write", "w", schemaMap(Map.of())));
        registry.register(new TestTool("read", "r", schemaMap(Map.of())));
        var snapshot = ToolManifest.snapshot(registry);
        int readPos = snapshot.json().indexOf("\"read\"");
        int writePos = snapshot.json().indexOf("\"write\"");
        assertTrue(readPos < writePos);
    }

    @Test
    void disabledToolsNotInSnapshot() {
        var registry = new ToolRegistry();
        registry.register(new TestTool("read", "desc", schemaMap(Map.of()), false));
        var snapshot = ToolManifest.snapshot(registry);
        assertFalse(snapshot.json().contains("read"));
    }

    @Test
    void canonicalSchemaSortsKeys() {
        var schema = Map.<String, Object>of("z", "last", "a", "first");
        var canonical = ToolManifest.canonicalSchema(schema);
        var keys = canonical.keySet().iterator();
        assertEquals("a", keys.next());
        assertEquals("z", keys.next());
    }

    @Test
    void canonicalSchemaHandlesNestedMaps() {
        var schema = Map.<String, Object>of("inner", Map.of("b", 2, "a", 1));
        @SuppressWarnings("unchecked")
        var inner = (Map<String, Object>) ToolManifest.canonicalSchema(schema).get("inner");
        var keys = inner.keySet().iterator();
        assertEquals("a", keys.next());
        assertEquals("b", keys.next());
        assertEquals(1, inner.get("a"));
        assertEquals(2, inner.get("b"));
    }

    @Test
    void canonicalSchemaHandlesLists() {
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) ToolManifest
                                        .canonicalSchema(Map.<String, Object>of("items", List.of("z", "a")));
        assertEquals(List.of("z", "a"), result.get("items"));
    }

    @Test
    void canonicalSchemaHandlesNestedListOfMaps() {
        var schema = Map.<String, Object>of("list", List.of(Map.of("b", 2, "a", 1)));
        @SuppressWarnings("unchecked")
        var list = (List<Map<String, Object>>) ToolManifest.canonicalSchema(schema).get("list");
        assertEquals(1, list.size());
        var keys = list.get(0).keySet().iterator();
        assertEquals("a", keys.next());
        assertEquals("b", keys.next());
    }

    @Test
    void duplicateSnapshotsHaveIdenticalHashes() {
        var reg1 = new ToolRegistry();
        reg1.register(new TestTool("t", "desc", schemaMap(Map.of())));

        var reg2 = new ToolRegistry();
        reg2.register(new TestTool("t", "desc", schemaMap(Map.of())));

        assertEquals(ToolManifest.snapshot(reg1).hash(), ToolManifest.snapshot(reg2).hash());
    }

    private static class TestTool implements Tool {
        private final String name;
        private final String description;
        private final Map<String, Object> schema;
        private final boolean defaultEnabled;

        TestTool(String name, String description, Map<String, Object> schema) {
            this(name, description, schema, true);
        }

        TestTool(String name, String description, Map<String, Object> schema, boolean defaultEnabled) {
            this.name = name;
            this.description = description;
            this.schema = schema;
            this.defaultEnabled = defaultEnabled;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public Map<String, Object> schema() {
            return schema;
        }

        @Override
        public boolean defaultEnabled() {
            return defaultEnabled;
        }

        @Override
        public java.util.concurrent.CompletableFuture<Tool.ToolResult> execute(String toolCallId, Tool.Args arguments,
                                        String sessionCwd) {
            return java.util.concurrent.CompletableFuture.completedFuture(Tool.ToolResult.success("ok"));
        }
    }
}
