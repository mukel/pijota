package org.pijota;

import org.pijota.Main.Frontend;
import org.pijota.cli.ArgParser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which frontend a command line selects. The order of these checks is the entire behaviour, and it was wrong once in a
 * way no unit test could see: {@code RpcMode} and its parser were both covered, while the branch that reaches them sat
 * below the one that claims redirected stdin, so the mode was dead in the only way it can be used.
 */
class FrontendRoutingTest {

    private static Frontend route(boolean stdinRedirected, String... argv) {
        return Main.frontendFor(ArgParser.parse(argv), stdinRedirected);
    }

    /**
     * Regression. JSON-RPC arrives on stdin by definition, so "rpc with a pipe" is the only way the mode is ever used;
     * routing it to print mode fed the request lines to the model, which answered them in prose.
     */
    @Test
    void rpcBeatsPrintModeWhenStdinIsAPipe() {
        assertEquals(Frontend.RPC, route(true, "--mode", "rpc"));
        assertEquals(Frontend.RPC, route(false, "--mode", "rpc"), "and from a terminal too");
    }

    @Test
    void anythingRedirectedIntoStdinIsOtherwisePrintMode() {
        assertEquals(Frontend.PRINT, route(true));
        assertEquals(Frontend.PRINT, route(false, "-p", "hi"));
        assertEquals(Frontend.PRINT, route(false, "explain", "this"), "a bare prompt is one shot");
    }

    @Test
    void aBareInvocationIsTheTui() {
        assertEquals(Frontend.TUI, route(false));
    }

    /** A named frontend wins over everything, including a pipe. */
    @Test
    void subcommandsOutrankStdin() {
        assertEquals(Frontend.WEB, route(true, "web", "127.0.0.1:8080"));
        assertEquals(Frontend.TELEGRAM, route(true, "telegram"));
        assertEquals(Frontend.ACP, route(true, "acp"));
    }

    @Test
    void jsonModeIsStillPrintMode() {
        assertEquals(Frontend.PRINT, route(true, "--mode", "json"),
                                        "--mode json streams events out of a print run; only rpc is its own frontend");
    }
}
