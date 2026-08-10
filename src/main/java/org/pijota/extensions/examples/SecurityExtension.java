package org.pijota.extensions.examples;

import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.ExtensionResult;
import org.pijota.extensions.ExtensionRuntime;
import org.pijota.modes.SlashCommand;
import org.pijota.util.Emojis;
import org.pijota.util.Util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Blocks dangerous bash commands: privilege escalation, recursive delete, disk destruction, fork bombs, shell-piping,
 * iptables, destructive git, and more. Toggle with {@code /security}.
 */
public final class SecurityExtension implements Extension {

    /** A command shape worth stopping, and what to call it when explaining the block. */
    private record Danger(Pattern pattern, String what) {
    }

    /**
     * One list, not two arrays in index lockstep.
     *
     * <p>
     * The patterns and their names were parallel arrays walked by a shared index, in two separate scans. Adding a
     * pattern without its label, or inserting one in the middle of one array only, mislabels every entry after it -- or
     * throws -- and nothing says so until a real command matches. Pairing them makes the mistake unwritable, the same
     * move the tunnel channels and the metadata types already use here.
     */
    private static final List<Danger> DANGERS = List.of(
                                    new Danger(Pattern.compile("\\b(sudo|su|doas)\\b"),
                                                                    "privilege escalation (sudo/su/doas)"),
                                    new Danger(Pattern.compile("\\brm\\s+.*(?:-[a-zA-Z]*[rR][a-zA-Z]*|--recursive|--dir)\\b"),
                                                                    "recursive delete (rm -r)"),
                                    new Danger(Pattern.compile("\\b(chmod|chown)\\b.*\\b777\\b",
                                                                    Pattern.CASE_INSENSITIVE),
                                                                    "world-writable perms (chmod/chown 777)"),
                                    new Danger(Pattern.compile("\\bchmod\\s+.*-R\\b", Pattern.CASE_INSENSITIVE),
                                                                    "recursive chmod"),
                                    new Danger(Pattern.compile("\\bchown\\s+.*-R\\b", Pattern.CASE_INSENSITIVE),
                                                                    "recursive chown"),
                                    new Danger(Pattern.compile("\\b(dd|mkfs|fdisk|wipefs|parted)\\b",
                                                                    Pattern.CASE_INSENSITIVE),
                                                                    "disk destruction (dd/mkfs/fdisk/wipefs/parted)"),
                                    new Danger(Pattern.compile("\\b(shutdown|reboot|halt|poweroff|init\\s+[06])\\b"),
                                                                    "system shutdown/reboot"),
                                    new Danger(Pattern.compile("\\bsystemctl\\s+(stop|disable|mask|halt|poweroff|reboot|kexec)\\b"),
                                                                    "systemctl destructive"),
                                    new Danger(Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*}\\s*;\\s*:"),
                                                                    "fork bomb"),
                                    new Danger(Pattern.compile("[0-9&]*>>?\\s*/dev/sd[a-z]"),
                                                                    "write to raw disk device"),
                                    new Danger(Pattern.compile("\\bwget\\s+.*\\|\\s*(ba)?sh\\b"), "pipe wget to shell"),
                                    new Danger(Pattern.compile("\\bcurl\\s+.*\\|\\s*(ba)?sh\\b"), "pipe curl to shell"),
                                    new Danger(Pattern.compile("\\biptables\\s+-[ADIFXP]\\b", Pattern.CASE_INSENSITIVE),
                                                                    "iptables modification"),
                                    new Danger(Pattern.compile("\\bnc\\s+.*-(l|e)\\b", Pattern.CASE_INSENSITIVE),
                                                                    "netcat listen/exec"),
                                    new Danger(Pattern.compile("\\bgit\\s+(?:push\\s+.*--(?:force|-f)|reset\\s+--hard)\\b",
                                                                    Pattern.CASE_INSENSITIVE),
                                                                    "destructive git (force push / hard reset)"));

    /** The first shape this command matches, or null when none does. Both callers ask the same question. */
    private static Danger firstDanger(String command) {
        for (var danger : DANGERS)
            if (danger.pattern().matcher(command).find())
                return danger;
        return null;
    }

    private static final Logger log = LoggerFactory.getLogger(SecurityExtension.class);

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

            var danger = firstDanger(command);
            if (danger == null)
                return null;
            log.warn("security: blocked {} tool call -- {} in: {}", tc.toolName(), danger.what(), summarize(command));
            return new ExtensionResult.Block("Blocked by security: " + danger.what() + " in: " + summarize(command));
        });

        api.onUserBash(ub -> {
            if (!enabled)
                return ExtensionResult.UserBashResult.allow();
            String command = ub.command();
            if (command == null)
                return ExtensionResult.UserBashResult.allow();

            var danger = firstDanger(command);
            if (danger == null)
                return ExtensionResult.UserBashResult.allow();
            if (runtime.ui() != null) {
                try {
                    boolean ok = runtime.ui().confirm(Emojis.WARN + " Dangerous command:\n  " + command
                                                    + "\n\nExecute anyway?").join();
                    // The override is the line worth keeping. A block is the guard working and leaves the machine as
                    // it was; an approved rm -rf is the one event you go looking for afterwards, and it is the only
                    // one that leaves nothing else behind to find.
                    log.warn("security: user {} {} in: {}", ok ? "ALLOWED" : "declined", danger.what(),
                                                    summarize(command));
                    if (!ok)
                        return ExtensionResult.UserBashResult.block("Blocked by user");
                    return ExtensionResult.UserBashResult.allow();
                } catch (Exception e) {
                    return ExtensionResult.UserBashResult.block("Confirm failed: " + e.getMessage());
                }
            }
            log.warn("security: blocked user command with no UI to ask -- {} in: {}", danger.what(),
                                            summarize(command));
            return ExtensionResult.UserBashResult.block("Blocked by security: dangerous command (no UI available)");
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
