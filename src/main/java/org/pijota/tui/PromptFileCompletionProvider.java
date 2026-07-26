package org.pijota.tui;

import java.util.List;

@FunctionalInterface
interface PromptFileCompletionProvider {
    List<CompletionItem> complete(String text);
}
