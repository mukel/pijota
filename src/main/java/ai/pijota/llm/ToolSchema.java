package ai.pijota.llm;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolSchema {
    private ToolSchema() {}

    public static Map<String, Object> toMap(JsonSchemaElement schema) {
        var out = new LinkedHashMap<String, Object>();
        switch (schema) {
            case JsonObjectSchema object -> {
                out.put("type", "object");
                if (object.description() != null) out.put("description", object.description());
                var properties = new LinkedHashMap<String, Object>();
                if (object.properties() != null) {
                    for (var entry : object.properties().entrySet()) {
                        properties.put(entry.getKey(), toMap(entry.getValue()));
                    }
                }
                out.put("properties", properties);
                if (object.required() != null && !object.required().isEmpty()) out.put("required", object.required());
                if (object.additionalProperties() != null) out.put("additionalProperties", object.additionalProperties());
            }
            case JsonArraySchema array -> {
                out.put("type", "array");
                if (array.description() != null) out.put("description", array.description());
                out.put("items", array.items() != null ? toMap(array.items()) : Map.of("type", "string"));
            }
            case JsonBooleanSchema bool -> {
                out.put("type", "boolean");
                if (bool.description() != null) out.put("description", bool.description());
            }
            case JsonIntegerSchema integer -> {
                out.put("type", "integer");
                if (integer.description() != null) out.put("description", integer.description());
            }
            case JsonNumberSchema number -> {
                out.put("type", "number");
                if (number.description() != null) out.put("description", number.description());
            }
            case JsonStringSchema string -> {
                out.put("type", "string");
                if (string.description() != null) out.put("description", string.description());
            }
            default -> out.put("type", "string");
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static JsonObjectSchema objectFromMap(Map<String, Object> schema) {
        var builder = JsonObjectSchema.builder().additionalProperties(false);
        Object description = schema.get("description");
        if (description instanceof String s) builder.description(s);
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
                if (description != null) builder.description(description);
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
                if (description != null) builder.description(description);
                yield builder.build();
            }
            case "number" -> {
                var builder = JsonNumberSchema.builder();
                if (description != null) builder.description(description);
                yield builder.build();
            }
            case "boolean" -> {
                var builder = JsonBooleanSchema.builder();
                if (description != null) builder.description(description);
                yield builder.build();
            }
            default -> {
                var builder = JsonStringSchema.builder();
                if (description != null) builder.description(description);
                yield builder.build();
            }
        };
    }
}
