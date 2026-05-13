package ai.pijota.modes;

import java.util.List;
import java.util.function.Consumer;

public record SlashCommand(
    String name,
    String description,
    List<String> aliases,
    Consumer<String> handler
) {}
