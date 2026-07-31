package org.pijota.acp;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the tool-approval gating policy in isolation from the extension/JSON-RPC plumbing. */
class AcpPermissionExtensionTest {

    private static AcpPermissionExtension withRequester(AcpPermissionExtension.Requester r) {
        return new AcpPermissionExtension(r);
    }

    @Test
    void readOnlyToolIsNeverGated() {
        var called = new AtomicBoolean();
        var ext = withRequester((n, id, a) -> {
            called.set(true);
            return CompletableFuture.completedFuture(true);
        });
        assertNull(ext.gate("read", "c", "{}"));
        assertFalse(called.get(), "a read-only tool must not trigger a permission round-trip");
    }

    @Test
    void allowedMutatingToolPasses() {
        var ext = withRequester((n, id, a) -> CompletableFuture.completedFuture(true));
        assertNull(ext.gate("bash", "c", "{}"));
    }

    @Test
    void rejectedMutatingToolIsBlocked() {
        var ext = withRequester((n, id, a) -> CompletableFuture.completedFuture(false));
        assertNotNull(ext.gate("bash", "c", "{}"));
    }

    @Test
    void gatedSetIsTheCanonicalShellSetPlusFileMutators() {
        assertTrue(AcpPermissionExtension.isGated("bash"));
        assertTrue(AcpPermissionExtension.isGated("tmux_bash")); // not just "bash", the canonical shell set
        assertTrue(AcpPermissionExtension.isGated("jobs_run"));
        assertTrue(AcpPermissionExtension.isGated("write"));
        assertTrue(AcpPermissionExtension.isGated("edit"));
        assertTrue(AcpPermissionExtension.isGated("apply_patch"));
        assertFalse(AcpPermissionExtension.isGated("read"));
        assertFalse(AcpPermissionExtension.isGated("grep"));
    }

    @Test
    void brokenRoundTripFailsOpen() {
        var ext = withRequester((n, id, a) -> CompletableFuture.failedFuture(new RuntimeException("client gone")));
        assertNull(ext.gate("bash", "c", "{}"), "a broken permission round-trip must not wedge the agent");
    }

    @Test
    void requesterReceivesToolNameCallIdAndArgs() {
        var seen = new AtomicReference<String>();
        var ext = withRequester((n, id, a) -> {
            seen.set(n + "|" + id + "|" + a);
            return CompletableFuture.completedFuture(true);
        });
        ext.gate("write", "call-1", "{\"path\":\"/x\"}");
        assertEquals("write|call-1|{\"path\":\"/x\"}", seen.get());
    }
}
