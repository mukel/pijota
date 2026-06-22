package ai.pijota.extensions.transcend;

import ai.pijota.agent.SystemPrompt;
import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.extensions.ExtensionKeybinding;
import ai.pijota.extensions.KeyChord;
import ai.pijota.modes.SlashCommand;
import ai.pijota.tools.TranscendenceTool;
import ai.pijota.util.Platform;

import java.util.List;
import java.util.Optional;

/**
 * Pijota's self-modification feature as an optional extension: registers the transcendence tool and its system-prompt
 * guidance. It rebuilds via Maven and respawns a jar, which only works on the JVM — so on a native image it declares
 * itself unavailable and the registry skips it (tool and guidance absent), rather than offering a tool that can't run.
 */
public final class TranscendenceExtension implements Extension {

    @Override
    public String name() {
        return "transcendence";
    }

    @Override
    public String description() {
        return "Self-modification: edit, build, test, and respawn pijota's own source.";
    }

    @Override
    public Optional<String> unavailableReason() {
        return Platform.isNativeImage()
                                        ? Optional.of("requires the JVM (rebuilds via Maven and respawns the jar); not on a native image")
                                        : Optional.empty();
    }

    @Override
    public void init(ExtensionAPI api) {
        var orchestrator = new TranscendenceOrchestrator(api);
        api.registerTool(new TranscendenceTool());
        api.registerSystemPrompt(() -> SystemPrompt.TRANSCENDENCE_SECTION);
        api.registerSlashCommand(new SlashCommand("transcend",
                                        "Rebuild and restart pijota, or set a self-modification goal", List.of(),
                                        orchestrator::handle));
        api.registerKeybinding(new ExtensionKeybinding(
                                        new KeyChord(KeyChord.Key.G, KeyChord.Modifier.CTRL, KeyChord.Modifier.ALT),
                                        "Transcend (rebuild & restart)", () -> orchestrator.handle("")));
    }
}
