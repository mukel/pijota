package org.pijota.extensions.examples.plan;

import java.util.regex.Pattern;

final class PlanUtils {
    private PlanUtils() {}

    private static final Pattern[] DESTRUCTIVE = {Pattern.compile("\\brm\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmv\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcp\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmkdir\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\btouch\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bchmod\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bchown\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bln\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdd\\b", Pattern.CASE_INSENSITIVE), Pattern.compile("(^|[^<])>(?!>)"),
            Pattern.compile(">>"),
            Pattern.compile("\\bnpm\\s+(install|uninstall|update|ci|link|publish)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\byarn\\s+(add|remove|install)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpip\\s+(install|uninstall)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bapt(-get)?\\s+(install|remove|purge|update|upgrade)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bbrew\\s+(install|uninstall|upgrade)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bgit\\s+(add|commit|push|pull|merge|rebase|reset|checkout|stash|tag|init|clone)",
                                            Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bsudo\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bkill\\b", Pattern.CASE_INSENSITIVE),};

    private static final Pattern[] SAFE = {Pattern.compile("^\\s*cat\\b"), Pattern.compile("^\\s*head\\b"),
            Pattern.compile("^\\s*tail\\b"), Pattern.compile("^\\s*less\\b"), Pattern.compile("^\\s*grep\\b"),
            Pattern.compile("^\\s*find\\b"), Pattern.compile("^\\s*ls\\b"), Pattern.compile("^\\s*pwd\\b"),
            Pattern.compile("^\\s*echo\\b"), Pattern.compile("^\\s*wc\\b"), Pattern.compile("^\\s*sort\\b"),
            Pattern.compile("^\\s*uniq\\b"), Pattern.compile("^\\s*diff\\b"), Pattern.compile("^\\s*file\\b"),
            Pattern.compile("^\\s*stat\\b"), Pattern.compile("^\\s*du\\b"), Pattern.compile("^\\s*df\\b"),
            Pattern.compile("^\\s*tree\\b"), Pattern.compile("^\\s*which\\b"), Pattern.compile("^\\s*type\\b"),
            Pattern.compile("^\\s*env\\b"), Pattern.compile("^\\s*uname\\b"), Pattern.compile("^\\s*whoami\\b"),
            Pattern.compile("^\\s*id\\b"), Pattern.compile("^\\s*date\\b"), Pattern.compile("^\\s*uptime\\b"),
            Pattern.compile("^\\s*ps\\b"), Pattern.compile("^\\s*free\\b"),
            Pattern.compile("^\\s*git\\s+(status|log|diff|show|branch|remote)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*npm\\s+(list|ls|view|info|search|outdated|audit)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*node\\s+(--version|--help)"), Pattern.compile("^\\s*python\\s+(--version|--help)"),
            Pattern.compile("^\\s*curl\\s"), Pattern.compile("^\\s*jq\\b"), Pattern.compile("^\\s*rg\\b"),
            Pattern.compile("^\\s*fd\\b"), Pattern.compile("^\\s*bat\\b"),};

    static boolean isSafeCommand(String command) {
        for (var p : DESTRUCTIVE) {
            if (p.matcher(command).find())
                return false;
        }
        for (var p : SAFE) {
            if (p.matcher(command).find())
                return true;
        }
        return false;
    }
}
