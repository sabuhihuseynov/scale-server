package com.scale;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.scale.config.AppConfig;
import com.scale.model.IndicatorType;
import com.scale.model.WeightReading;
import com.scale.serial.PortScanner;
import com.scale.serial.ScaleReader;
import com.scale.server.SseServer;
import com.scale.ui.StatusWindow;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import javafx.application.Platform;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.*;

/**
 * Application entry point.
 * <p>
 * Wiring:
 *   ScaleReader  →  (onWeight / onStatus callback)
 *       ↓
 *   StatusWindow  — live JavaFX status display for the operator
 *   SseServer.publish(JSON)  →  frontend app receives SSE events
 * <p>
 * Usage:
 *   java -jar scale-server-1.0.0-jar-with-dependencies.jar
 * <p>
 * Configuration (optional):
 *   Place scale.properties next to the JAR — see AppConfig for keys.
 *   Default: AUTO port scan, 9600 baud, CAS_V1, http port 8435.
 * <p>
 * SSE endpoint: http://localhost:8435/stream
 */
public final class Main {

    /** Single shared Gson instance — thread-safe after construction. */
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();
        setupLogging(config.debug);
        Logger log = Logger.getLogger(Main.class.getName());

        log.info("=".repeat(55));
        log.info("Weighbridge Scale Server  (single-client mode)");
        log.info("=".repeat(55));

        // ── Show config/status window; block until operator clicks Start ──────
        StatusWindow window = createWindow(config, log);
        if (window != null) {
            StatusWindow.Selection sel = window.awaitSelection();
            if (sel != null) {
                config = config.withUiSettings(sel.port(), sel.indicatorType(), sel.debug());
                setupLogging(config.debug);
            }
        }

        log.info("  " + config);
        log.info("=".repeat(55));

        // ── Resolve device name ───────────────────────────────────────────────
        String deviceName = config.deviceName.isEmpty()
                ? defaultDeviceName(config.indicatorType)
                : config.deviceName;

        // ── Resolve serial port ───────────────────────────────────────────────
        // If port = AUTO (default), scan all available ports and use the first
        // one that responds with a recognised scale packet.
        // If port is explicitly set (e.g. COM5), skip scanning and connect directly.
        String resolvedPort = resolvePort(config, log);

        log.info("=".repeat(55));
        log.info("  Port (resolved): " + resolvedPort);
        log.info("=".repeat(55));

        // ── Create components ─────────────────────────────────────────────────
        SseServer sseServer = new SseServer(config.httpPort);
        if (window != null) sseServer.onClientChange = window::updateClient;

        ScaleReader scaleReader = new ScaleReader(
                resolvedPort,
                config.baud,
                config.indicatorType,
                deviceName,
                config.scaleToKq
        );

        // ── Wire callbacks directly to SSE server ─────────────────────────────

        scaleReader.onWeight = reading -> {
            String json = buildWeightJson(reading, resolvedPort);
            sseServer.publish(json);
            log.fine("Weight published: " + reading);
        };

        scaleReader.onStatus = connected -> {
            if (window != null) window.updateScale(connected, resolvedPort);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type",      "status");
            payload.put("device",    deviceName);
            payload.put("port",      resolvedPort);
            payload.put("connected", connected);
            sseServer.publish(GSON.toJson(payload));
            log.info("Scale " + (connected ? "connected" : "disconnected")
                    + " on " + resolvedPort);
        };

        // ── Start SSE server first (so frontend can connect immediately) ───────
        try {
            sseServer.start();
        } catch (IOException e) {
            log.severe("Cannot bind HTTP port " + config.httpPort + ": " + e.getMessage());
            log.severe("Is another instance of this application already running?");
            System.exit(1);
        }

        // ── Start scale reader ────────────────────────────────────────────────
        scaleReader.start();

        log.info("SSE stream : http://localhost:" + config.httpPort + "/stream");
        log.info("Press Ctrl+C to stop.");

