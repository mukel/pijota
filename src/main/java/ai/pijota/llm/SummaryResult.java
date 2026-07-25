package ai.pijota.llm;

import ai.pijota.messages.Usage;

public record SummaryResult(String text, Usage usage) {
}
