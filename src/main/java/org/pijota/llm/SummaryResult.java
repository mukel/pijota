package org.pijota.llm;

import org.pijota.messages.Usage;

public record SummaryResult(String text, Usage usage) {
}