        // ── Graceful shutdown hook ─────────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Logger shutdownLog = Logger.getLogger("shutdown");
            shutdownLog.info("Shutdown signal received — stopping...");
            scaleReader.stop();
            sseServer.stop();
            shutdownLog.info("All components stopped. Goodbye.");
        }, "shutdown-hook"));

        // ── Park the main thread — all real work runs on daemon threads ────────
        Thread.currentThread().join();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Port resolution
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the serial port to use.
     * <p>
     * If {@code config.portName} is "AUTO" (the default), scans all available
     * serial ports using the configured indicator protocol until a scale is found.
     * The scan is retried every 5 seconds if no port responds.
     * <p>
     * If {@code config.portName} is a specific port name (e.g. "COM5"), it is
     * returned immediately without scanning.
     */
    private static String resolvePort(AppConfig config, Logger log) {
        if (!config.portName.equalsIgnoreCase("AUTO")) {
            log.info("Port explicitly configured: " + config.portName + " (skipping scan)");
            return config.portName;
        }

        log.info("Port=AUTO — scanning available serial ports for scale device...");
        String found = null;
        while (found == null) {
            found = PortScanner.scan(config.indicatorType, config.baud);
            if (found == null) {
                log.warning("No scale port found — retrying in 5 seconds...");
                try { Thread.sleep(5_000); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return found != null ? found : "UNKNOWN";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JSON helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static String buildWeightJson(WeightReading r, String port) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type",       "weight");
        payload.put("device",     r.device);
        payload.put("port",       port);
        payload.put("weight",     r.weight);
        payload.put("unit",       r.unit);
        payload.put("stable",     r.stable);
        payload.put("overload",   r.overload);
        payload.put("weightType", r.weightType);
        payload.put("timestamp",  r.timestamp);
        payload.put("lampFlags",  r.lampFlags);
        return GSON.toJson(payload);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Logging setup
    // ─────────────────────────────────────────────────────────────────────────

    private static void setupLogging(boolean debug) throws IOException {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.ALL);

        for (Handler existing : root.getHandlers()) {
            root.removeHandler(existing);
        }

        Formatter fmt = buildFormatter();

        ConsoleHandler console = new ConsoleHandler();
        console.setLevel(Level.INFO);
        console.setFormatter(fmt);
        root.addHandler(console);

        if (debug) {
            // Temporary debug log — FINE and above, overwritten on every run.
            // Contains raw byte traces and packet details useful during testing.
            FileHandler debugFile = new FileHandler("scale_debug.log", /* append */ false);
            debugFile.setLevel(Level.ALL);
            debugFile.setFormatter(fmt);
            root.addHandler(debugFile);
            Logger.getLogger(Main.class.getName()).info("Debug logging enabled → scale_debug.log");
        }

        Logger.getLogger("com.fazecast.jSerialComm").setLevel(Level.WARNING);
        // JavaFX warns about running from an unnamed module (fat JAR / classpath).
        // The app works correctly — this is a cosmetic advisory from the FX runtime.
        Logger.getLogger("javafx").setLevel(Level.SEVERE);
        Logger.getLogger("com.sun.javafx").setLevel(Level.SEVERE);
    }

    private static Formatter buildFormatter() {
        return new SimpleFormatter() {
            private static final DateTimeFormatter TS =
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            @Override
            public synchronized String format(LogRecord lr) {
                String name = lr.getLoggerName();
                String shortName = name.substring(name.lastIndexOf('.') + 1);
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("%s [%-7s] %-16s %s%n",
                        TS.format(LocalDateTime.now()),
                        lr.getLevel().getName(),
                        shortName,
                        formatMessage(lr)));
                if (lr.getThrown() != null) {
                    Throwable t = lr.getThrown();
                    sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
                    for (StackTraceElement e : t.getStackTrace())
                        sb.append("\tat ").append(e).append('\n');
                }
                return sb.toString();
            }
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────────────────────

    private static StatusWindow createWindow(AppConfig config, Logger log) {
        if (GraphicsEnvironment.isHeadless()) {
            log.info("No display available — running without status window");
            return null;
        }

        log.info("Opening status window...");
        try {
            CountDownLatch latch = new CountDownLatch(1);
            StatusWindow[] ref = new StatusWindow[1];
            Platform.startup(() -> {
                ref[0] = new StatusWindow(config);
                latch.countDown();
            });
            latch.await();
            return ref[0];
        } catch (Throwable t) {
            log.warning("Could not open status window: " + t.getMessage());
            if (log.isLoggable(Level.FINE)) {
                log.log(Level.FINE, "Status window error detail", t);
            }
            return null;
        }
    }

    private static String defaultDeviceName(IndicatorType type) {
        return switch (type) {
            case CAS_V1 -> "CAS NT-500 (v1)";
            case CAS_V2 -> "CAS NT-500 (v2)";
            case TYPE5  -> "Indicator Type 5";
        };
    }
}
