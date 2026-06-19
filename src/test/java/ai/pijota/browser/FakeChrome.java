package ai.pijota.browser;

import ai.pijota.util.Util;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fake Chrome DevTools endpoint for tests: the {@code /json*} HTTP surface plus a minimal CDP WebSocket server, so
 * the real {@link BrowserSession} can be driven end-to-end without launching a browser. Runtime.evaluate is scripted by
 * inspecting the expression (readyState, location.href, the extraction scripts, etc.).
 */
public final class FakeChrome implements AutoCloseable {

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final HttpServer http;
    private final ServerSocket wsServer;
    private final int httpPort, wsPort;
    private final Thread acceptThread;
    private volatile boolean running = true;
    private volatile Socket client;

    // ---- scripted state (mutated by tests) ----
    public volatile String currentUrl = "about:blank";
    public volatile String navigateError = ""; // non-empty → Page.navigate returns this errorText
    public volatile String pageExtract = "# Fake Page\n\nhttps://x/\n\nhello world\n- [link](https://link/)";
    public volatile String searchExtract = "## Results\n- [r1](https://r1/)\n\n## Page text\nsnippet";
    public volatile String readerJson = null; // JSON for the reader-mode article, or null → not an article (fallback)
    public final AtomicInteger evaluateCount = new AtomicInteger();
    public final AtomicInteger navigateCount = new AtomicInteger();

