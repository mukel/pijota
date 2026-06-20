package ai.pijota.extensions.examples;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.tools.TodowriteTool;

/**
 * Opt-in structured todo list via the {@code todowrite} tool. The tool is only useful for multi-step work and adds
 * weight to every prompt, so it is OFF by default — enable with the {@code --todo} flag.
 */
public final class TodoExtension implements Extension {

    @Override
    public String name() {
        return "todo";
    }

    @Override
    public String description() {
        return "Opt-in structured todo list (todowrite). Enable with --todo.";
    }

    @Override
    public void init(ExtensionAPI api) {
        if (api.runtime().getFlag("todo", false))
            api.registerTool(new TodowriteTool());
    }
}
