package org.pijota.llm;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolSchema {
    private ToolSchema() {}

    /** Starts a fluent JSON-schema object builder (replaces hand-built nested {@code Map.of(...)} schemas). */
    public static Builder object() {
        return new Builder();
    }

    /**
     * Fluent builder for JSON-schema {@code object} fragments used by tool {@code schema()} methods. Emits the same
     * {@code Map<String, Object>} shape the tools used to hand-build, so {@code ToolManifest}/{@code objectFromMap}
     * keep working unchanged.
     */
    public static final class Builder {
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        private Builder() {}

        public Builder str(String name, String description) {
            return add(name, false, scalar("string", description));
        }

        public Builder str(String name, String description, boolean required) {
            return add(name, required, scalar("string", description));
        }

        public Builder integer(String name, String description) {
            return add(name, false, scalar("integer", description));
        }

        public Builder integer(String name, String description, boolean required) {
            return add(name, required, scalar("integer", description));
        }

        public Builder number(String name, String description) {
            return add(name, false, scalar("number", description));
        }

        public Builder bool(String name, String description) {
            return add(name, false, scalar("boolean", description));
        }

        public Builder bool(String name, String description, boolean required) {
            return add(name, required, scalar("boolean", description));
        }

        /** A string property constrained to a fixed set of values ({@code enum}). */
        public Builder enumStr(String name, String description, boolean required, List<String> values) {
            return add(name, required, Map.of("type", "string", "description", description, "enum",
                                            List.copyOf(values)));
        }

        /**
         * Adds an array property. A null {@code description} omits the description key (some nested arrays lack one).
         */
        public Builder array(String name, String description, boolean required, Map<String, Object> items) {
            var m = new LinkedHashMap<String, Object>();
            m.put("type", "array");
            if (description != null)
                m.put("description", description);
            m.put("items", items);
            return add(name, required, m);
        }

        /**
         * Adds an open object property (no {@code additionalProperties} constraint) with string values. Suitable for
         * free-form maps like environment variables.
         */
        public Builder openObject(String name, String description, boolean required) {
            var m = new LinkedHashMap<String, Object>();
            m.put("type", "object");
            if (description != null)
                m.put("description", description);
            m.put("additionalProperties", Map.of("type", "string"));
            return add(name, required, m);
        }

        private Builder add(String name, boolean required, Map<String, Object> schema) {
            properties.put(name, schema);
            if (required)
                this.required.add(name);
            return this;
        }

        /** Builds a closed object schema (with {@code additionalProperties: false}), the standard top-level form. */
        public Map<String, Object> build() {
            return build(true);
        }

        /** Builds an open object schema (no {@code additionalProperties} key), used for some nested array items. */
        public Map<String, Object> buildOpen() {
            return build(false);
        }

        private Map<String, Object> build(boolean closed) {
            var m = new LinkedHashMap<String, Object>();
            m.put("type", "object");
            m.put("properties", new LinkedHashMap<>(properties));
            if (!required.isEmpty())
                m.put("required", List.copyOf(required));
            if (closed)
                m.put("additionalProperties", false);
            return m;
        }

        private static Map<String, Object> scalar(String type, String description) {
            return description == null ? Map.of("type", type) : Map.of("type", type, "description", description);
        }
    }

    @SuppressWarnings("unchecked")
    public static JsonObjectSchema objectFromMap(Map<String, Object> schema) {
        var builder = JsonObjectSchema.builder().additionalProperties(false);
        Object description = schema.get("description");
        if (description instanceof String s)
            builder.description(s);
        Object required = schema.get("required");
        if (required instanceof List<?> list) {
            builder.required(list.stream().filter(String.class::isInstance).map(String.class::cast).toList());
        }
        Object properties = schema.get("properties");
        if (properties instanceof Map<?, ?> props) {
            for (var entry : props.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() instanceof Map<?, ?> value) {
                    builder.addProperty(key, elementFromMap((Map<String, Object>) value));
                }
            }
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    public static JsonSchemaElement elementFromMap(Map<String, Object> schema) {
        String type = schema.get("type") instanceof String s ? s : "string";
        String description = schema.get("description") instanceof String s ? s : null;
        return switch (type) {
            case "object" -> objectFromMap(schema);
            case "array" -> {
                var builder = JsonArraySchema.builder();
                if (description != null)
                    builder.description(description);
                Object items = schema.get("items");
                if (items instanceof Map<?, ?> itemSchema) {
                    builder.items(elementFromMap((Map<String, Object>) itemSchema));
                } else {
                    builder.items(JsonStringSchema.builder().build());
                }
                yield builder.build();
            }
            case "integer" -> {
                var builder = JsonIntegerSchema.builder();
                if (description != null)
                    builder.description(description);
                yield builder.build();
            }
            case "number" -> {
                var builder = JsonNumberSchema.builder();
                if (description != null)
                    builder.description(description);
                yield builder.build();
            }
            case "boolean" -> {
                var builder = JsonBooleanSchema.builder();
                if (description != null)
                    builder.description(description);
                yield builder.build();
            }
            default -> {
                var builder = JsonStringSchema.builder();
                if (description != null)
                    builder.description(description);
                yield builder.build();
            }
        };
    }
}
