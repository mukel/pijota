package ai.pijota.registers;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RegisterServiceTest {

    @Test
    void readsGetterOnlyRegister() {
        var service = new RegisterService(Map.of());
        service.register("goal", "extension.goal.version", () -> "1.0.0");

        var missing = new ArrayList<String>();

        assertEquals(Map.of("extension.goal.version", "1.0.0"),
                                        service.get(List.of("extension.goal.version"), missing));
        assertTrue(missing.isEmpty());
    }

    @Test
    void rejectsWritingGetterOnlyRegister() {
        var service = new RegisterService(Map.of());
        service.register("goal", "extension.goal.version", () -> "1.0.0");
        var rejected = new LinkedHashMap<String, String>();

        service.set(Map.of("extension.goal.version", "2.0.0"), List.of(), new ArrayList<>(), new ArrayList<>(),
                                        rejected);

        assertEquals(Map.of("extension.goal.version", "read-only"), rejected);
    }

    @Test
    void writesSetterRegisterAndPersistsExtensionValue() {
        var value = new AtomicReference<>("passive");
        var service = new RegisterService(Map.of());
        service.register("goal", "extension.goal.mode", value::get, value::set);
        var updated = new ArrayList<String>();

        service.set(Map.of("extension.goal.mode", "active"), List.of(), updated, new ArrayList<>(),
                                        new LinkedHashMap<>());

        assertEquals("active", value.get());
        assertEquals(List.of("extension.goal.mode"), updated);
        assertEquals(Map.of("extension.goal.mode", "active"), service.persistedSnapshot());
    }

    @Test
    void persistedValueOverridesGetterOnlyAfterDeclaration() {
        var service = new RegisterService(Map.of("extension.goal.mode", "active"));
        var missing = new ArrayList<String>();

        assertTrue(service.get(List.of("extension.goal.mode"), missing).isEmpty());
        assertEquals(List.of("extension.goal.mode"), missing);

        service.register("goal", "extension.goal.mode", () -> "passive", _ -> {});
        missing.clear();

        assertEquals(Map.of("extension.goal.mode", "active"), service.get(List.of("extension.goal.mode"), missing));
    }

    @Test
    void registerValueReadsPersistedValueBeforeDeclaration() {
        var service = new RegisterService(Map.of("extension.goal.mode", "active"));

        assertEquals("active", service.registerValue("goal", "extension.goal.mode").orElseThrow());
    }

    @Test
    void scratchCanBeCreatedReadListedAndDeleted() {
        var service = new RegisterService(Map.of());
        var updated = new ArrayList<String>();
        var deleted = new ArrayList<String>();

        service.set(Map.of("scratch.plan", "inspect"), List.of(), updated, deleted, new LinkedHashMap<>());

        assertEquals(List.of("scratch.plan"), updated);
        assertEquals(Map.of("scratch.plan", "inspect"), service.get(List.of("scratch.plan"), new ArrayList<>()));
        assertEquals(List.of("scratch.plan"), service.contains("scratch.*"));

        service.set(Map.of(), List.of("scratch.plan"), new ArrayList<>(), deleted, new LinkedHashMap<>());

        assertEquals(List.of("scratch.plan"), deleted);
        assertTrue(service.contains("scratch.*").isEmpty());
    }

    @Test
    void onlyScratchCanBeDeleted() {
        var service = new RegisterService(Map.of());
        var rejected = new LinkedHashMap<String, String>();

        service.set(Map.of(), List.of("extension.goal.mode"), new ArrayList<>(), new ArrayList<>(), rejected);

        assertEquals(Map.of("extension.goal.mode", "delete only supported for scratch"), rejected);
    }

    @Test
    void containsUsesStarWildcard() {
        var service = new RegisterService(Map.of());
        service.register("goal", "extension.goal.mode", () -> "active", _ -> {});
        service.register("todo", "extension.todo.mode", () -> "active", _ -> {});
        service.register("goal", "extension.goal.current", () -> "task");

        assertEquals(List.of("extension.goal.current", "extension.goal.mode"), service.contains("extension.goal.*"));
        assertEquals(List.of("extension.goal.mode", "extension.todo.mode"), service.contains("extension.*.mode"));
    }

    @Test
    void enforcesExtensionNamespace() {
        var service = new RegisterService(Map.of());

        assertThrows(IllegalArgumentException.class,
                                        () -> service.register("goal", "extension.todo.mode", () -> "active"));
        assertThrows(IllegalArgumentException.class, () -> service.register("goal", "scratch.plan", () -> "x"));
        assertThrows(IllegalArgumentException.class, () -> service.registerValue("goal", "extension.todo.mode"));
    }
}
