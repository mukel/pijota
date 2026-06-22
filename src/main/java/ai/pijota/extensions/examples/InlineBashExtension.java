package ai.pijota.extensions.examples;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.extensions.ExtensionResult;
import ai.pijota.extensions.ExtensionRuntime.ExecResult;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands {@code !{command}} patterns in user prompts by capturing the command output and inlining it. Short-circuits
 * raw {@code !command} prefix (bash mode) without expansion.
 */
public final class InlineBashExtension implements Extension {

    private static final Pattern INLINE_BASH = Pattern.compile("!\\{([^}]+)\\}");

    @Override
    public String name() {
        return "inline-bash";
    }

    @Override
    public String description() {
        return "Expands !{command} patterns in user prompts";
    }

    @Override
    public void init(ExtensionAPI api) {
        var runtime = api.runtime();

        api.onInput(text -> {
            String trimmed = text.stripLeading();

            if (trimmed.startsWith("!") && !trimmed.startsWith("!{")) {
                return new ExtensionResult.InputResult(text, false);
            }

            Matcher m = INLINE_BASH.matcher(text);
            var replacements = new ArrayList<Replacement>();
            while (m.find()) {
                replacements.add(new Replacement(m.group(0), m.group(1).strip()));
            }
            if (replacements.isEmpty()) {
                return new ExtensionResult.InputResult(text, false);
            }

            var result = new StringBuilder();
            int lastEnd = 0;
            for (var r : replacements) {
                int idx = text.indexOf(r.original, lastEnd);
                if (idx < 0)
                    continue;
                result.append(text, lastEnd, idx);
                try {
                    ExecResult exec = runtime.exec(List.of("bash", "-c", r.command));
                    String out = exec.success() ? exec.stdout().strip() : exec.stderr().strip();
                    result.append(out);
                } catch (Exception e) {
                    result.append("[error: ").append(e.getMessage()).append("]");
                }
                lastEnd = idx + r.original.length();
            }
            result.append(text, lastEnd, text.length());

            if (runtime.ui() != null) {
                runtime.setStatus("inline-bash", "Expanded " + replacements.size() + " inline command(s)");
            }

            return new ExtensionResult.InputResult(result.toString(), false);
        });
    }

    private record Replacement(String original, String command) {
    }
}
