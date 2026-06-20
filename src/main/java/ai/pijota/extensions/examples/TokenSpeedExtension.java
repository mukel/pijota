package ai.pijota.extensions.examples;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.extensions.ExtensionRuntime;
import ai.pijota.util.Util;

import java.util.List;

/**
 * Opt-in status line showing the prefill (prompt-processing) and decode (generation) tokens-per-second of the most
 * recent request, e.g. {@code ⚡ pp 2.5K · tg 48 tok/s}. Off by default — enable with {@code --token-speed}. The speed
 * is measured by the LLM client and read via {@link ExtensionRuntime#llm()}; this extension only renders it.
 */
public final class TokenSpeedExtension implements Extension {

    private ExtensionRuntime runtime;

    @Override
    public String name() {
        return "token-speed";
    }

    @Override
    public String description() {
        return "Opt-in prefill/decode tokens-per-second in the status bar. Enable with --token-speed.";
    }

    @Override
    public void init(ExtensionAPI api) {
        if (!api.runtime().getFlag("token-speed", false))
            return;
        this.runtime = api.runtime();
        api.registerStatusLines(this::statusLines);
    }

    private List<String> statusLines() {
        var s = runtime.llm().lastRequestSpeed();
        if (s == null)
            return List.of();
        return List.of("⚡ pp " + Util.fmtCount((long) s.prefillTokensPerSec()) + " · tg "
                                        + Util.fmtCount((long) s.decodeTokensPerSec()) + " tok/s");
    }
}
