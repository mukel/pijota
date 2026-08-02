package org.pijota.extensions.suggest;

import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.ExtensionEvent;
import org.pijota.extensions.ExtensionRuntime;
import org.pijota.extensions.UiContext;
import org.pijota.messages.Message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * After a turn, offers the message the user is likely to type next, shown as ghost text on the empty prompt and
 * accepted with Tab. Opt-in ({@code --suggest}).
 *
 * <p>
 * The request is the conversation <em>as it already stands</em> with one short instruction appended, rather than a
 * summarised or re-shaped version of it. That is not a stylistic choice: it keeps the provider's cached prefix intact.
 * Measured against deepseek on a 1.5K-token conversation, the appended form read 1536 tokens from cache and paid for
 * 116, while the re-shaped form paid full price for all of them -- and the gap widens as the conversation grows, since
 * only the appended instruction is ever new.
 */
public final class SuggestExtension implements Extension {

    /** Beyond this the user has moved on and a suggestion would arrive as an interruption. */
    private static final long TIMEOUT_SECONDS = 5;

    private static final String INSTRUCTION = """
                                    Reply with the single message the user is most likely to type next, and nothing else.

                                    Rules:
                                    - 2 to 12 words, in the user's own style, as an instruction to you
                                    - no questions, no explanations, no quotes, no trailing period
                                    - never answer as yourself ("I'll...", "Let me...", "Sure...")
                                    - never propose an idea the conversation does not already point to
                                    - reply with nothing at all if the next step is not obvious, or if it touches
                                      credentials, security, destructive actions, or private data
                                    """;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SuggestExtension.class);

    private ExtensionAPI api;

    @Override
    public String name() {
        return "suggest";
    }

    @Override
    public boolean enabledByDefault() {
        return false; // it spends tokens the user did not ask to spend
    }

    @Override
    public String description() {
        return "After a turn, suggest the next message as ghost text; Tab accepts it. Enable with --suggest.";
    }

    @Override
    public void init(ExtensionAPI api) {
        this.api = api;
    }

    /**
     * Registration happens here, not in {@link #init}: extensions are initialised during bootstrap and the frontend
     * hands itself to the runtime afterwards, so {@code ui()} is still null at init time and the capability could only
     * ever read false.
     *
     * <p>
     * Nothing is registered when the frontend has no draft to put a suggestion in. Declining here rather than rendering
     * nothing later is the point: the argument costs an inference call to produce.
     */
    @Override
    public void onReady(ExtensionRuntime runtime) {
        UiContext ui = runtime == null ? null : runtime.ui();
        if (api == null || ui == null || !ui.acceptsPromptDrafts()) {
            log.debug("suggest: no draft-capable frontend, staying inert (ui={})", ui);
            return;
        }
        log.debug("suggest: armed");
        api.on(ExtensionEvent.AgentEnd.class, ignored -> suggest(ui));
    }

    private void suggest(UiContext ui) {
        CompletableFuture.supplyAsync(() -> {
            var llm = api.runtime().llm();
            var messages = new ArrayList<Message>(api.runtime().messages());
            if (messages.isEmpty())
                return "";
            messages.add(new Message.User(UUID.randomUUID(), Instant.now(), INSTRUCTION, List.of()));
            // No tools: the reply is a sentence, and a tool call here would be meaningless.
            String reply = llm.streamMessages(messages, List.of(), (a, b) -> {}).join().content();
            log.debug("suggest: model replied {}", reply == null ? "null" : "\"" + reply.strip() + "\"");
            return reply;
        }).orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).thenAccept(
                                        reply -> SuggestionText.validate(reply).ifPresent(ui::offerSuggestion))
                                        // Silent to the user -- a suggestion is a nicety, never an error line -- but
                                        // not silent to the log, or a broken suggestion path is undiagnosable.
                                        .exceptionally(e -> {
                                            log.debug("suggestion failed", e);
                                            return null;
                                        });
    }
}
