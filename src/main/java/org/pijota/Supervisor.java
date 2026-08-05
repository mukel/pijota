package org.pijota;

import org.pijota.util.SelfRespawn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
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

    /** Package-private so the -D propagation can be asserted; it has no other reason to be visible. */
    static List<String> initialWorkerCommand(String[] originalArgs) {
        var command = new ArrayList<String>();
        command.add(SelfRespawn.resolveJava());
        command.addAll(SelfRespawn.jvmOptions());

        // -jar throws the classpath away and takes only what the jar's own manifest declares, which is everything
        // for the shaded build and nothing for any other. A one entry classpath IS that shaded jar, so -jar is right.
        // Anything longer means the dependencies were resolved onto the classpath -- jbang, mvn exec, an IDE, a plain
        // java -cp -- and handing the child -jar starts it with pijota's own classes and nothing else, where it dies
        // in Main's static initializer looking for a logger. That is what a release from Maven Central looks like:
        // a thin jar beside its dependencies, which is exactly the arrangement this used to break on.
        String classpath = System.getProperty("java.class.path", "");
        Path jar = SelfRespawn.findCurrentJar();
        if (jar != null && !classpath.contains(File.pathSeparator)) {
            command.add("-jar");
            command.add(jar.toAbsolutePath().toString());
        } else {
            command.add("-cp");
            command.add(classpath);
            command.add(Main.class.getName());
        }

        command.add("--worker");
        command.addAll(Arrays.asList(originalArgs));
        return command;
    }
}
