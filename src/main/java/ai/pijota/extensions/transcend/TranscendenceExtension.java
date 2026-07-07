package ai.pijota.extensions.transcend;

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
    private static final String SYSTEM_PROMPT = """
                                    TRANSCENDENCE:
                                    You are capable of self-modification. When the user sets a goal (including via
                                    /transcend "goal"), implement it by editing source files, using the transcendence
                                    tool to build and test, and finally respawning to restart with the new code.
                                    Work autonomously — the user sets the goal and you handle everything: edit source,
                                    run tests, and respawn without needing step-by-step guidance. After respawn, the
                                    session resumes where it left off with the new code running.

                                    """;

    @Override
    public String name() {
        return "transcendence";
    }

    @Override
    public boolean enabledByDefault() {
        return false; // opt-in: --transcendence (self-modification is the riskiest capability)
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
        api.registerSystemPrompt(() -> SYSTEM_PROMPT);
        api.registerSlashCommand(new SlashCommand("transcend",
                                        "Rebuild and restart pijota, or set a self-modification goal", List.of(),
                                        orchestrator::handle));
        api.registerKeybinding(new ExtensionKeybinding(
                                        new KeyChord(KeyChord.Key.G, KeyChord.Modifier.CTRL, KeyChord.Modifier.ALT),
                                        "Transcend (rebuild & restart)", () -> orchestrator.handle("")));
    }
}
