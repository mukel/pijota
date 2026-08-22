package org.pijota.extensions.examples;

import org.pijota.browser.BrowserSession;
import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.ExtensionRuntime;
import org.pijota.tools.Tool;
import org.pijota.util.Env;
import org.pijota.util.PijotaHome;
import org.pijota.util.Util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in web browsing through a real browser, the ds4 approach. Off by default; enable with {@code --browse}. Exposes
 * three tools for search, rendered pages, and PDFs. The browser uses a dedicated persistent profile under Pijota's
 * config directory, is launched lazily on first use, asks before opening a visible window, is reused across the
 * session, and is closed on shutdown.
 */
public final class WebBrowseExtension implements Extension {
    private static final Logger logger = LoggerFactory.getLogger(WebBrowseExtension.class);

    private static final int DEFAULT_PORT = 9333;

    private ExtensionAPI api;
    private BrowserSession session;
    private String browser = "chrome"; // selected browser, for the launch-confirmation message
    private volatile boolean browserApproved; // once the user approves, re-launches after idle-close skip the prompt

    @Override
    public String name() {
        return "browse";
    }

    @Override
    public Set<String> additionalFlagNames() {
        return Set.of("browse-headless", "browse-port");
    }

    @Override
    public boolean enabledByDefault() {
        return false; // opt-in: --browse[=chrome|firefox|safari]
    }

    @Override
    public String description() {
        return "Opt-in web browsing via a real browser (web_google_search, web_visit_page). Enable with --browse; "
                                        + "select the engine with --browse=chrome|firefox|safari (default chrome).";
    }

    @Override
    public void init(ExtensionAPI api) {
        ExtensionRuntime rt = api.runtime();
        this.api = api;
        // --browse[=chrome|firefox|safari]: the flag's value selects the engine; a bare --browse falls back to the
        // PIJOTA_BROWSER env var, then chrome.
        String engine = rt.getFlag("browse").filter(v -> !v.isBlank() && !v.equals("true"))
                                        .orElse(Env.get("pijota.browse.engine", "PIJOTA_BROWSER"));
        this.browser = engine == null || engine.isBlank() ? "chrome" : engine.strip().toLowerCase();
        boolean headless = headless(rt, browser, noDisplay());
        Path profile = PijotaHome.resolve("browser", browser);
        String binary = Env.get("pijota.browse.bin", "PIJOTA_BROWSER_BIN"); // optional path override; else
                                                                            // auto-detected
        try {
            this.session = BrowserSession.create(browser, debugPort(rt), profile, binary, this::setBrowseStatus,
                                            this::confirmLaunch, headless);
        } catch (RuntimeException e) {
            // e.g. the selected browser's WebDriver isn't installed → disable browsing rather than fail startup. Log
            // and surface via status: the extension status line isn't visible in the Telegram/web frontends, so
            // otherwise
            // the failure looks silent (the browse tools just never appear).
            logger.warn("Browse disabled for --browse={}: {}", browser, e.getMessage());
            rt.setStatus("browse", "🌐 browse disabled: " + e.getMessage());
            return;
        }

        api.registerTool(new Tool.ToolDefinition("web_google_search",
                                        "Search the web (Google) in a real browser and return the top result links as markdown. "
                                                                        + "Use for finding pages; follow up with web_visit_page to read one.",
                                        oneArg("query", "The search query"),
                                        (id, args, cwd) -> run(() -> session.search(args.getString("query"))))
                                        .withGlyph("🌐"));

        api.registerTool(new Tool.ToolDefinition("web_visit_page",
                                        "Open a URL in a real browser (renders JavaScript, handles consent walls) and return the "
                                                                        + "page content as markdown.",
                                        oneArg("url", "The URL to open"),
                                        (id, args, cwd) -> run(() -> session.visit(args.getString("url"))))
                                        .withGlyph("🌐"));

        api.registerTool(new Tool.ToolDefinition("web_pdf_to_markdown",
                                        "Extract a local PDF's text as markdown using the browser's PDF.js. Digital (text-layer) "
                                                                        + "PDFs only. Scanned/image-only PDFs have no text layer and yield little.",
                                        oneArg("path", "Path to the .pdf file, relative to the workspace"),
                                        (id, args, cwd) -> run(() -> session.extractPdf(
                                                                        readPdfBase64(cwd, args.getString("path")))))
                                        .withGlyph("📄"));
    }

    private static String readPdfBase64(String cwd, String path) throws IOException {
        Path p = Path.of(cwd).resolve(path).normalize();
        return Base64.getEncoder().encodeToString(Files.readAllBytes(p));
    }

    @Override
    public void onShutdown(ExtensionRuntime runtime) {
        if (session != null)
            session.close();
        runtime.clearStatus("browse");
    }

    private void setBrowseStatus(String message) {
        api.runtime().setStatus("browse", "🌐 " + message);
    }

    /** Linux has no standard desktop browser display without X11 or Wayland; Windows and macOS provide one. */
    static boolean noDisplay() {
        return noDisplay(org.pijota.util.Platform.isWindows() || org.pijota.util.Platform.isMac(),
                                        System.getenv("DISPLAY"), System.getenv("WAYLAND_DISPLAY"));
    }

    static boolean noDisplay(boolean desktopOs, String display, String waylandDisplay) {
        return !desktopOs && (display == null || display.isBlank())
                                        && (waylandDisplay == null || waylandDisplay.isBlank());
    }

    /** Safari is visible-only; the headless flag applies only to Chrome and Firefox. */
    static boolean headless(ExtensionRuntime rt, String browser, boolean noDisplay) {
        return !"safari".equals(browser) && rt.getFlag("browse-headless", noDisplay);
    }

    /**
     * The browser's remote-debugging port. A free port is auto-picked so the user never has to think about it; the
     * undocumented {@code browse-port} override pins a specific one, used by tests to attach to a fake browser, and to
     * attach to an already-running instance.
     */
    private static int debugPort(ExtensionRuntime rt) {
        var override = rt.getFlag("browse-port");
        if (override.isPresent())
            try {
                return Integer.parseInt(override.get().trim());
            } catch (NumberFormatException ignored) {}
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            return DEFAULT_PORT;
        }
    }

    /** Runs a browser action on a worker thread; the session confirms + launches Chrome lazily on first use. */
    private CompletableFuture<Tool.ToolResult> run(Callable<String> action) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String md = action.call();

                if (md == null || md.isBlank())
                    return Tool.ToolResult.success("(no content)");
                if (md.length() > Util.DEFAULT_MAX_CHARS) {
                    var t = Util.truncateToFile(md, Util.DEFAULT_MAX_CHARS, "pijota-browse-");
                    return Tool.ToolResult.success(t.text(), t.details());
                }
                return Tool.ToolResult.success(md);
            } catch (Exception e) {
                return Tool.ToolResult.error("Browser error: " + e.getMessage());
            } finally {
                api.runtime().clearStatus("browse");
            }
        });
    }

    private boolean confirmLaunch() {
        if (browserApproved) // approved earlier this session → re-launch after an idle close without re-prompting
            return true;
        String label = switch (browser) {
            case "firefox" -> "Firefox";
            case "safari" -> "Safari";
            default -> "Chrome";
        };
        try {
            boolean ok = Boolean.TRUE.equals(api.runtime().ui()
                                            .confirm("Open a " + label + " window for pijota to browse the web?")
                                            .get(120, TimeUnit.SECONDS));
            browserApproved = ok;
            return ok;
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
