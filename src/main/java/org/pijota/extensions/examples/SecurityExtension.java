package org.pijota.extensions.examples;

import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.ExtensionResult;
import org.pijota.extensions.ExtensionRuntime;
import org.pijota.modes.SlashCommand;
import org.pijota.util.Emojis;
import org.pijota.util.Util;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Blocks dangerous bash commands: privilege escalation, recursive delete, disk destruction, fork bombs, shell-piping,
 * iptables, destructive git, and more. Toggle with {@code /security}.
 */
public final class SecurityExtension implements Extension {

    private static final Pattern[] DANGEROUS_CMDS = {Pattern.compile("\\b(sudo|su|doas)\\b"),
            Pattern.compile("\\brm\\s+.*(?:-[a-zA-Z]*[rR][a-zA-Z]*|--recursive|--dir)\\b"),
            Pattern.compile("\\b(chmod|chown)\\b.*\\b777\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bchmod\\s+.*-R\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bchown\\s+.*-R\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(dd|mkfs|fdisk|wipefs|parted)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(shutdown|reboot|halt|poweroff|init\\s+[06])\\b"),
            Pattern.compile("\\bsystemctl\\s+(stop|disable|mask|halt|poweroff|reboot|kexec)\\b"),
            Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*}\\s*;\\s*:"),
            Pattern.compile("[0-9&]*>>?\\s*/dev/sd[a-z]"), Pattern.compile("\\bwget\\s+.*\\|\\s*(ba)?sh\\b"),
            Pattern.compile("\\bcurl\\s+.*\\|\\s*(ba)?sh\\b"),
            Pattern.compile("\\biptables\\s+-[ADIFXP]\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bnc\\s+.*-(l|e)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bgit\\s+(?:push\\s+.*--(?:force|-f)|reset\\s+--hard)\\b", Pattern.CASE_INSENSITIVE),};

    private static final String[] DANGEROUS_LABELS = {"privilege escalation (sudo/su/doas)", "recursive delete (rm -r)",
            "world-writable perms (chmod/chown 777)", "recursive chmod", "recursive chown",
            "disk destruction (dd/mkfs/fdisk/wipefs/parted)", "system shutdown/reboot", "systemctl destructive",
            "fork bomb", "write to raw disk device", "pipe wget to shell", "pipe curl to shell",
            "iptables modification", "netcat listen/exec", "destructive git (force push / hard reset)",};

    private volatile boolean enabled = true;

    @Override
    public String name() {
        return "security";
    }

    @Override
    public String description() {
        return "Blocks dangerous commands (sudo, rm -rf, dd, pipe-to-shell)";
    }

    @Override
    public void init(ExtensionAPI api) {
        var runtime = api.runtime();
        api.registerSlashCommand(new SlashCommand("security", "Toggle dangerous command blocking", List.of(),
                                        ignored -> toggle(runtime)));
        // A live supplier (re-evaluated each render) keeps the chip in sync with the toggle everywhere, including
        // after /new, which clears keyed setStatus entries but not the extension's own state. Blocking is on by
        // default, so only its absence earns a chip: the status bar should name the exception, not the norm.
        api.registerStatusLines(() -> enabled ? List.of() : List.of(Emojis.UNLOCK + " unlocked"));

        api.onToolCall(tc -> {
            if (!enabled)
                return null;
            if (!Util.SHELL_TOOL_NAMES.contains(tc.toolName()))
                return null;
            String command = Util.parseBashArgs(tc.arguments());
            if (command == null)
                return null;

            for (int pi = 0; pi < DANGEROUS_CMDS.length; pi++) {
                if (DANGEROUS_CMDS[pi].matcher(command).find()) {
                    return new ExtensionResult.Block("Blocked by security: " + DANGEROUS_LABELS[pi] + " in: "
                                                    + summarize(command));
                }
            }
            return null;
        });

        api.onUserBash(ub -> {
            if (!enabled)
                return ExtensionResult.UserBashResult.allow();
            String command = ub.command();
            if (command == null)
                return ExtensionResult.UserBashResult.allow();

            for (int pi = 0; pi < DANGEROUS_CMDS.length; pi++) {
                if (DANGEROUS_CMDS[pi].matcher(command).find()) {
                    if (runtime.ui() != null) {
                        try {
                            boolean ok = runtime.ui().confirm(Emojis.WARN + " Dangerous command:\n  " + command
                                                            + "\n\nExecute anyway?").join();
                            if (!ok)
                                return ExtensionResult.UserBashResult.block("Blocked by user");
                            return ExtensionResult.UserBashResult.allow();
                        } catch (Exception e) {
                            return ExtensionResult.UserBashResult.block("Confirm failed: " + e.getMessage());
                        }
                    }
                    return ExtensionResult.UserBashResult
                                                    .block("Blocked by security: dangerous command (no UI available)");
                }
            }
            return ExtensionResult.UserBashResult.allow();
        });
    }

    @Override
    public void onReady(ExtensionRuntime rt) {
        if (!rt.getFlag("security", true))
            enabled = false;
    }

    /**
     * Says what it did. Re-arming the guard takes the only chip off the status bar, so a silent toggle showed nothing
     * but a disappearance -- indistinguishable from a command that did not run. This is also the more important of the
     * two directions to state plainly: it decides whether the next rm -rf is stopped.
     */
    private void toggle(ExtensionRuntime runtime) {
        enabled = !enabled;
        runtime.setStatus(enabled ? "blocking dangerous commands" : "not blocking: dangerous commands will run");
    }

    static String summarize(String command) {
        return command.length() > 120 ? command.substring(0, 117) + "..." : command;
    }
}
