package ai.pijota.llm;

import ai.pijota.messages.Message;
import ai.pijota.messages.ToolCall;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class MessageTransformer {
    private MessageTransformer() {}

    public static List<Message> transform(List<Message> messages, String targetProvider, String targetModel) {
        if (messages == null || messages.isEmpty())
            return List.of();
        var idMap = new LinkedHashMap<String, String>();
        var usedIds = new HashSet<String>();
        var adapted = new ArrayList<Message>(messages.size());

        for (var message : messages) {
            switch (message) {
                case Message.Assistant assistant ->
                    adapted.add(adaptAssistant(assistant, targetProvider, targetModel, idMap, usedIds));
                case Message.ToolResult result -> adapted.add(adaptToolResult(result, idMap));
                default -> adapted.add(message);
            }
        }

        return repairToolSequences(adapted);
    }

    private static Message.Assistant adaptAssistant(Message.Assistant assistant, String targetProvider,
                                    String targetModel, Map<String, String> idMap, Set<String> usedIds) {
        var calls = assistant.toolCalls();
        boolean sameModel = sameModel(assistant, targetProvider, targetModel);
        assistant = sameModel ? assistant : assistant.withThinking("");
        if (calls == null || calls.isEmpty() || sameModel) {
            return assistant;
        }

        var normalizedCalls = new ArrayList<ToolCall>(calls.size());
        boolean changed = false;
        for (var call : calls) {
            String oldId = call.id();
            String newId = ToolCallIdNormalizer.unique(oldId, targetProvider, usedIds);
            if (!Objects.equals(oldId, newId)) {
                idMap.put(oldId, newId);
                changed = true;
            }
            normalizedCalls.add(new ToolCall(newId, call.name(), call.arguments()));
        }

        if (!changed)
            return assistant;
        return new Message.Assistant(assistant.id(), assistant.timestamp(), assistant.content(),
                                        List.copyOf(normalizedCalls), assistant.thinking(), assistant.sourceProvider(),
                                        assistant.sourceModel());
    }

    private static boolean sameModel(Message.Assistant assistant, String targetProvider, String targetModel) {
        return assistant.sourceProvider() != null && assistant.sourceModel() != null
                                        && Objects.equals(assistant.sourceProvider(), targetProvider)
                                        && Objects.equals(assistant.sourceModel(), targetModel);
    }

    private static Message.ToolResult adaptToolResult(Message.ToolResult result, Map<String, String> idMap) {
        String mapped = idMap.get(result.toolCallId());
        if (mapped == null || Objects.equals(mapped, result.toolCallId()))
            return result;
        return new Message.ToolResult(result.id(), result.timestamp(), mapped, result.toolName(), result.content(),
                                        result.isError(), result.details());
    }

    private static List<Message> repairToolSequences(List<Message> messages) {
        var repaired = new ArrayList<Message>(messages.size());
        var pending = new LinkedHashMap<String, String>();
        var satisfied = new LinkedHashSet<String>();

        for (var message : messages) {
            if (message instanceof Message.ToolResult toolResult) {
                satisfied.add(toolResult.toolCallId());
                repaired.add(message);
                continue;
            }

            flushSyntheticResults(repaired, pending, satisfied);
            if (message instanceof Message.Assistant assistant) {
                var calls = assistant.toolCalls();
                if (calls != null) {
                    for (var call : calls)
                        pending.put(call.id(), call.name());
                }
            }
            repaired.add(message);
        }

        flushSyntheticResults(repaired, pending, satisfied);
        return List.copyOf(repaired);
    }

    private static void flushSyntheticResults(List<Message> result, Map<String, String> pending,
                                    Set<String> satisfied) {
        if (pending.isEmpty())
            return;
        for (var entry : pending.entrySet()) {
            if (!satisfied.contains(entry.getKey())) {
                result.add(new Message.ToolResult(entry.getKey(), entry.getValue(), "No result provided", true));
            }
        }
        pending.clear();
        satisfied.clear();
    }
}
