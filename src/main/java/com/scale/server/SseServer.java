package com.scale.server;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Raw-socket SSE server — replaces com.sun.net.httpserver which cannot
 * stream responses in JDK 21 (sendResponseHeaders closes body immediately).
 */
public final class SseServer {

    private static final Logger log = Logger.getLogger(SseServer.class.getName());
    private static final long HEARTBEAT_MS = 15_000L;

    private final int httpPort;
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    private final Object lock      = new Object();
    private String       latestPayload = null;
    private long         payloadSeq    = 0L;

    private volatile OutputStream activeClient = null;
    public  volatile Consumer<Boolean> onClientChange;

    public SseServer(int httpPort) { this.httpPort = httpPort; }

    // ── Publisher ─────────────────────────────────────────────────────────────
    public void publish(String json) {
        synchronized (lock) {
            latestPayload = json;
            payloadSeq++;
            lock.notifyAll();
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    public void start() throws IOException {
        running = true;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("127.0.0.1", httpPort));

        Thread acceptThread = new Thread(this::acceptLoop, "sse-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        log.info("HTTP server listening on 127.0.0.1:" + httpPort);
    }

    public void stop() {
        running = false;
        synchronized (lock) { lock.notifyAll(); }
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        log.info("HTTP server stopped");
    }

    // ── Accept loop ───────────────────────────────────────────────────────────
    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                Thread t = new Thread(() -> handleClient(client), "sse-handler");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) log.warning("Accept error: " + e.getMessage());
            }
        }
    }

    // ── Per-connection handler ────────────────────────────────────────────────
    private void handleClient(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            BufferedReader in  = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            OutputStream   out = socket.getOutputStream();

            // Read request line
            String requestLine = in.readLine();
            if (requestLine == null) return;

            // Read and discard request headers
            String h;
            while ((h = in.readLine()) != null && !h.isEmpty()) {}

            String method = requestLine.split(" ")[0];
            String path   = requestLine.split(" ").length > 1 ? requestLine.split(" ")[1] : "/";

            // CORS preflight
            if ("OPTIONS".equalsIgnoreCase(method)) {
                write(out,
                        "HTTP/1.1 204 No Content\r\n" +
                                "Access-Control-Allow-Origin: *\r\n" +
                                "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
                                "Access-Control-Allow-Headers: Content-Type\r\n" +
                                "Content-Length: 0\r\n\r\n");
                return;
            }

            if (path.startsWith("/stream")) {
                handleStream(out);
            } else {
                write(out,
                        "HTTP/1.1 404 Not Found\r\n" +
                                "Content-Length: 3\r\n\r\n404");
            }
        } catch (IOException ignored) {
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // ── SSE stream ────────────────────────────────────────────────────────────
    private void handleStream(OutputStream out) {
        // Close any previous client
        OutputStream prev = activeClient;
        if (prev != null) {
            log.info("New SSE client — closing previous connection");
            try { prev.close(); } catch (IOException ignored) {}
        }

        try {
            // Write SSE headers manually — no HttpServer involvement
            write(out,
                    "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/event-stream\r\n" +
                            "Cache-Control: no-cache\r\n" +
                            "Connection: keep-alive\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "X-Accel-Buffering: no\r\n" +
                            "\r\n");

            activeClient = out;
            notify(true);
            log.info("SSE client connected");

            // Send immediate comment to flush browser buffer
            writeEvent(out, ": connected\n\n");

            long lastSeq = 0L;

            while (running) {
                String data;
                long   newSeq;

                synchronized (lock) {
                    if (payloadSeq == lastSeq) {
                        try { lock.wait(HEARTBEAT_MS); }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    data   = (payloadSeq != lastSeq) ? latestPayload : null;
                    newSeq = payloadSeq;
                }

                if (data != null) {
                    writeEvent(out, "data: " + data + "\n\n");
                    lastSeq = newSeq;
                } else {
                    writeEvent(out, ": heartbeat\n\n");
                }
            }

        } catch (IOException e) {
            log.fine("SSE client disconnected: " + e.getMessage());
        } finally {
            activeClient = null;
            notify(false);
            log.info("SSE client session ended");
        }
    }

    private void writeEvent(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private void notify(boolean connected) {
        Consumer<Boolean> cb = onClientChange;
        if (cb != null) {
            try { cb.accept(connected); }
            catch (Exception e) { log.warning("onClientChange threw: " + e.getMessage()); }
        }
    }
}