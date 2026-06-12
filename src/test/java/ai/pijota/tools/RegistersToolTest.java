package ai.pijota.tools;

import ai.pijota.registers.RegisterService;
import ai.pijota.util.Util;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RegistersToolTest {

    @Test
    void containsToolFindsRegisters() throws Exception {
        var service = new RegisterService(Map.of());
        service.register("goal", "extension.goal.mode", () -> "active", _ -> {});
        var tool = new RegistersContainsTool(service);

        var result = tool.execute("id", Map.of("query", "extension.goal.*"), null).get();

        assertFalse(result.isError());
        assertEquals(List.of("extension.goal.mode"), parse(result.content()).get("matches"));
        assertEquals("Find registers.", tool.description());
    }

    @Test
    void getToolReadsRegistersAndMissingKeys() throws Exception {
        var service = new RegisterService(Map.of());
        service.register("goal", "extension.goal.mode", () -> "active", _ -> {});
        var tool = new RegistersGetTool(service);

        var result = tool.execute("id", Map.of("keys", List.of("extension.goal.mode", "missing.key")), null).get();

        assertFalse(result.isError());
        var parsed = parse(result.content());
        assertEquals(Map.of("extension.goal.mode", "active"), parsed.get("values"));
        assertEquals(List.of("missing.key"), parsed.get("missing"));
        assertEquals("Read registers.", tool.description());
    }

    @Test
    void setToolWritesAndDeletesScratch() throws Exception {
        var service = new RegisterService(Map.of());
        var tool = new RegistersSetTool(service);

        var write = tool.execute("id", Map.of("values", Map.of("scratch.plan", "inspect")), null).get();
        var delete = tool.execute("id", Map.of("delete", List.of("scratch.plan")), null).get();

        assertFalse(write.isError());
        assertEquals(List.of("scratch.plan"), parse(write.content()).get("updated"));
        assertFalse(delete.isError());
        assertEquals(List.of("scratch.plan"), parse(delete.content()).get("deleted"));
        assertEquals("Write registers.", tool.description());
    }

    @Test
    void setToolWritesDeclaredRegisterAndRejectsReadOnly() throws Exception {
        var mode = new AtomicReference<>("passive");
        var service = new RegisterService(Map.of());
        service.register("goal", "extension.goal.mode", mode::get, mode::set);
        service.register("goal", "extension.goal.version", () -> "1.0.0");
        var tool = new RegistersSetTool(service);

        var result = tool.execute("id", Map.of("values",
                                        Map.of("extension.goal.mode", "active", "extension.goal.version", "2")), null)
                                        .get();

        assertFalse(result.isError());
        var parsed = parse(result.content());
        assertEquals(List.of("extension.goal.mode"), parsed.get("updated"));
        assertEquals(Map.of("extension.goal.version", "read-only"), parsed.get("rejected"));
        assertEquals("active", mode.get());
    }

    @Test
    void setToolRejectsNonStringValues() throws Exception {
        var tool = new RegistersSetTool(new RegisterService(Map.of()));

        var result = tool.execute("id", Map.of("values", Map.of("scratch.count", 1)), null).get();

        assertTrue(result.isError());
        assertEquals("values must be string map", result.content());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) throws Exception {
        return Util.MAPPER.readValue(json, Map.class);
    }
}
