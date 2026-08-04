package org.pijota.llm;

import org.pijota.config.AppConfig;
import org.pijota.config.ProviderCatalog;
import org.pijota.config.SettingsManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A refresh used to end at a static field, so it died with the process: a user who ran {@code /refresh} to reach their
 * provider's newer models had to run it again in every session, forever.
 *
 * <p>
 * It matters most for the models nothing else knows about. A central model archive can carry the hosted providers, but
 * not an Ollama on localhost or a company's internal gateway; asking the provider what it serves is the only way those
 * appear at all, so the answer is worth keeping.
 */
class ModelCachePersistsTest {

    private static final ModelInfo LOCAL = new ModelInfo("my-local-llama-70b", "mylocal", "My Local Llama 70b",
                                    128_000L);

    private interface Body {
        void run() throws Exception;
    }

    private static void withHome(Path home, Body body) throws Exception {
        String saved = System.setProperty("pijota.home", home.toString());
        List<ModelInfo> before = ModelRegistry.KNOWN_MODELS;
        // What the bootstrap does before reading the cache: the user's own providers join the catalog first, which is
        // what keeps their discovered models from being pruned as belonging to a provider that no longer exists.
        ProviderCatalog.registerUserProviders(List.of(new ProviderCatalog.Provider("mylocal", "My Local", "openai",
                                        "http://127.0.0.1:1/v1", List.of(), "my-local-llama-70b", Map.of(), null,
                                        null)));
        try {
            body.run();
        } finally {
            ProviderCatalog.registerUserProviders(List.of());
            ModelRegistry.KNOWN_MODELS = before; // static catalog: hand it back to the other tests as it was
            if (saved == null)
                System.clearProperty("pijota.home");
            else
                System.setProperty("pijota.home", saved);
        }
    }

    @Test
    void aSavedDiscoveryIsAppliedByTheNextProcess(@TempDir Path home) throws Exception {
        withHome(home, () -> {
            ModelRegistry.saveDiscovered(List.of(LOCAL));
            assertTrue(Files.isRegularFile(ModelRegistry.cacheFile()), "the refresh must reach disk");

            // What a fresh process does: nothing in memory, then the cache.
            ModelRegistry.replaceDiscovered(List.of());
            assertTrue(ModelRegistry.forProvider("mylocal").isEmpty(), "fixture check: gone from memory");

            ModelRegistry.loadDiscoveredCache();
            assertEquals(List.of("my-local-llama-70b"),
                                            ModelRegistry.forProvider("mylocal").stream().map(ModelInfo::id).toList());
        });
    }

    /** The built-ins are in the binary; writing them out too would only let them go stale on disk. */
    @Test
    void onlyTheDiscoveredModelsAreWritten(@TempDir Path home) throws Exception {
        withHome(home, () -> {
            ModelRegistry.saveDiscovered(List.of(LOCAL));
            String written = Files.readString(ModelRegistry.cacheFile());

            assertTrue(written.contains("my-local-llama-70b"), written);
            assertFalse(written.contains("\"provider\" : \"anthropic\""), "built-ins do not belong in the cache");
        });
    }

    /** A discovered model still loses to nothing: the merge keeps built-ins and layers discovery on top. */
    @Test
    void loadingTheCacheKeepsTheBuiltInCatalog(@TempDir Path home) throws Exception {
        withHome(home, () -> {
            ModelRegistry.saveDiscovered(List.of(LOCAL));
            ModelRegistry.loadDiscoveredCache();

            assertFalse(ModelRegistry.forProvider("anthropic").isEmpty(), "the bundled catalog must survive");
            assertFalse(ModelRegistry.forProvider("mylocal").isEmpty(), "and the discovery sits alongside it");
        });
    }

    /**
     * It is a convenience copy of something the network can supply again, so a corrupt one is dropped rather than
     * reported at every launch about a file the user never created.
     */
    @Test
    void anUnreadableCacheIsDiscardedRatherThanFatal(@TempDir Path home) throws Exception {
        withHome(home, () -> {
            Files.createDirectories(home);
            Files.writeString(ModelRegistry.cacheFile(), "{ not json");

            assertDoesNotThrow(ModelRegistry::loadDiscoveredCache);
            assertFalse(Files.exists(ModelRegistry.cacheFile()), "and it does not stay to fail again tomorrow");
        });
    }

    /**
     * The refresher itself must persist, not merely update memory. Nothing asserted this, so a refresh that only
     * touched the static field passed every test while being forgotten at exit.
     */
    @Test
    void aRefreshWritesWhatItFound(@TempDir Path home) throws Exception {
        withHome(home, () -> {
            ModelRefresher.refresh(new SettingsManager(new AppConfig(), ignored -> {}));
            assertTrue(Files.isRegularFile(ModelRegistry.cacheFile()),
                                            "a refresh that only updates memory is forgotten when the process exits");
        });
    }

    /**
     * Nothing else ever removes a cache entry, so a provider deleted from settings.json kept offering its models for
     * good, and choosing one failed at the request. The catalog already carries the user's own providers by the time
     * the cache is read, so a provider missing from it is a provider that is gone.
     */
    @Test
    void modelsFromAProviderThatNoLongerExistsAreDropped(@TempDir Path home) throws Exception {
        withHome(home, () -> {
            ModelRegistry.saveDiscovered(List.of(new ModelInfo("ghost", "deleted-provider", "Ghost", 128_000L)));
            ModelRegistry.replaceDiscovered(List.of());

            ModelRegistry.loadDiscoveredCache();

            assertTrue(ModelRegistry.forProvider("deleted-provider").isEmpty(),
                                            "a provider no longer in the catalog must not keep offering models");
        });
    }

    /** And the pruning must not take the live ones with it. */
    @Test
    void modelsFromAProviderThatStillExistsSurvive(@TempDir Path home) throws Exception {
        withHome(home, () -> {
            var known = ModelRegistry.KNOWN_MODELS.getFirst().provider(); // a built-in, so certainly in the catalog
            ModelRegistry.saveDiscovered(List.of(new ModelInfo("brand-new-id", known, "Brand New", 128_000L)));
            ModelRegistry.replaceDiscovered(List.of());

            ModelRegistry.loadDiscoveredCache();

            assertTrue(ModelRegistry.forProvider(known).stream().anyMatch(m -> m.id().equals("brand-new-id")),
                                            "discovery for a live provider is the whole point of the cache");
        });
    }

    @Test
    void noCacheIsNotAnError(@TempDir Path home) throws Exception {
        withHome(home, () -> {
            assertFalse(Files.exists(ModelRegistry.cacheFile()), "fixture check");
            assertDoesNotThrow(ModelRegistry::loadDiscoveredCache);
        });
    }
}
