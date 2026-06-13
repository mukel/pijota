package ai.pijota.extensions.examples;

import ai.pijota.extensions.ExtensionEvent;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.modes.SlashCommand;
import ai.pijota.util.Emojis;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class WhimsicalExtension implements Extension {

    private static final String[] PHRASES = {"pondering the orb", "consulting the spirits", "doing mental gymnastics",
            "summoning the answer", "staring into the abyss", "asking the magic conch", "rearranging neurons",
            "whispering to the machines", "searching the archives", "having a eureka moment", "connecting the dots",
            "consulting my notes", "doing a little dance", "making the donuts", "engaging brain cell",
            "the hamsters are running", "assembling thoughts", "reading the fine print", "checking my privilege",
            "powering up the crystal ball", "downloading wisdom", "performing arcane rituals", "untangling spaghetti",
            "feeding the algorithm", "one moment, human",};

    private static final long ROTATE_INTERVAL_MS = 2000;

    private volatile boolean enabled = true;
    private volatile boolean visible;
    private volatile String currentPhrase = randomPhrase();
    private volatile long lastRotatedMs;

    @Override
    public String name() {
        return "whimsical";
    }

    @Override
    public String description() {
        return "Replaces boring status text with whimsical random phrases";
    }

    @Override
    public void init(ExtensionAPI api) {
        api.registerSlashCommand(new SlashCommand("whimsical", "Toggle whimsical status phrases", List.of(), _ -> {
            enabled = !enabled;
            if (enabled)
                currentPhrase = randomPhrase();
        }));

        api.registerStatusLines(() -> {
            if (!enabled || !visible)
                return List.of();
            maybeRotate();
            return List.of(displayPhrase());
        });

        api.registerThinkingHeaderText(() -> enabled && visible ? displayPhrase() + Emojis.ELLIPSIS : null);

        api.on(ExtensionEvent.TurnStart.class, (_, _) -> {
            if (enabled) {
                currentPhrase = randomPhrase();
                lastRotatedMs = System.currentTimeMillis();
                visible = true;
            }
        });

        api.on(ExtensionEvent.TurnEnd.class, (_, _) -> visible = false);
    }

    private void maybeRotate() {
        long now = System.currentTimeMillis();
        if (now - lastRotatedMs >= ROTATE_INTERVAL_MS) {
            currentPhrase = randomPhrase();
            lastRotatedMs = now;
        }
    }

    private String displayPhrase() {
        return Emojis.SPARKLES + " " + currentPhrase;
    }

    static String randomPhrase() {
        return PHRASES[ThreadLocalRandom.current().nextInt(PHRASES.length)];
    }
}
