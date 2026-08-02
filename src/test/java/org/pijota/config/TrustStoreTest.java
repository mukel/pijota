package org.pijota.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

class TrustStoreTest {

    private static Path tempStore() throws Exception {
        Path dir = Files.createTempDirectory("pijota-trust");
        return dir.resolve("trust.json");
    }

    @Test
    void nothingTrustedByDefault() throws Exception {
        var store = new TrustStore(tempStore());
        assertFalse(store.isTrusted("/some/project"));
        assertFalse(store.isTrusted(null));
        assertFalse(store.isTrusted(""));
    }

    @Test
    void trustCoversTheDirAndItsSubdirectories() throws Exception {
        var store = new TrustStore(tempStore());
        store.trust("/home/u/proj");
        assertTrue(store.isTrusted("/home/u/proj"));
        assertTrue(store.isTrusted("/home/u/proj/src/main"), "subdirectories inherit trust");
        assertFalse(store.isTrusted("/home/u"), "ancestors are not trusted");
        assertFalse(store.isTrusted("/home/u/other"));
    }

    @Test
    void trustPersistsAcrossReload() throws Exception {
        Path file = tempStore();
        new TrustStore(file).trust("/home/u/proj");
        assertTrue(new TrustStore(file).isTrusted("/home/u/proj"), "reloaded store keeps the trusted dir");
    }

    @Test
    void revokeRemovesTrust() throws Exception {
        var store = new TrustStore(tempStore());
        store.trust("/home/u/proj");
        store.revoke("/home/u/proj");
        assertFalse(store.isTrusted("/home/u/proj"));
    }

    @Test
    void tracksMultipleIndependentDirs() throws Exception {
        var store = new TrustStore(tempStore());
        store.trust("/a/one");
        store.trust("/b/two");
        assertTrue(store.isTrusted("/a/one"));
        assertTrue(store.isTrusted("/b/two/sub"));
        assertFalse(store.isTrusted("/c/three"));
        assertEquals(2, store.trustedDirs().size());
    }

    @Test
    void revokingChildKeepsTrustFromAStillTrustedParent() throws Exception {
        var store = new TrustStore(tempStore());
        store.trust("/home/u");
        store.trust("/home/u/proj");
        store.revoke("/home/u/proj"); // exact entry removed, but the parent still covers it
        assertTrue(store.isTrusted("/home/u/proj"));
        store.revoke("/home/u");
        assertFalse(store.isTrusted("/home/u/proj"));
    }

    @Test
    void trustIsIdempotent() throws Exception {
        var store = new TrustStore(tempStore());
        store.trust("/x");
        store.trust("/x");
        assertEquals(1, store.trustedDirs().size());
    }

    @Test
    void toleratesCorruptStoreFile() throws Exception {
        Path file = tempStore();
        Files.writeString(file, "{ this is not valid json");
        var store = new TrustStore(file); // must not throw
        assertFalse(store.isTrusted("/anything"));
        store.trust("/now/valid"); // and recovers, overwrites with a good file
        assertTrue(new TrustStore(file).isTrusted("/now/valid"));
    }

    @Test
    void persistedStoreFileIsOwnerOnly() throws Exception {
        var fs = FileSystems.getDefault();
        Assumptions.assumeTrue(fs.supportedFileAttributeViews().contains("posix"));
        Path file = tempStore();
        new TrustStore(file).trust("/home/u/proj");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }
}
