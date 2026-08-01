package org.pijota.tui;

import static org.junit.jupiter.api.Assertions.*;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class KittyKeyboardTest {

    private record Probe(boolean supported, String written) {
    }

    /**
     * Runs a negotiation against a terminal that replies with {@code reply}, and reports what was written to it. Piped
     * streams, not a byte array: a stream that hits EOF is closed by JLine's pump, which is not how a real terminal
     * behaves between replies (it simply goes quiet).
     */
    private static Probe negotiateAgainst(String reply) throws Exception {
        var captured = new ByteArrayOutputStream();
        var toTerminal = new PipedOutputStream();
        var terminalIn = new PipedInputStream(toTerminal, 1024);
        toTerminal.write(reply.getBytes(StandardCharsets.UTF_8));
        toTerminal.flush();
        try (Terminal terminal = TerminalBuilder.builder().streams(terminalIn, captured).build()) {
            boolean supported = KittyKeyboard.negotiate(terminal);
            terminal.writer().flush();
            return new Probe(supported, captured.toString(StandardCharsets.UTF_8));
        } finally {
            toTerminal.close();
        }
    }

    @Test
    void aTerminalThatReportsFlagsSupportsTheProtocol() throws Exception {
        var probe = negotiateAgainst("\033[?1u"); // CSI ? <flags> u
        assertTrue(probe.supported(), "flags reply means the protocol is on");
    }

    @Test
    void aTerminalThatAnswersOnlyDeviceAttributesDoesNot() throws Exception {
        // The DA sentinel arriving first is the whole point: support is settled by the reply, not by a timeout.
        var probe = negotiateAgainst("\033[?62;c");
        assertFalse(probe.supported());
    }

    @Test
    void silenceIsTreatedAsUnsupportedAndDoesNotHang() throws Exception {
        long start = System.currentTimeMillis();
        assertFalse(negotiateAgainst("").supported());
        assertTrue(System.currentTimeMillis() - start < 5000, "negotiation must be bounded, not block on a quiet pipe");
    }

    @Test
    void garbageOnTheWireIsNotMistakenForSupport() throws Exception {
        assertFalse(negotiateAgainst("hello there").supported());
        assertFalse(negotiateAgainst("\033[200~pasted text\033[201~").supported());
    }

    @Test
    void theQueryPushesTheFlagsAndAsksForThemBeforeTheSentinel() throws Exception {
        String written = negotiateAgainst("\033[?1u").written();
        assertTrue(written.startsWith(KittyKeyboard.ENABLE), "flags must be requested before they are queried: "
                                        + written.replace("\033", "ESC"));
        assertTrue(written.indexOf("\033[?u") < written.indexOf("\033[c"),
                                        "the DA sentinel has to come last or it can answer first on a terminal that "
                                                                        + "does support the protocol: "
                                                                        + written.replace("\033", "ESC"));
    }

    @Test
    void enableAndDisableAreTheStackPushAndPopSequences() {
        assertEquals("\033[>1u", KittyKeyboard.ENABLE, "flag 1 only: key-release events would double-fire handlers");
        assertEquals("\033[<u", KittyKeyboard.DISABLE);
    }
}
