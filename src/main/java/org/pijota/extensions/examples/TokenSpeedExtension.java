package org.pijota.extensions.examples;

import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.ExtensionRuntime;

import java.util.List;
import java.util.Locale;

/**
 * Opt-in status line showing the prefill (prompt-processing) and decode (generation) tokens-per-second of the most
 * recent request, e.g. {@code ⚡ pp 2.5K · tg 48 tok/s}. Off by default. Enable with {@code --token-speed}. The speed is
 * measured by the LLM client and read via {@link ExtensionRuntime#llm()}; this extension only renders it.
 */
public final class TokenSpeedExtension implements Extension {

    private ExtensionRuntime runtime;

    @Override
    public String name() {
        return "token-speed";
    }

    @Override
    public boolean enabledByDefault() {
        return false; // opt-in: --token-speed
    }

    @Override
    public String description() {
        return "Opt-in prefill/decode tokens-per-second in the status bar. Enable with --token-speed.";
    }

    @Override
    public void init(ExtensionAPI api) {
        this.runtime = api.runtime();
        api.registerStatusLines(this::statusLines);
    }

    private List<String> statusLines() {
        var s = runtime.llm().lastRequestSpeed();
        if (s == null)
            return List.of();
        // show the real figures (not fmtCount's "2.5K" rounding): prefill is large so an integer is enough, decode is
        // small so keep one decimal. Both are wall-clock end-to-end (prefill includes network latency to first token).
        return List.of(String.format(Locale.ROOT, "⚡ pp %d · tg %.1f tok/s", Math.round(s.prefillTokensPerSec()),
                                        s.decodeTokensPerSec()));
    }
}
