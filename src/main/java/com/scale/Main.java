package com.scale;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.scale.config.AppConfig;
import com.scale.model.IndicatorType;
import com.scale.model.WeightReading;
import com.scale.serial.ScaleReader;
import com.scale.server.Broadcaster;
import com.scale.server.SseServer;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.*;

/**
 * Application entry point.
 *
 * Wiring:
 *   ScaleReader  →  (onWeight / onStatus callback)
 *       ↓
 *   Broadcaster.publish(JSON)
 *       ↓
 *   SseServer   →  connected browser tabs receive SSE events
 *
 * Usage:
 *   java -jar scale-server-1.0.0-jar-with-dependencies.jar
 *
 * Configuration (optional):
 *   Place scale.properties next to the JAR — see AppConfig for keys.
 *   Default: COM3, 9600 baud, CAS_V1, http port 8080.
 *
 * SSE endpoint:
 *   http://localhost:8080/stream
 *
 * Health check:
 *   http://localhost:8080/health
 */
public final class Main {

    /** Single shared Gson instance — thread-safe after construction. */
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    public static void main(String[] args) throws Exception {
        setupLogging();
        Logger log = Logger.getLogger(Main.class.getName());

        // ── Load config ───────────────────────────────────────────────────────
        AppConfig config = AppConfig.load();

        log.info("=".repeat(55));
        log.info("Weighbridge Scale Server");
        log.info("  " + config);
        log.info("=".repeat(55));

        // ── Resolve device name ───────────────────────────────────────────────
        String deviceName = config.deviceName.isEmpty()
                ? defaultDeviceName(config.indicatorType)
                : config.deviceName;

        // ── Create components ─────────────────────────────────────────────────
        Broadcaster broadcaster = new Broadcaster();
        SseServer   sseServer   = new SseServer(config.httpPort, broadcaster);

        ScaleReader scaleReader = new ScaleReader(
                config.portName,
                config.baud,
                config.indicatorType,
                deviceName,
                config.scaleToKq
        );

        // ── Wire callbacks ─────────────────────────────────────────────────────

        scaleReader.onWeight = reading -> {
            // Publish weight event to all SSE clients
            String json = buildWeightJson(reading);
            broadcaster.publish(json);
            log.fine("Weight published: " + reading);
        };

        scaleReader.onStatus = connected -> {
            // Publish connection status change
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type",      "status");
            payload.put("device",    deviceName);
            payload.put("connected", connected);
            broadcaster.publish(GSON.toJson(payload));
            log.info("Scale " + (connected ? "connected" : "disconnected"));
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
        log.info("Health     : http://localhost:" + config.httpPort + "/health");
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
        // main() itself doesn't loop; we just wait for a shutdown signal.
        Thread.currentThread().join();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JSON helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the SSE "weight" event JSON.
     * Using LinkedHashMap for stable key ordering in the JSON output.
     */
    private static String buildWeightJson(WeightReading r) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type",       "weight");
        payload.put("device",     r.device);
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

    /**
     * Configure JUL (java.util.logging) with:
     *   - Console handler:  INFO and above, clean one-line format
     *   - File handler:     ALL levels, same format, appended to scale_server.log
     */
    private static void setupLogging() throws IOException {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.ALL);

        // Remove the default handler (it uses a verbose format and goes to stderr)
        for (Handler existing : root.getHandlers()) {
            root.removeHandler(existing);
        }

        Formatter fmt = buildFormatter();

        ConsoleHandler console = new ConsoleHandler();
        console.setLevel(Level.INFO);
        console.setFormatter(fmt);
        root.addHandler(console);

        String logPath = "scale_server.log";
        FileHandler file = new FileHandler(logPath, /* append */ true);
        file.setLevel(Level.ALL);
        file.setFormatter(fmt);
        root.addHandler(file);

        // Suppress noisy jSerialComm library logs below WARNING
        Logger.getLogger("com.fazecast.jSerialComm").setLevel(Level.WARNING);

        Logger.getLogger(Main.class.getName())
              .info("Log file: " + new File(logPath).getAbsolutePath());
    }

    /** Single-line log format: "2025-06-01 12:34:56 [INFO   ] CommPort - message" */
    private static Formatter buildFormatter() {
        return new SimpleFormatter() {
            private static final DateTimeFormatter TS =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            @Override
            public synchronized String format(LogRecord lr) {
                // Shorten logger name: "com.scale.serial.CommPort" → "CommPort"
                String name = lr.getLoggerName();
                int dot = name.lastIndexOf('.');
                String shortName = (dot >= 0) ? name.substring(dot + 1) : name;

                String msg = formatMessage(lr);
                if (lr.getThrown() != null) {
                    msg += "\n" + throwableToString(lr.getThrown());
                }

                return String.format("%s [%-7s] %-16s %s%n",
                        TS.format(LocalDateTime.now()),
                        lr.getLevel().getName(),
                        shortName,
                        msg);
            }

            private String throwableToString(Throwable t) {
                StringBuilder sb = new StringBuilder();
                sb.append(t.getClass().getName()).append(": ").append(t.getMessage());
                for (StackTraceElement e : t.getStackTrace()) {
                    sb.append("\n\tat ").append(e);
                }
                return sb.toString();
            }
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────────────────────

    private static String defaultDeviceName(IndicatorType type) {
        return switch (type) {
            case CAS_V1 -> "CAS NT-500 (v1)";
            case CAS_V2 -> "CAS NT-500 (v2)";
            case TYPE5  -> "Indicator Type 5";
        };
    }
}
