package com.scale.config;

import com.scale.model.IndicatorType;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Application configuration.
 * Loads from {@code scale.properties} if found next to the JAR,
 * otherwise uses sensible defaults.
 * <p>
 * ─────────────────────────────────────────────────────────────────────────
 * scale.properties keys:
 * <p>
 * port            = AUTO          Serial port (COMx or /dev/ttyUSBx).
 *                                 AUTO (default) scans all available ports at startup
 *                                 and uses the first one that responds with scale data.
 *                                 Set to a specific port (e.g. COM5) to skip scanning.
 * baud            = 9600          Baud rate
 * indicator.type  = 1             Protocol type: 1=CAS_V1, 2=CAS_V2, 5=TYPE5
 * scale.to.kq     = 1.0           Weight multiplier (1.0 = already in kg)
 * http.port       = 8435          TCP port for the SSE HTTP server
 * device.name     =               Optional human-readable device label
 * debug           = false         true = write FINE-level byte/packet traces to
 *                                 scale_debug.log (overwritten each run, not permanent)
 * ─────────────────────────────────────────────────────────────────────────
 */
public final class AppConfig {

    private static final Logger log = Logger.getLogger(AppConfig.class.getName());

    /**
     * File name to look for next to the running JAR.
     */
    public static final String PROPS_FILE = "scale.properties";

    // ── Parsed fields ─────────────────────────────────────────────────────────

    /**
     * Serial port name, e.g. "COM3" or "/dev/ttyUSB0".
     */
    public final String portName;

    /**
     * Baud rate — must match the physical scale setting (typically 9600).
     */
    public final int baud;

    /**
     * Protocol variant — determines poll command and packet parser.
     */
    public final IndicatorType indicatorType;

    /**
     * Weight multiplier applied after parsing.
     * 1.0 = device already reports kg.
     */
    public final double scaleToKq;

    /**
     * TCP port for the embedded HTTP/SSE server.
     */
    public final int httpPort;

    /**
     * Human-readable device label shown in logs and SSE status events.
     */
    public final String deviceName;

    /**
     * When true, FINE-level byte and packet traces are written to scale_debug.log.
     * The file is overwritten on every run — it is a temporary session capture, not
     * a permanent log.  Set false (default) in production.
     */
    public final boolean debug;

    // ─────────────────────────────────────────────────────────────────────────
    // Construction
    // ─────────────────────────────────────────────────────────────────────────

    private AppConfig(Properties p) {
        portName = p.getProperty("port", "AUTO").strip();
        baud = parseIntOrDefault(p, "baud", 9600);
        indicatorType = IndicatorType.fromCode(parseIntOrDefault(p, "indicator.type", 1));
        scaleToKq = parseDblOrDefault(p, "scale.to.kq", 1.0);
        httpPort   = parseIntOrDefault(p, "http.port", 8435);
        deviceName = p.getProperty("device.name", "").strip();
        debug      = Boolean.parseBoolean(p.getProperty("debug", "false").strip());
    }

    /**
     * Load configuration.
     * Searches for {@value PROPS_FILE} in the working directory (or next to the JAR
     * when packaged).  Falls back to all defaults if not found.
     */
    public static AppConfig load() {
        Properties props = new Properties();
        File file = new File(PROPS_FILE);
        if (file.exists()) {
            try (FileInputStream fis = new FileInputStream(file)) {
                props.load(fis);
                log.info("Config loaded from: " + file.getAbsolutePath());
            } catch (IOException e) {
                log.warning("Could not read " + PROPS_FILE + ": " + e.getMessage()
                        + " — using defaults");
            }
        } else {
            log.info("No " + PROPS_FILE + " found — using built-in defaults");
        }
        return new AppConfig(props);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static int parseIntOrDefault(Properties p, String key, int def) {
        try {
            String v = p.getProperty(key);
            return (v != null) ? Integer.parseInt(v.strip()) : def;
        } catch (NumberFormatException e) {
            log.warning("Config: invalid integer for '" + key + "' — using " + def);
            return def;
        }
    }

    private static double parseDblOrDefault(Properties p, String key, double def) {
        try {
            String v = p.getProperty(key);
            return (v != null) ? Double.parseDouble(v.strip()) : def;
        } catch (NumberFormatException e) {
            log.warning("Config: invalid double for '" + key + "' — using " + def);
            return def;
        }
    }

    @Override
    public String toString() {
        return String.format(
                "AppConfig{port=%s, baud=%d, type=%s(%d), scaleToKq=%.3f, httpPort=%d, device='%s', debug=%b}",
                portName, baud, indicatorType, indicatorType.code, scaleToKq, httpPort, deviceName, debug);
    }
}
