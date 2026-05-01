package com.scale.server;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Embedded HTTP server exposing a single SSE endpoint for the frontend app.
 * <p>
 * ─────────────────────────────────────────────────────────────────────────
 * SINGLE-CLIENT DESIGN
 * <p>
 * /stream is reserved for one frontend application at a time.
 * A new connection silently closes the previous one.
 * <p>
 * Why is the lock needed with only one client?
 * The lock coordinates two threads, not two clients:
 *   (1) ScaleReader callback thread  → calls publish()
 *   (2) SSE HTTP handler thread      → blocks in wait() for new data
 * Without synchronization the handler could miss a notify() or read a
 * stale payloadSeq.  This is standard producer-consumer; client count
 * is irrelevant.
 * <p>
 * ─────────────────────────────────────────────────────────────────────────
 * Routes:
 *   GET  /stream   → SSE event stream (for the frontend app only)
 *   OPTIONS *      → CORS preflight (204)
 *   *              → 404
 */
public final class SseServer {

    private static final Logger log = Logger.getLogger(SseServer.class.getName());

    private static final long HEARTBEAT_MS = 15_000L;

    private final int httpPort;
    private HttpServer server;
    private volatile boolean running = false;

    // ── Single-slot pub/sub ──────────────────────────────────────────────────
    private final Object lock         = new Object();
    private String       latestPayload = null;
    private long         payloadSeq    = 0L;

    // ── Active SSE client ────────────────────────────────────────────────────
    private volatile OutputStream activeClient = null;

    // ── Callback — fired when the frontend client connects / disconnects ──────
    public volatile Consumer<Boolean> onClientChange;

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────────────

    public SseServer(int httpPort) {
        this.httpPort = httpPort;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Publisher (called from ScaleReader callback thread)
    // ─────────────────────────────────────────────────────────────────────────

    public void publish(String json) {
        synchronized (lock) {
            latestPayload = json;
            payloadSeq++;
            lock.notifyAll();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    public void start() throws IOException {
        running = true;

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", httpPort), 4);
        server.createContext("/stream", this::handleStream);
        server.createContext("/",       this::handleNotFound);

        server.setExecutor(runnable -> {
            Thread t = new Thread(runnable, "sse-handler");
            t.setDaemon(true);
            t.start();
        });

        server.start();
        log.info("HTTP server listening on 127.0.0.1:" + httpPort);
    }

    public void stop() {
        running = false;
        synchronized (lock) { lock.notify(); }
        if (server != null) { server.stop(1); }
        log.info("HTTP server stopped");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Route handlers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * GET /stream — SSE event stream for the frontend application.
     * Only one connection at a time; a new request closes the previous one.
     */
    private void handleStream(HttpExchange ex) throws IOException {
        if (isCorsPreflightRequest(ex)) { sendCorsOptions(ex); return; }

        OutputStream prev = activeClient;
        if (prev != null) {
            log.info("New SSE client — closing previous connection");
            try { prev.close(); } catch (IOException ignored) { }
        }

        Headers h = ex.getResponseHeaders();
        addCorsHeaders(h);
        h.set("Content-Type",      "text/event-stream");
        h.set("Cache-Control",     "no-cache");
        h.set("Connection",        "keep-alive");
        h.set("X-Accel-Buffering", "no");

        ex.sendResponseHeaders(200, -1);
        log.info("SSE client connected from " + ex.getRemoteAddress());

        try (OutputStream os = ex.getResponseBody()) {
            activeClient = os;
            notifyClientChange(true);
            long lastSeq = 0L;

            while (running) {
                String data;
                long   newSeq;

                synchronized (lock) {
                    if (payloadSeq == lastSeq) {
                        try {
                            lock.wait(HEARTBEAT_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    data   = (payloadSeq != lastSeq) ? latestPayload : null;
                    newSeq = payloadSeq;
                }

                byte[] event;
                if (data == null) {
                    event = ": heartbeat\n\n".getBytes(StandardCharsets.UTF_8);
                } else {
                    event = ("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8);
                    lastSeq = newSeq;
                }

                os.write(event);
                os.flush();
            }

        } catch (IOException e) {
            log.fine("SSE client disconnected: " + e.getMessage());
        } finally {
            activeClient = null;
            notifyClientChange(false);
            log.info("SSE client session ended");
        }
    }

    private void handleNotFound(HttpExchange ex) throws IOException {
        if (isCorsPreflightRequest(ex)) { sendCorsOptions(ex); return; }
        byte[] body = "404 Not Found\n".getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(404, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void notifyClientChange(boolean connected) {
        Consumer<Boolean> cb = onClientChange;
        if (cb != null) {
            try {
                cb.accept(connected);
            } catch (Exception e) {
                log.warning("onClientChange callback threw: " + e.getMessage());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CORS helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static boolean isCorsPreflightRequest(HttpExchange ex) {
        return "OPTIONS".equalsIgnoreCase(ex.getRequestMethod());
    }

    private static void addCorsHeaders(Headers h) {
        h.set("Access-Control-Allow-Origin",  "*");
        h.set("Access-Control-Allow-Methods", "GET, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void sendCorsOptions(HttpExchange ex) throws IOException {
        addCorsHeaders(ex.getResponseHeaders());
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }
}
