package ai.pijota.llm;

import ai.pijota.messages.Message;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

public final class MessageConverter {
    private MessageConverter() {}

    public static List<ChatMessage> toLangChain4j(List<Message> messages) {
        var chatMessages = new ArrayList<ChatMessage>();
        for (Message msg : messages) {
            switch (msg) {
                case Message.System s -> chatMessages.add(new SystemMessage(s.content()));
                case Message.User u -> chatMessages.add(new UserMessage(u.content()));
                case Message.Assistant a -> {
                    if (a.toolCalls() != null && !a.toolCalls().isEmpty()) {
                        var tcList = a.toolCalls().stream()
                            .map(tc -> ToolExecutionRequest.builder()
                                .id(tc.id()).name(tc.name()).arguments(tc.arguments()).build())
                            .toList();
                        String text = a.content() != null && !a.content().isBlank() ? a.content() : null;
                        chatMessages.add(AiMessage.builder()
                            .text(text)
                            .toolExecutionRequests(tcList)
                            .build());
                    } else {
                        chatMessages.add(AiMessage.from(
                            a.content() != null ? a.content() : ""));
                    }
                }
                case Message.ToolResult tr -> chatMessages.add(ToolExecutionResultMessage.builder()
                    .id(tr.toolCallId())
                    .toolName(tr.toolName())
                    .text(tr.content())
                    .isError(tr.isError() ? true : null)
                    .build());
                case Message.CompactionSummary cs -> chatMessages.add(
                    new UserMessage("[Previous conversation summary]\n" + cs.summary()));
                case Message.BranchSummary bs -> chatMessages.add(
                    new UserMessage("[Branch context]\n" + bs.summary()));
            }
        }
        return chatMessages;
    }
}
