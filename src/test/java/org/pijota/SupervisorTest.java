package org.pijota;

import org.pijota.util.SelfRespawn;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The supervisor launches the worker that does all the actual work, and builds its command from scratch rather than
 * forwarding its own. Every system property used to be dropped on the way: {@code -Dpijota.home=/somewhere} moved the
 * banner, the settings and the model, and the worker then read {@code ~/.pijota}. Trust decisions made in a redirected
 * home were written to the real one, which is how it was noticed.
 */
class SupervisorTest {

    @Test
    void theWorkerInheritsTheSystemPropertiesTheSupervisorWasGiven() {
        var expected = SelfRespawn.jvmOptions();
        // From surefire's argLine. Asserting against an empty list would pass however the command is built.
        assertFalse(expected.isEmpty(), "the test JVM must be started with a -D for this to mean anything");

        var command = Supervisor.initialWorkerCommand(new String[] {"--continue"});

        assertTrue(command.containsAll(expected), "every -D must reach the worker: " + command);
        for (String option : expected)
            assertTrue(command.indexOf(option) < command.size() - 1, option + " must precede the program arguments");
    }

    /** The options belong to the JVM, so they have to sit before {@code -jar}/{@code -cp} rather than after it. */
    @Test
    void thePropertiesPrecedeTheJarOrClasspath() {
        var command = Supervisor.initialWorkerCommand(new String[] {});

        int launchTarget = command.indexOf("-jar") >= 0 ? command.indexOf("-jar") : command.indexOf("-cp");
        assertTrue(launchTarget > 0, "the worker is launched with -jar or -cp: " + command);
        for (String option : SelfRespawn.jvmOptions())
            assertTrue(command.indexOf(option) < launchTarget,
                                            option + " came after the launch target, so java reads it as an argument: "
                                                                            + command);
    }

    /** The user's own arguments still reach the worker, after the flag that marks it as one. */
    @Test
    void theOriginalArgumentsAreForwardedAfterTheWorkerFlag() {
        var command = Supervisor.initialWorkerCommand(new String[] {"--continue", "-m", "some/model"});

        int worker = command.indexOf("--worker");
        assertTrue(worker > 0, "the child must be told it is the worker: " + command);
        assertEquals(java.util.List.of("--continue", "-m", "some/model"), command.subList(worker + 1, command.size()));
    }
}
