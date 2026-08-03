package org.pijota;

import org.pijota.cli.ArgParser;
import org.pijota.config.ProviderCatalog;
import org.pijota.testkit.StdioCapture;
import org.pijota.util.Util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The reference docs are only worth consulting if they describe this build. Nothing keeps prose in step with a settings
 * record or a flag table except a check that reads both, so these compare what the code offers against what
 * {@code docs/} claims.
 *
 * <p>
 * Coverage only, deliberately: a key or flag has to be mentioned, not described in any particular way. That catches the
 * failure that matters -- adding a setting and forgetting the page -- without turning every wording change into a test
 * failure.
 */
class DocsCoverConfigurationTest {

    @RegisterExtension
    final StdioCapture stdio = new StdioCapture();

    private static String doc(String name) throws IOException {
        Path path = Path.of("docs", name);
        assertTrue(Files.isRegularFile(path), path + " is missing");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** Every {@code @JsonProperty} in the settings record is a key someone can write; each needs a home in the docs. */
    @Test
    void everySettingIsDocumented() throws IOException {
        String source = Files.readString(Path.of("src/main/java/org/pijota/config/AppConfig.java"),
                                        StandardCharsets.UTF_8);
        String docs = doc("settings.md");

        var undocumented = new TreeSet<String>();
        var keys = Pattern.compile("@JsonProperty\\(\"([a-z_]+)\"\\)").matcher(source);
        while (keys.find())
            if (!mentions(docs, keys.group(1)))
                undocumented.add(keys.group(1));

        assertTrue(undocumented.isEmpty(), "settings.json keys with no mention in docs/settings.md: " + undocumented);
    }

    /**
     * Whether the page names this key at all: on its own, qualified by its parent object
     * ({@code `compaction.reserve_tokens`}), or as a JSON key in an example. Nesting is a formatting choice, so
     * insisting on the bare name would force the docs into one shape to satisfy the test.
     */
    private static boolean mentions(String docs, String key) {
        return docs.contains("`" + key + "`") || docs.contains("." + key + "`") || docs.contains("\"" + key + "\"");
    }

    /** A provider nobody documents is a provider nobody finds. */
    @Test
    void everyBuiltInProviderIsDocumented() throws IOException {
        String docs = doc("providers.md");

        var missing = ProviderCatalog.providers().stream().map(ProviderCatalog.Provider::id)
                                        .filter(id -> !docs.contains("`" + id + "`")).sorted().toList();

        assertTrue(missing.isEmpty(), "providers with no mention in docs/providers.md: " + missing);
    }

    /**
     * Every long flag {@code --help} advertises is one a reader will look up. The help text is the source of truth
     * here: it is what the binary prints, and the docs are the thing that drifts from it.
     */
    @Test
    void everyAdvertisedFlagIsDocumented() throws IOException {
        ArgParser.printHelp();
        String help = stdio.out();
        assertFalse(help.isBlank(), "no help text captured");

        String docs = doc("cli.md") + doc("extensions.md");
        var undocumented = new ArrayList<String>();
        var flags = Pattern.compile("(--[a-z][a-z-]+)").matcher(help);
        var seen = new TreeSet<String>();
        while (flags.find()) {
            String flag = flags.group(1);
            if (seen.add(flag) && !docs.contains(flag))
                undocumented.add(flag);
        }

        assertTrue(undocumented.isEmpty(), "flags in --help with no mention in the docs: " + undocumented);
    }

    /**
     * A {@code provider/model} written in the docs has to be a model that exists. An invented id still "works" --
     * pijota sends any id verbatim -- so nothing fails, and the reader copies a name no provider has heard of. This
     * caught {@code anthropic/claude-sonnet-4-20250514}, which I had written from memory into three pages.
     */
    @Test
    void everyModelIdInTheDocsIsInTheCatalogue() throws IOException {
        var known = new TreeSet<String>();
        var catalogue = Util.MAPPER.readTree(Files.readString(Path.of("src/main/resources/models.json"),
                                        StandardCharsets.UTF_8));
        catalogue.path("models").forEach(m -> known.add(m.path("id").asText()));
        assertFalse(known.isEmpty(), "no models parsed; the catalogue shape changed");

        var invented = new TreeSet<String>();
        var cited = Pattern.compile("\\b(?:anthropic|openai|deepseek|google|groq|mistral)/([a-z0-9.\\-]+)");
        try (var pages = Files.list(Path.of("docs"))) {
            var files = new ArrayList<>(pages.filter(p -> p.toString().endsWith(".md")).toList());
            files.add(Path.of("README.md"));
            for (Path page : files) {
                var m = cited.matcher(Files.readString(page, StandardCharsets.UTF_8));
                while (m.find())
                    if (!known.contains(m.group(1)))
                        invented.add(page.getFileName() + ": " + m.group(1));
            }
        }

        assertTrue(invented.isEmpty(), "model ids cited in the docs that no provider offers: " + invented);
    }

    /** The index is the way in; a page it does not link is a page nobody opens. */
    @Test
    void theIndexLinksEveryPage() throws IOException {
        String index = doc("index.md");

        try (var pages = Files.list(Path.of("docs"))) {
            var unlinked = pages.map(p -> p.getFileName().toString()).filter(name -> name.endsWith(".md"))
                                            .filter(name -> !name.equals("index.md"))
                                            .filter(name -> !index.contains(name)).sorted().toList();

            assertTrue(unlinked.isEmpty(), "docs pages the index does not link: " + unlinked);
        }
    }
}
