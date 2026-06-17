package ai.pijota.extensions.examples;

import ai.pijota.browser.BrowserSession;
import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.extensions.ExtensionRuntime;
import ai.pijota.tools.Tool;
import ai.pijota.util.Util;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in web browsing through a real (visible) Chrome — the ds4 approach. Off by default; enable with {@code --browse}.
 * Exposes two tools, {@code web_google_search} and {@code web_visit_page}, returning clean markdown from a rendered
 * page (so paywalls/consent walls/SPAs that defeat a plain fetch work). The browser uses a dedicated persistent profile
 * under {@code ~/.pijota/browser}, is launched lazily on first use after an interactive confirmation, reused across the
 * session, and closed on shutdown.
 */
public final class WebBrowseExtension implements Extension {

    private static final int DEFAULT_PORT = 9333;

    private ExtensionAPI api;
    private BrowserSession session;
    private String browser = "chrome"; // selected browser, for the launch-confirmation message

    @Override
    public String name() {
        return "browse";
    }

    @Override
    public String description() {
        return "Opt-in web browsing via a real browser (web_google_search, web_visit_page). Enable with --browse; "
                                        + "pick the browser with --browser=chrome|firefox|safari.";
    }

    @Override
    public void init(ExtensionAPI api) {
        ExtensionRuntime rt = api.runtime();
        if (!rt.getFlag("browse", false))
            return;
        this.api = api;
        int port = DEFAULT_PORT;
        var portFlag = rt.getFlag("browse-port");
        if (portFlag.isPresent())
            try {
                port = Integer.parseInt(portFlag.get().trim());
            } catch (NumberFormatException ignored) {}
        String flag = rt.getFlag("browser").orElse(System.getenv("PIJOTA_BROWSER")); // chrome (default)|firefox|safari
        this.browser = flag == null || flag.isBlank() ? "chrome" : flag.strip().toLowerCase();
        Path profile = Path.of(System.getProperty("user.home"), ".pijota", "browser", browser);
        String binary = rt.getFlag("chrome").orElse(rt.getFlag("driver").orElse(System.getenv("PIJOTA_CHROME")));
        try {
            this.session = BrowserSession.create(browser, port, profile, binary,
                                            msg -> rt.setStatus("browse", "🌐 " + msg), this::confirmLaunch);
        } catch (RuntimeException e) {
            // e.g. the selected browser's WebDriver isn't installed → disable browsing rather than fail startup. Print
            // to the console too: the extension status line isn't visible in the Telegram/web frontends, so otherwise
            // the failure looks silent (the browse tools just never appear).
            System.err.println("[browse] disabled for --browser=" + browser + ": " + e.getMessage());
            rt.setStatus("browse", "🌐 browse disabled: " + e.getMessage());
            return;
        }

        api.registerTool(new Tool.ToolDefinition("web_google_search",
                                        "Search the web (Google) in a real browser and return the top result links as markdown. "
                                                                        + "Use for finding pages; follow up with web_visit_page to read one.",
                                        oneArg("query", "The search query"),
                                        (id, args, cwd) -> run(() -> session.search(Util.getString(args, "query"))))
                                        .withGlyph("🌐"));

        api.registerTool(new Tool.ToolDefinition("web_visit_page",
                                        "Open a URL in a real browser (renders JavaScript, handles consent walls) and return the "
                                                                        + "page content as markdown.",
                                        oneArg("url", "The URL to open"),
                                        (id, args, cwd) -> run(() -> session.visit(Util.getString(args, "url"))))
                                        .withGlyph("🌐"));
    }

    @Override
    public void onShutdown(ExtensionRuntime runtime) {
        if (session != null)
            session.close();
    }

    /** Runs a browser action on a worker thread; the session confirms + launches Chrome lazily on first use. */
    private CompletableFuture<Tool.ToolResult> run(Callable<String> action) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String md = action.call(); // the session confirms + launches lazily on first use, serialized

                if (md == null || md.isBlank())
                    return Tool.ToolResult.success("(no content)");
                if (md.length() > Util.DEFAULT_MAX_CHARS) {
                    var t = Util.truncateToFile(md, Util.DEFAULT_MAX_CHARS, "pijota-browse-");
                    return Tool.ToolResult.success(t.text(), t.details());
                }
                return Tool.ToolResult.success(md);
            } catch (Exception e) {
                return Tool.ToolResult.error("Browser error: " + e.getMessage());
            }
        });
    }

    private boolean confirmLaunch() {
        String label = switch (browser) {
            case "firefox" -> "Firefox";
            case "safari" -> "Safari";
            default -> "Chrome";
        };
        try {
            return Boolean.TRUE.equals(api.runtime().ui()
                                            .confirm("Open a " + label + " window for pijota to browse the web?")
                                            .get(120, TimeUnit.SECONDS));
        } catch (Exception e) {
            return false;
        }
    }

    private static Map<String, Object> oneArg(String name, String description) {
        return Map.of("type", "object", "properties",
                                        Map.of(name, Map.of("type", "string", "description", description)), "required",
                                        List.of(name), "additionalProperties", false);
    }
}
