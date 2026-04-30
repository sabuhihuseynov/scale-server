package com.scale.server;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Embedded HTTP server exposing an SSE endpoint for the frontend.
 * <p>
 * ─────────────────────────────────────────────────────────────────────────
 * Java 21 virtual threads — WHY they are ideal here:
 * <p>
 * SSE connections are long-lived and I/O-bound.  Each connected browser
 * tab holds open an HTTP connection and blocks inside Broadcaster.waitNext()
 * waiting for the next weight reading.
 * <p>
 * Platform threads (the old default):
 * - Each consumes ~1 MB of OS stack memory.
 * - The OS scheduler treats them as heavyweight tasks.
 * - 100 browser tabs = 100 MB just for thread stacks.
 * <p>
 * Virtual threads (Java 21 Project Loom):
 * - Start at ~few KB of heap memory; grown on demand.
 * - When a virtual thread blocks (e.g. Condition.await(), OutputStream.write()),
 * the underlying OS ("carrier") thread is immediately freed to run other
 * virtual threads.  No OS thread is wasted while we wait for a client.
 * - Creating/destroying them is cheap (JVM-managed, not OS-managed).
 * - 100 browser tabs might use only 2–4 OS carrier threads under the hood.
 * <p>
 * For our use case this is a perfect fit: the bottleneck is not CPU work
 * (we do almost none), it is blocking on I/O events.  Virtual threads make
 * the "one thread per SSE client" model essentially free.
 * <p>
 * ─────────────────────────────────────────────────────────────────────────
 * Routes:
 * GET  /stream   → SSE event stream  (text/event-stream)
 * GET  /health   → {"ok":true}       (application/json)
 * OPTIONS *      → CORS preflight    (204 No Content)
 * *              → 404
 * <p>
 * SSE event format:
 * data: <JSON>\n\n          — new weight or status reading
 * : heartbeat\n\n           — keep-alive comment every HEARTBEAT_MS
 */
public final class SseServer {

    private static final Logger log = Logger.getLogger(SseServer.class.getName());

    /**
     * Heartbeat interval — keeps TCP connections alive through proxies and firewalls.
     */
    private static final long HEARTBEAT_MS = 15_000L;

    /**
     * Health-check body — pre-computed once, reused for every /health request.
     */
    private static final byte[] HEALTH_BODY =
            "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);

    private final int httpPort;
    private final Broadcaster broadcaster;

    private HttpServer server;

    /**
     * Running flag that SSE handler threads check in their loop condition.
     * <p>
     * WHY volatile and not AtomicBoolean?
     * We only ever write it from stop() (one writer, many readers).
     * volatile guarantees visibility across threads; the CAS overhead of
     * AtomicBoolean is unnecessary for a simple boolean flag.
     */
    private volatile boolean serverRunning = false;

    /**
     * Counter shown in log messages and optionally in a future admin UI.
     */
    private final AtomicInteger clientCount = new AtomicInteger(0);

    public SseServer(int httpPort, Broadcaster broadcaster) {
        this.httpPort = httpPort;
        this.broadcaster = broadcaster;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Bind the TCP port and start accepting HTTP connections.
     * Throws IOException if the port is already in use (another instance running).
     */
    public void start() throws IOException {
        serverRunning = true;

        server = HttpServer.create(new InetSocketAddress("0.0.0.0", httpPort), 64);

        // ── Virtual-thread executor ────────────────────────────────────────
        // Executors.newVirtualThreadPerTaskExecutor() is the Java 21 standard
        // factory that assigns one virtual thread per submitted Runnable.
        //
        // The HttpServer calls executor.execute(handler) for each HTTP request.
        // With this executor, each incoming request (including long-lived SSE
        // connections) gets its own virtual thread — cheap to create, cheap to
        // block on I/O, and automatically cleaned up when the handler returns.
        //
        // Named factory via Thread.ofVirtual().name("sse-vt-", 0).factory():
        //   Names threads "sse-vt-0", "sse-vt-1", … so they appear with
        //   meaningful names in thread dumps and profilers.
        server.setExecutor(
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual()
                                .name("sse-vt-", 0)   // sequential names: sse-vt-0, sse-vt-1, …
                                .factory()
                )
        );

        server.createContext("/stream", this::handleStream);
        server.createContext("/health", this::handleHealth);
        server.createContext("/", this::handleNotFound);

        server.start();
        log.info("SSE server started on http://0.0.0.0:" + httpPort);
        log.info("  Stream : http://localhost:" + httpPort + "/stream");
        log.info("  Health : http://localhost:" + httpPort + "/health");
    }

    /**
     * Stop accepting new connections and wait at most 1 second for in-flight handlers.
     */
    public void stop() {
        serverRunning = false;   // SSE loops will see this and exit cleanly
        if (server != null) {
            server.stop(1);
            log.info("SSE server stopped");
        }
    }

