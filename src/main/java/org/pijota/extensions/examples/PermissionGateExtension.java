package org.pijota.extensions.examples;

import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.ExtensionResult;
import org.pijota.util.Util;

import java.util.regex.Pattern;

/**
 * Confirms before running dangerous bash commands. On each {@code bash} tool call, checks the command against patterns
 * for recursive deletion, privilege escalation, and permission modifications, then prompts the user for confirmation
 * via the UI. Not registered by default.
 */
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
        var runtime = api.runtime();
        api.onToolCall(tc -> {
            if (!Util.SHELL_TOOL_NAMES.contains(tc.toolName()))
                return null;
            String command = parseCommand(tc.arguments());
            if (command == null || !isDangerous(command))
                return null;

            if (runtime.ui() == null)
                return new ExtensionResult.Block("Dangerous command blocked (no UI)");

            try {
                boolean ok = runtime.ui().confirm("Dangerous command:\n  " + command + "\n\nAllow?").join();
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

    static String parseCommand(String argsJson) {
        return Util.parseBashArgs(argsJson);
    }
}
