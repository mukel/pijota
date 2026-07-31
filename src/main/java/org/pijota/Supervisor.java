package org.pijota;

import org.pijota.util.SelfRespawn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class Supervisor {
    private static final Logger logger = LoggerFactory.getLogger(Supervisor.class);

    private Supervisor() {}

    static int run(String[] originalArgs) throws IOException, InterruptedException {
        SelfRespawn.clearRestartRequest();
        SelfRespawn.markStartupHealthy(); // clear any stale boot sentinel from a prior run
        var cwd = Path.of(System.getProperty("user.dir")).toFile();
        List<String> command = initialWorkerCommand(originalArgs);
        boolean respawned = false; // is the current command a respawn (vs the initial launch)?
        boolean rolledBack = false; // auto-rollback is one-shot, to avoid a crash loop

        while (true) {
            if (respawned)
                SelfRespawn.markBooting(); // arm the boot-health check; the worker clears it once it starts cleanly
            int exitCode = new ProcessBuilder(command).inheritIO().directory(cwd).start().waitFor();

            var restart = SelfRespawn.readRestartRequest();
            if (restart.isPresent()) {
                command = restart.get();
                SelfRespawn.clearRestartRequest();
                respawned = true;
                continue;
            }

            // No restart requested. If a freshly-respawned build crashed without ever signalling a healthy startup,
            // auto-roll-back to the previous build. A self-modification that compiles + tests but dies on boot must
            // never brick the agent. The session arg is carried over, so the conversation is preserved.
            if (respawned && exitCode != 0 && !rolledBack && SelfRespawn.isBooting()) {
                var rollback = SelfRespawn.autoRollbackCommand(command);
                if (rollback.isPresent()) {
                    logger.warn("New build failed to start (exit {}); rolled back to the previous build; session preserved",
                                                    exitCode);
                    command = rollback.get();
                    rolledBack = true; // respawned is already true here; the rollback relaunch is armed next loop
                    SelfRespawn.markStartupHealthy(); // reset the sentinel before relaunching the good build
                    continue;
                }
            }
            SelfRespawn.markStartupHealthy(); // clear the sentinel on a clean/normal exit
            return exitCode;
        }
    }

    private static List<String> initialWorkerCommand(String[] originalArgs) {
        var command = new ArrayList<String>();
        command.add(SelfRespawn.resolveJava());

        Path jar = SelfRespawn.findCurrentJar();
        if (jar != null) {
            command.add("-jar");
            command.add(jar.toAbsolutePath().toString());
        } else {
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(Main.class.getName());
        }

        command.add("--worker");
        command.addAll(Arrays.asList(originalArgs));
        return command;
    }
}
