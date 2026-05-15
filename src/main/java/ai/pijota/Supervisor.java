package ai.pijota;

import ai.pijota.util.SelfRespawn;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class Supervisor {
    private Supervisor() {}

    static int run(String[] originalArgs) throws IOException, InterruptedException {
        SelfRespawn.clearRestartRequest();
        List<String> command = initialWorkerCommand(originalArgs);

        while (true) {
            Process worker = new ProcessBuilder(command)
                .inheritIO()
                .directory(Path.of(System.getProperty("user.dir")).toFile())
                .start();
            int exitCode = worker.waitFor();

            var restart = SelfRespawn.readRestartRequest();
            if (restart.isEmpty()) return exitCode;

            command = restart.get();
            SelfRespawn.clearRestartRequest();
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
