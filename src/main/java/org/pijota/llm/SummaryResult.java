package org.pijota.llm;

import org.pijota.messages.Usage;

/** A compaction summary and what generating it cost, so the tokens spent summarising are not invisible. */
public record SummaryResult(String text, Usage usage) {
}
