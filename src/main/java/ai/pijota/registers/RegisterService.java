package ai.pijota.registers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class RegisterService {

    private final Map<String, Supplier<String>> getters = new ConcurrentHashMap<>();
    private final Map<String, Consumer<String>> setters = new ConcurrentHashMap<>();
    private final Map<String, String> scratch = new ConcurrentHashMap<>();
    private final Map<String, String> persisted = new ConcurrentHashMap<>();
    private volatile Runnable persistHook;

    public RegisterService(Map<String, String> persisted) {
        replacePersisted(persisted);
    }

    public void setPersistHook(Runnable persistHook) {
        this.persistHook = persistHook;
    }

    public void replacePersisted(Map<String, String> values) {
        persisted.clear();
        if (values != null)
            persisted.putAll(values);
    }

    public Map<String, String> persistedSnapshot() {
        return new LinkedHashMap<>(persisted);
    }

    public void register(String ownerId, String key, Supplier<String> getter) {
        validateOwner(ownerId, key);
        if (getter == null)
            throw new IllegalArgumentException("getter is required");
        getters.put(key, getter);
        setters.remove(key);
    }

    public void register(String ownerId, String key, Supplier<String> getter, Consumer<String> setter) {
        validateOwner(ownerId, key);
        if (getter == null)
            throw new IllegalArgumentException("getter is required");
        if (setter == null)
            throw new IllegalArgumentException("setter is required");
        getters.put(key, getter);
        setters.put(key, setter);
    }

    public Optional<String> registerValue(String ownerId, String key) {
        validateOwner(ownerId, key);
        return Optional.ofNullable(persisted.get(key));
    }

    public List<String> contains(String query) {
        String pattern = query == null || query.isBlank() ? "*" : query;
        var keys = new LinkedHashSet<>(getters.keySet());
        keys.addAll(scratch.keySet());
        for (String key : persisted.keySet()) {
            if (getters.containsKey(key))
                keys.add(key);
        }
        keys.removeIf(key -> !matches(pattern, key));
        var sorted = new ArrayList<>(keys);
        Collections.sort(sorted);
        return sorted;
    }

    public Map<String, String> get(List<String> keys, List<String> missing) {
        var values = new LinkedHashMap<String, String>();
        if (keys == null)
            return values;
        for (String key : keys) {
            if (key == null)
                continue;
            if (scratch.containsKey(key)) {
                values.put(key, scratch.get(key));
            } else if (persisted.containsKey(key) && getters.containsKey(key)) {
                values.put(key, persisted.get(key));
            } else if (getters.containsKey(key)) {
                values.put(key, safeGet(key));
            } else if (missing != null) {
                missing.add(key);
            }
        }
        return values;
    }

    public void set(Map<String, String> values, List<String> delete, List<String> updated, List<String> deleted,
                                    Map<String, String> rejected) {
        boolean persistedChanged = false;
        if (delete != null) {
            for (String key : delete) {
                if (key == null)
                    continue;
                if (isScratch(key)) {
                    scratch.remove(key);
                    deleted.add(key);
                } else {
                    rejected.put(key, "delete only supported for scratch");
                }
            }
        }
        if (values != null) {
            for (var entry : values.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key == null || value == null) {
                    if (key != null)
                        rejected.put(key, "value must be string");
                    continue;
                }
                if (isScratch(key)) {
                    scratch.put(key, value);
                    updated.add(key);
                    continue;
                }
                Consumer<String> setter = setters.get(key);
                if (setter == null) {
                    rejected.put(key, getters.containsKey(key) ? "read-only" : "missing");
                    continue;
                }
                if (isPersisted(key)) {
                    persisted.put(key, value);
                    persistedChanged = true;
                }
                setter.accept(value);
                updated.add(key);
            }
        }
        if (persistedChanged && persistHook != null)
            persistHook.run();
    }

    private String safeGet(String key) {
        String value = getters.get(key).get();
        return value == null ? "" : value;
    }

    private static boolean matches(String query, String key) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        regex.append('$');
        return key.matches(regex.toString());
    }

    private static boolean isScratch(String key) {
        return key.startsWith("scratch.");
    }

    private static boolean isPersisted(String key) {
        return key.startsWith("extension.") || key.startsWith("session.");
    }

    private static void validateOwner(String ownerId, String key) {
        if (key == null || key.isBlank() || !key.contains("."))
            throw new IllegalArgumentException("invalid register key");
        if (key.startsWith("scratch."))
            throw new IllegalArgumentException("scratch registers cannot be declared");
        if (key.startsWith("extension.")) {
            String prefix = "extension." + ownerId + ".";
            if (ownerId == null || ownerId.isBlank() || !key.startsWith(prefix))
                throw new IllegalArgumentException("extension register namespace mismatch");
        }
    }
}
