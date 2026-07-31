package org.pijota.llm;

import org.pijota.messages.Message;
import org.pijota.util.Util;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Internal bridge from pijota {@link org.pijota.messages.Message}s to langchain4j message types. Package-private so the
 * langchain4j dependency stays an implementation detail of the LLM boundary and never leaks into the public API, only
 * {@link LlmService} (same package) calls it.
 */
final class MessageConverter {
    private MessageConverter() {}

    public static List<ChatMessage> toLangChain4j(List<Message> messages) {
        return toLangChain4j(messages, null);
    }

    /**
     * @param allowed
     *            the modalities the active model accepts; attachments of any other modality are dropped and called out
     *            in a note so the conversation still makes sense. {@code null} means allow every modality (e.g. an
     *            unknown model, let the provider decide).
     */
    public static List<ChatMessage> toLangChain4j(List<Message> messages, Set<Modality> allowed) {
        var chatMessages = new ArrayList<ChatMessage>();
        Set<String> pendingToolResultIds = new LinkedHashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            switch (msg) {
                case Message.System s -> {
                    pendingToolResultIds = new LinkedHashSet<>();
                    chatMessages.add(new SystemMessage(s.content()));
                }
                case Message.Custom ignored -> {}
                case Message.User u -> {
                    pendingToolResultIds = new LinkedHashSet<>();
                    chatMessages.add(userMessage(u, allowed));
                }
                case Message.Assistant a -> {
                    if (a.toolCalls() != null && !a.toolCalls().isEmpty()) {
                        if (hasCompleteImmediateToolResults(messages, i)) {
                            var tcList = a.toolCalls().stream().map(tc -> ToolExecutionRequest.builder().id(tc.id())
                                                            .name(tc.name()).arguments(tc.arguments()).build())
                                                            .toList();
                            String text = a.content() != null && !a.content().isBlank() ? a.content() : null;
                            chatMessages.add(AiMessage.builder().text(text).thinking(nonBlank(a.thinking()))
                                                            .toolExecutionRequests(tcList).build());
                            pendingToolResultIds = a.toolCalls().stream().map(tc -> tc.id())
                                                            .collect(Collectors.toCollection(LinkedHashSet::new));
                        } else {
                            pendingToolResultIds = new LinkedHashSet<>();
                            if (hasText(a.content()) || hasText(a.thinking())) {
                                chatMessages.add(assistantMessage(a.content() != null ? a.content() : "",
                                                                a.thinking()));
                            }
                        }
                    } else {
                        pendingToolResultIds = new LinkedHashSet<>();
                        chatMessages.add(assistantMessage(a.content() != null ? a.content() : "", a.thinking()));
                    }
                }
                case Message.ToolResult tr -> {
                    if (pendingToolResultIds.remove(tr.toolCallId())) {
                        chatMessages.add(ToolExecutionResultMessage.builder().id(tr.toolCallId())
                                                        .toolName(tr.toolName()).text(tr.content())
                                                        .isError(tr.isError() ? true : null).build());
                    }
                }
                case Message.CompactionSummary cs -> {
                    pendingToolResultIds = new LinkedHashSet<>();
                    chatMessages.add(new UserMessage("[Previous conversation summary]\n" + cs.summary()));
                }
                case Message.BranchSummary bs -> {
                    pendingToolResultIds = new LinkedHashSet<>();
                    chatMessages.add(new UserMessage("[Branch context]\n" + bs.summary()));
                }
            }
        }
        return chatMessages;
    }

    // A user message becomes a multi-part message (text + one ImageContent per attachment) when it carries images and
    // the model supports vision; langchain4j maps ImageContent to each provider's native shape (Anthropic base64
    // source,
    // OpenAI image_url, Gemini inlineData). With no usable images it stays a plain text message.
    private static ChatMessage userMessage(Message.User u, Set<Modality> allowed) {
        // only binary media reaches here as attachments; text-like files are extracted & inlined upstream
        var media = u.attachments().stream()
                                        .filter(a -> a.mime() != null && Modality.fromMime(a.mime()) != Modality.TEXT)
                                        .toList();
        if (media.isEmpty())
            return new UserMessage(u.content());
        var kept = new ArrayList<Content>();
        var dropped = new LinkedHashMap<Modality, Integer>(); // modalities the model can't read, counted for the note
        for (var a : media) {
            Modality m = Modality.fromMime(a.mime());
            if (allowed == null || allowed.contains(m))
                kept.add(contentFor(m, a));
            else
                dropped.merge(m, 1, Integer::sum);
        }
        String text = u.content();
        if (!dropped.isEmpty()) {
            int total = dropped.values().stream().mapToInt(Integer::intValue).sum();
            var notes = dropped.entrySet().stream().map(
                                            e -> Util.plural(e.getValue(), e.getKey().name().toLowerCase(Locale.ROOT)))
                                            .toList();
            String note = "[" + String.join(" and ", notes) + " omitted, the current model can't read "
                                            + (total == 1 ? "it" : "them") + "]";
            text = hasText(text) ? text + "\n\n" + note : note;
        }
        var parts = new ArrayList<Content>();
        if (hasText(text))
            parts.add(TextContent.from(text));
        parts.addAll(kept);
        // a lone text part stays a plain UserMessage so singleText() keeps working downstream
        if (parts.size() == 1 && parts.get(0) instanceof TextContent tc)
            return new UserMessage(tc.text());
        return UserMessage.from(parts);
    }

    // langchain4j maps each typed content to the provider's native shape (Anthropic source/document, OpenAI image_url/
    // input_audio, Gemini inlineData). Text modality never reaches here; those files are inlined as text upstream.
    private static Content contentFor(Modality m, Message.Attachment a) {
        return switch (m) {
            case IMAGE -> ImageContent.from(a.base64(), a.mime());
            case PDF -> PdfFileContent.from(a.base64(), a.mime());
            case AUDIO -> AudioContent.from(a.base64(), a.mime());
            case VIDEO -> VideoContent.from(a.base64(), a.mime());
            case TEXT -> throw new IllegalStateException("text attachments are extracted & inlined upstream");
        };
    }

    private static AiMessage assistantMessage(String text, String thinking) {
        return AiMessage.builder().text(text).thinking(nonBlank(thinking)).build();
    }

    private static String nonBlank(String value) {
        return value != null && !value.isBlank() ? value : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean hasCompleteImmediateToolResults(List<Message> messages, int assistantIndex) {
        var assistant = (Message.Assistant) messages.get(assistantIndex);
        var ids = assistant.toolCalls().stream().map(tc -> tc.id())
                                        .collect(Collectors.toCollection(LinkedHashSet::new));
        for (int i = assistantIndex + 1; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof Message.ToolResult tr))
                break;
            ids.remove(tr.toolCallId());
            if (ids.isEmpty())
                return true;
        }
        return false;
    }
}
