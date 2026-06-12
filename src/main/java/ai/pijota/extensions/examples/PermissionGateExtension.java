package ai.pijota.extensions.examples;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.extensions.ExtensionResult;
import ai.pijota.util.Util;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

public final class PermissionGateExtension implements Extension {

    private static final Pattern[] DANGEROUS = {
            Pattern.compile("\\brm\\s+(-rf?|--recursive)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bsudo\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(chmod|chown)\\b.*777", Pattern.CASE_INSENSITIVE),};

    @Override
    public String name() {
        return "permission-gate";
    }

    @Override
    public String description() {
        return "Confirms before running dangerous bash commands";
    }

    @Override
    public void init(ExtensionAPI api) {
        api.onToolCall((tc, ctx) -> {
            if (!"bash".equals(tc.toolName()))
                return null;
            String command = parseCommand(tc.arguments());
            if (command == null || !isDangerous(command))
                return null;

            if (!ctx.hasUI())
                return new ExtensionResult.Block("Dangerous command blocked (no UI)");

            try {
                boolean ok = ctx.ui().confirm("Dangerous command:\n  " + command + "\n\nAllow?").join();
                if (!ok)
                    return new ExtensionResult.Block("Blocked by user");
            } catch (Exception e) {
                return new ExtensionResult.Block("Confirm failed: " + e.getMessage());
            }

            return null;
        });
    }

    static boolean isDangerous(String command) {
        for (var p : DANGEROUS)
            if (p.matcher(command).find())
                return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    static String parseCommand(String argsJson) {
        try {
            var args = (Map<String, Object>) Util.MAPPER.readValue(argsJson, Map.class);
            return (String) args.get("command");
        } catch (IOException e) {
            return null;
        }
    }
}