    public FakeChrome() throws IOException {
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        httpPort = http.getAddress().getPort();
        wsServer = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
        wsPort = wsServer.getLocalPort();

        http.createContext("/json/version", ex -> reply(ex, "{\"Browser\":\"FakeChrome/1.0\"}"));
        http.createContext("/json", ex -> {
            String path = ex.getRequestURI().getPath();
            reply(ex, path.startsWith("/json/new") ? target() : "[" + target() + "]");
        });
        http.start();

        acceptThread = new Thread(this::acceptLoop, "fake-chrome-ws");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public int port() {
        return httpPort;
    }

    /** Force-drops the current CDP socket so the session must reconnect on its next call. */
    public void dropConnection() throws IOException {
        Socket c = client;
        if (c != null)
            c.close();
    }

    private String target() {
        return "{\"type\":\"page\",\"title\":\"t\",\"url\":\"" + currentUrl + "\",\"webSocketDebuggerUrl\":\"ws://"
                                        + InetAddress.getLoopbackAddress().getHostAddress() + ":" + wsPort
                                        + "/devtools/page/T\"}";
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    // ---- WebSocket server ----

    private void acceptLoop() {
        while (running) {
            try (Socket s = wsServer.accept()) {
                client = s;
                serve(s);
            } catch (IOException e) {
                // server closed or client dropped → loop to accept the next (reconnect) until shut down
            }
        }
    }

    private void serve(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();
        handshake(in, out);
        String msg;
        while ((msg = readFrame(in)) != null)
            for (String frame : respond(msg)) // reply, then any server-initiated events (e.g. Page.loadEventFired)
                writeFrame(out, frame);
    }

    private static void handshake(InputStream in, OutputStream out) throws IOException {
        StringBuilder req = new StringBuilder();
        int prev = 0, ch;
        while ((ch = in.read()) != -1) {
            req.append((char) ch);
            if (prev == '\n' && ch == '\n')
                break; // blank line ends headers (\r\n\r\n; we also tolerate \n\n)
            if (ch != '\r')
                prev = ch;
        }
        String key = req.toString().lines().filter(l -> l.toLowerCase().startsWith("sec-websocket-key:")).findFirst()
                                        .map(l -> l.substring(l.indexOf(':') + 1).trim()).orElseThrow();
        String accept;
        try {
            byte[] sha = MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).getBytes(StandardCharsets.UTF_8));
            accept = Base64.getEncoder().encodeToString(sha);
        } catch (Exception e) {
            throw new IOException(e);
        }
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                                        + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n")
                                        .getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Reads one client message (accumulating continuation frames until FIN); returns null on close/EOF. */
    private static String readFrame(InputStream in) throws IOException {
        var msg = new java.io.ByteArrayOutputStream();
        int firstOpcode = -1;
        while (true) {
            int b0 = in.read();
            if (b0 == -1)
                return null;
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            if (opcode == 0x8)
                return null; // close frame
            if (firstOpcode == -1)
                firstOpcode = opcode;
            int b1 = in.read();
            if (b1 == -1)
                return null;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126)
                len = ((long) in.read() << 8) | in.read();
            else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++)
                    len = (len << 8) | in.read();
            }
            byte[] mask = new byte[4];
            if (masked)
                in.readNBytes(mask, 0, 4);
            byte[] payload = in.readNBytes((int) len);
            if (masked)
                for (int i = 0; i < payload.length; i++)
                    payload[i] ^= mask[i & 3];
            msg.write(payload);
            if (fin)
                break;
        }
        return firstOpcode == 0x1 ? msg.toString(StandardCharsets.UTF_8) : "";
    }

    private static void writeFrame(OutputStream out, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        out.write(0x81); // FIN + text
        if (payload.length < 126)
            out.write(payload.length);
        else if (payload.length < 65536) {
            out.write(126);
            out.write((payload.length >> 8) & 0xFF);
            out.write(payload.length & 0xFF);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--)
                out.write((int) ((long) payload.length >> (8 * i)) & 0xFF);
        }
        out.write(payload);
        out.flush();
    }

    // ---- CDP JSON-RPC scripting ----

    private java.util.List<String> respond(String json) {
        try {
            JsonNode n = Util.MAPPER.readTree(json);
            int id = n.path("id").asInt();
            String method = n.path("method").asText();
            JsonNode params = n.path("params");
            ObjectNode reply = Util.MAPPER.createObjectNode();
            reply.put("id", id);
            ObjectNode result = reply.putObject("result");
            switch (method) {
                case "Page.navigate" -> {
                    navigateCount.incrementAndGet();
                    result.put("frameId", "F");
                    if (!navigateError.isEmpty())
                        result.put("errorText", navigateError);
                    else
                        currentUrl = params.path("url").asText(currentUrl);
                }
                case "Runtime.evaluate" -> {
                    evaluateCount.incrementAndGet();
                    result.putObject("result").set("value", scriptValue(params.path("expression").asText()));
                }
                default -> {} // Page.enable / Runtime.enable / Emulation.* → empty result
            }
            String replyJson = Util.MAPPER.writeValueAsString(reply);
            // Real Chrome fires Page.loadEventFired once the navigated document finishes loading; CdpBackend.navigate
            // now blocks on it, so the fake must send it too (after the navigate reply).
            if ("Page.navigate".equals(method) && navigateError.isEmpty())
                return java.util.List.of(replyJson,
                                                "{\"method\":\"Page.loadEventFired\",\"params\":{\"timestamp\":0}}");
            return java.util.List.of(replyJson);
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    /** Mimics a Runtime.evaluate return value by matching the injected expression (specific patterns first). */
    private JsonNode scriptValue(String expr) {
        var nf = Util.MAPPER.getNodeFactory();
        if (expr.contains("__pijota_reader__")) { // Mozilla Readability injection → article object or null
            if (readerJson == null)
                return nf.nullNode();
            try {
                return Util.MAPPER.readTree(readerJson);
            } catch (Exception e) {
                return nf.nullNode();
            }
        }
        if (expr.contains("scrollBy") || expr.contains("scrollTo"))
            return nf.numberNode(0);
        if (expr.contains("a h3")) // extract-search.js
            return nf.textNode(searchExtract);
        if (expr.contains("getBoundingClientRect")) // extract-page.js
            return nf.textNode(pageExtract);
        if (expr.contains("role=button")) // dismiss-consent.js
            return nf.textNode("");
        if (expr.contains("outerHTML"))
            return nf.textNode("<html><body><p>fallback</p></body></html>");
        if (expr.contains("location.href"))
            return nf.textNode(currentUrl);
        if (expr.contains("readyState"))
            return nf.textNode("complete");
        if (expr.contains("scrollHeight"))
            return nf.numberNode(100); // ≤ innerHeight*1.35 → adaptive scroll is skipped
        if (expr.contains("innerHeight"))
            return nf.numberNode(900);
        if (expr.contains("innerText") && expr.contains("length"))
            return nf.numberNode(42); // stable → settle loop converges quickly
        if (expr.contains("document.title"))
            return nf.textNode("Fake Title");
        return nf.textNode("");
    }

    @Override
    public void close() {
        running = false;
        http.stop(0);
        try {
            wsServer.close();
        } catch (IOException ignored) {}
        Socket c = client;
        if (c != null)
            try {
                c.close();
            } catch (IOException ignored) {}
    }
}
