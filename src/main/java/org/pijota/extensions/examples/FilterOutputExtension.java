package org.pijota.extensions.examples;

import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.ExtensionEvent;
import org.pijota.extensions.ExtensionResult;
import org.pijota.extensions.ExtensionRuntime;
import org.pijota.messages.Message;
import org.pijota.modes.SlashCommand;
import org.pijota.util.Emojis;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-redacts secrets and tokens from user input and from every message in the provider request, so they never leave
 * for the model/provider. Detects AWS keys, GitHub tokens, Stripe keys, JWTs, Bearer/Basic auth headers, and
 * environment variable assignments containing secrets. The local TUI still shows the raw tool output; it's the user's
 * own machine; only what is sent to the provider is redacted. On by default; toggle with {@code /filter-output}.
 */
public final class FilterOutputExtension implements Extension {

    private static final Pattern[] SECRETS = {Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{8,}\\b"), Pattern.compile("\\bgh[pour]_[A-Za-z0-9]{36}\\b"),
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{40,}\\b"), Pattern.compile("\\bAIza[0-9A-Za-z\\-_]{35}\\b"),
            Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b"),
            Pattern.compile("\\b(sk|pk)_(test|live)_[A-Za-z0-9]{24,}\\b"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b"),
            Pattern.compile("\\b(Bearer|Basic)\\s+[A-Za-z0-9+/=_-]{20,}\\b"),};

    private static final Pattern SECRET_VALUE = Pattern.compile(
                                    "(?i)(^|\\s)([A-Z][A-Z0-9_]*=)?([A-Za-z0-9+/=_-]{40,})(\\s|$)", Pattern.MULTILINE);
    private static final Pattern ENV_SECRET_LINE = Pattern.compile(
                                    "(?i)^\\s*(?:export\\s+)?(\\w*(?:KEY|SECRET|TOKEN|PASSWORD|AUTH)\\w*)\\s*=\\s*(.+)$",
                                    Pattern.MULTILINE);
    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile(
                                    "-----BEGIN\\s.*PRIVATE\\sKEY-----[\\s\\S]*?-----END\\s.*PRIVATE\\sKEY-----",
                                    Pattern.MULTILINE);

    private volatile boolean enabled = true;

    @Override
    public String name() {
        return "filter-output";
    }

    @Override
    public String description() {
        return "Auto-redacts secrets and tokens before sending to AI";
    }

    @Override
    public void init(ExtensionAPI api) {
        api.registerSlashCommand(new SlashCommand("filter-output", "Toggle secret/token redaction", List.of(),
                                        ignored -> toggle(api)));
        // A live supplier keeps the chip in sync with the toggle everywhere, including after /new (which clears keyed
        // setStatus entries but not this extension's state). Redaction is on by default, so only its absence is worth
        // a chip: a permanent badge for the safe state is noise, and the eye stops seeing it long before it matters.
        api.registerStatusLines(() -> enabled ? List.of() : List.of(Emojis.EYES + " visible"));

        api.onInput(text -> {
            if (!enabled)
                return new ExtensionResult.InputResult(text, false);
            String redacted = redactString(text);
            return new ExtensionResult.InputResult(redacted != null ? redacted : text, false);
        });

        api.on(ExtensionEvent.BeforeProviderRequest.class, event -> {
            if (!enabled)
                return;
            List<Message> messages = event.messages();
            for (int i = 0; i < messages.size(); i++) {
                Message msg = messages.get(i);
                String content = contentOf(msg);
                if (content == null || content.contains("[REDACTED]"))
                    continue;
                String redacted = redactString(content);
                if (redacted != null)
                    messages.set(i, withContent(msg, redacted));
            }
        });
    }

    @Override
    public void onReady(ExtensionRuntime rt) {
        if (!rt.getFlag("filter-secrets", true))
            enabled = false;
    }

    /**
     * Says what it did. Turning redaction back on removes the only chip that was on screen, so a toggle that reported
     * nothing looked like a command that had failed: the one visible effect of succeeding was something disappearing.
     */
    private void toggle(ExtensionAPI api) {
        enabled = !enabled;
        api.runtime().setStatus(enabled
                                        ? "redacting secrets and tokens before they are sent"
                                        : "not redacting: secrets in output go to the model as they are");
    }

    private static String contentOf(Message msg) {
        return switch (msg) {
            case Message.ToolResult r -> r.content();
            case Message.User u -> u.content();
            case Message.Assistant a -> a.content();
            default -> null;
        };
    }

    static String redactString(String text) {
        if (text == null || text.isBlank())
            return null;
        String result = text;
        boolean changed = false;

        result = PRIVATE_KEY_BLOCK.matcher(result).replaceAll("[PRIVATE KEY REDACTED]");
        if (!result.equals(text))
            changed = true;

        for (Pattern p : SECRETS) {
            var m = p.matcher(result);
            if (m.find()) {
                result = m.replaceAll("[REDACTED]");
                changed = true;
            }
        }

        var envMatcher = ENV_SECRET_LINE.matcher(result);
        if (envMatcher.find()) {
            StringBuilder sb = new StringBuilder();
            do {
                envMatcher.appendReplacement(sb, Matcher.quoteReplacement(envMatcher.group(1) + "=[REDACTED]"));
            } while (envMatcher.find());
            envMatcher.appendTail(sb);
            result = sb.toString();
            changed = true;
        }

        var secretValMatcher = SECRET_VALUE.matcher(result);
        if (secretValMatcher.find()) {
            StringBuilder sb = new StringBuilder();
            do {
                String prefix = secretValMatcher.group(1) != null ? secretValMatcher.group(1) : "";
                String key = secretValMatcher.group(2) != null ? secretValMatcher.group(2) : "";
                String suffix = secretValMatcher.group(4) != null ? secretValMatcher.group(4) : "";
                secretValMatcher.appendReplacement(sb, Matcher.quoteReplacement(prefix + key + "[REDACTED]" + suffix));
            } while (secretValMatcher.find());
            secretValMatcher.appendTail(sb);
            result = sb.toString();
            changed = true;
        }

        return changed ? result : null;
    }

    private static Message withContent(Message msg, String newContent) {
        return switch (msg) {
            case Message.ToolResult r -> new Message.ToolResult(r.id(), r.timestamp(), r.toolCallId(), r.toolName(),
                                            newContent, r.isError(), r.details());
            case Message.User u -> new Message.User(u.id(), u.timestamp(), newContent, u.attachments());
            case Message.Assistant a -> new Message.Assistant(a.id(), a.timestamp(), newContent, a.toolCalls(),
                                            a.thinking(), a.sourceProvider(), a.sourceModel());
            default -> msg;
        };
    }
}
