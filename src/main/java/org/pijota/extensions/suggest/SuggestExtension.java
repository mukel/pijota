package org.pijota.extensions.suggest;

import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.ExtensionEvent;
import org.pijota.extensions.ExtensionRuntime;
import org.pijota.extensions.UiContext;
import org.pijota.llm.ThinkingLevel;
import org.pijota.messages.Message;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 *
 * <p>
 * Reasoning is off for this request whatever the session is set to, because reasoning is the entire delay. Measured on
 * deepseek-v4-flash at MEDIUM, four suggestions spent 126, 206, 317 and 448 reasoning tokens to produce a five-word
 * sentence, and arrived 2.1 to 5.4s after the turn ended; prefill is not the cost (the cached prefix hit every time). A
 * provider that cannot express "do not think" ignores this and keeps thinking -- deepseek has no such parameter today,
 * so the delay stays there until it does, or until the suggestion is asked of a smaller model.
 */
public final class SuggestExtension implements Extension {

    /** Beyond this the user has moved on and a suggestion would arrive as an interruption. */
    private static final long TIMEOUT_SECONDS = 5;

    /** What to suggest. Replaceable by the user; see {@link #instruction}. */
    static final String GUIDANCE = """
                                    Reply with the single message the user is most likely to type next, and nothing else.

                                    - in the user's own style, as an instruction to you
                                    - never answer as yourself ("I'll...", "Let me...", "Sure...")
                                    - never propose an idea the conversation does not already point to
                                    - never repeat or rephrase something already asked; suggest the step after it
                                    - prefer the step the user must take, not one you already took for them
                                    """;

    /**
     * Always appended, including after a user's own guidance. These are not style preferences: {@link SuggestionText}
     * rejects a reply that breaks them, so a custom prompt without them would produce output that is silently thrown
     * away, which reads as the setting not working.
     */
    static final String FORMAT = """

                                    Format, always:
                                    - 2 to 12 words, one line, no quotes, no trailing period
                                    - no questions, no explanations
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
        return "After a turn, suggest the next message as ghost text; Tab accepts it. Enable with --suggest, and "
                                        + "customise the instruction with --suggest=<text|file> or the \"suggest\" "
                                        + "entry under \"extensions\" in settings.json.";
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

    /**
     * The instruction sent to the model. {@code --suggest=<text>} (or {@code "extensions": {"suggest": "<text>"}})
     * replaces the guidance; a value naming a readable file is read from it, so a long prompt can live in a file rather
     * than on a command line. The format rules are appended either way.
     */
    String instruction() {
        return customGuidance().orElse(GUIDANCE).strip() + FORMAT;
    }

    private Optional<String> customGuidance() {
        var flag = api == null ? Optional.<String>empty() : api.runtime().getFlag(name());
        // "true"/"" is the plain on-switch, not a prompt.
        return flag.map(String::strip).filter(v -> !v.isEmpty() && !v.equalsIgnoreCase("true"))
                                        .map(SuggestExtension::textOrFileContents);
    }

    /** A value that names a readable file is its contents; anything else is the text itself. */
    private static String textOrFileContents(String value) {
        try {
            Path path = Path.of(value.startsWith("~/") ? System.getProperty("user.home") + value.substring(1) : value);
            if (Files.isRegularFile(path))
                return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // Not a usable path (too long, illegal characters, unreadable): treat it as the prompt text.
        }
        return value;
    }

    private void suggest(UiContext ui) {
        CompletableFuture.supplyAsync(() -> {
            var llm = api.runtime().llm();
            var messages = new ArrayList<Message>(api.runtime().messages());
            if (messages.isEmpty())
                return "";
            messages.add(new Message.User(UUID.randomUUID(), Instant.now(), instruction(), List.of()));
            // No tools: the reply is a sentence, and a tool call here would be meaningless. Reasoning off, because it
            // is the whole delay -- see the class comment.
            var result = llm.streamMessages(messages, List.of(), (a, b) -> {}, ThinkingLevel.OFF).join();
            String reply = result.content();
            log.debug("suggest: model replied {} · {}", reply == null ? "null" : "\"" + reply.strip() + "\"",
                                            result.usage());
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