    /**
     * Number of currently connected SSE clients.
     */
    public int clientCount() {
        return clientCount.get();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Route handlers
    // ─────────────────────────────────────────────────────────────────────────

    // ── GET /health ───────────────────────────────────────────────────────────

    private void handleHealth(HttpExchange ex) throws IOException {
        if (isCorsPreflightRequest(ex)) {
            sendCorsOptions(ex);
            return;
        }
        Headers h = ex.getResponseHeaders();
        addCorsHeaders(h);
        h.set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, HEALTH_BODY.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(HEALTH_BODY);
        }
    }

    // ── GET /stream (SSE) ─────────────────────────────────────────────────────

    /**
     * Long-lived SSE handler — one virtual thread per connected client.
     * <p>
     * sendResponseHeaders(200, -1): the -1 content-length puts the response into
     * chunked-transfer mode so the client receives each chunk as we flush it.
     */
    private void handleStream(HttpExchange ex) throws IOException {
        if (isCorsPreflightRequest(ex)) {
            sendCorsOptions(ex);
            return;
        }

        Headers h = ex.getResponseHeaders();
        addCorsHeaders(h);
        h.set("Content-Type", "text/event-stream");
        h.set("Cache-Control", "no-cache");
        h.set("Connection", "keep-alive");
        h.set("X-Accel-Buffering", "no");  // disable Nginx response buffering if present

        ex.sendResponseHeaders(200, -1);   // -1 = chunked streaming, no fixed content-length

        int total = clientCount.incrementAndGet();
        log.info("SSE client connected (total=" + total + ") from " + ex.getRemoteAddress());

        // ── SSE event loop ────────────────────────────────────────────────
        // This is the core of the SSE connection: block until new data (or
        // timeout), write the event, flush, repeat.
        //
        // The virtual thread running this method parks efficiently inside
        // Broadcaster.waitNext() (on a Condition.await()) without occupying
        // a real OS thread — that's the key advantage of virtual threads here.
        try (OutputStream os = ex.getResponseBody()) {
            long seq = 0L;
            boolean connected = true;

            while (connected && serverRunning) {

                // Block until broadcaster has new data or HEARTBEAT_MS elapses.
                // When the virtual thread parks here, the OS carrier thread is
                // freed to run other virtual threads (other SSE clients, etc.).
                Broadcaster.WaitResult result;
                try {
                    result = broadcaster.waitNext(seq, HEARTBEAT_MS);
                } catch (InterruptedException e) {
                    // Server is shutting down — the thread was interrupted by stop().
                    // Restore the interrupt flag and exit the loop cleanly.
                    Thread.currentThread().interrupt();
                    connected = false;
                    continue;   // re-check while condition → exits
                }

                // Prepare the SSE bytes to write.
                // SSE wire format: "data: <payload>\n\n" or ": <comment>\n\n"
                byte[] event;
                if (result.payload() == null) {
                    // Timeout — no new data in HEARTBEAT_MS.
                    // Send an SSE comment to prevent proxy/browser from closing
                    // the connection thinking the server went away.
                    // Comments are ignored by EventSource but keep the TCP alive.
                    event = ": heartbeat\n\n".getBytes(StandardCharsets.UTF_8);
                } else {
                    // New weight or status reading — send as SSE data event.
                    // The double newline (\n\n) is the SSE event terminator.
                    event = ("data: " + result.payload() + "\n\n")
                            .getBytes(StandardCharsets.UTF_8);
                    seq = result.seq();   // advance our cursor to this sequence number
                }

                // Write and flush — IOException here means the client closed
                // the connection (tab closed, browser navigated away, network drop).
                try {
                    os.write(event);
                    os.flush();   // CRITICAL: flush every event individually
                } catch (IOException e) {
                    // Client disconnected — this is the normal exit for SSE.
                    // Setting connected=false exits the while loop without
                    // re-throwing, giving us a clean shutdown path.
                    connected = false;
                }
            }
            // Loop exited normally (either client disconnected or server stopped).
            // The try-with-resources block closes the OutputStream here.
        }

        int remaining = clientCount.decrementAndGet();
        log.info("SSE client disconnected (total=" + remaining + ")");
    }

    // ── 404 catch-all ─────────────────────────────────────────────────────────

    private void handleNotFound(HttpExchange ex) throws IOException {
        if (isCorsPreflightRequest(ex)) {
            sendCorsOptions(ex);
            return;
        }
        addCorsHeaders(ex.getResponseHeaders());
        ex.sendResponseHeaders(404, 0);
        ex.getResponseBody().close();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CORS helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Add permissive CORS headers so any local or remote frontend can subscribe.
     * <p>
     * In production you may want to replace "*" with the specific frontend origin.
     */
    private static void addCorsHeaders(Headers h) {
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static boolean isCorsPreflightRequest(HttpExchange ex) {
        return "OPTIONS".equalsIgnoreCase(ex.getRequestMethod());
    }

    private static void sendCorsOptions(HttpExchange ex) throws IOException {
        addCorsHeaders(ex.getResponseHeaders());
        ex.sendResponseHeaders(204, -1);
        ex.getResponseBody().close();
    }
}
