package com.scale.serial;

import com.fazecast.jSerialComm.SerialPort;
import com.scale.model.IndicatorType;

import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Probes available serial ports to find the scale device automatically.
 * <p>
 * Used when {@code port} in scale.properties is empty or "AUTO".
 * <p>
 * Strategy per indicator type:
 * <ul>
 *   <li>CAS_V1 — send poll byte 0x01, look for "ST,", "US,", or "OL," in response</li>
 *   <li>CAS_V2 — send "01RW\r", same response signature</li>
 *   <li>TYPE5  — device broadcasts continuously; listen for STX (0x02) + '!' landmark</li>
 * </ul>
 */
public final class PortScanner {

    private static final Logger log = Logger.getLogger(PortScanner.class.getName());

    /** Maximum time to listen on each candidate port before moving on. */
    private static final int PROBE_WAIT_MS = 1_500;

    /** Sleep between byte-available polls while probing. */
    private static final int POLL_SLEEP_MS = 50;

    private PortScanner() {}

    /**
     * Scan all serial ports present on this machine and return the first one
     * that responds with a packet matching the given indicator protocol.
     *
     * @param type protocol to look for (determines poll command and packet signature)
     * @param baud baud rate from configuration (must match the physical scale setting)
     * @return system port name (e.g. "COM3", "/dev/ttyUSB0"), or null if not found
     */
    public static String scan(IndicatorType type, int baud) {
        SerialPort[] ports = SerialPort.getCommPorts();
        if (ports.length == 0) {
            log.warning("PortScanner: no serial ports found on this system");
            return null;
        }

        log.info("PortScanner: scanning " + ports.length + " port(s) for scale device...");

        for (SerialPort p : ports) {
            log.info("PortScanner: probing " + p.getSystemPortName()
                    + "  (" + p.getPortDescription() + ")");
            String hit = probe(p, type, baud);
            if (hit != null) {
                log.info("PortScanner: scale device found on " + hit);
                return hit;
            }
            log.info("PortScanner: " + p.getSystemPortName() + " — no scale response");
        }

        log.warning("PortScanner: scale device not found on any available port");
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-port probe
    // ─────────────────────────────────────────────────────────────────────────

    private static String probe(SerialPort p, IndicatorType type, int baud) {
        p.setBaudRate(baud);
        p.setNumDataBits(8);
        p.setNumStopBits(SerialPort.ONE_STOP_BIT);
        p.setParity(SerialPort.NO_PARITY);
        p.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);

        if (!p.openPort()) {
            log.fine("PortScanner: " + p.getSystemPortName() + " — cannot open (busy or missing)");
            return null;
        }

        try {
            // Send poll command for active (request-response) protocols
            if (type == IndicatorType.CAS_V1) {
                p.writeBytes(new byte[]{0x01}, 1);
            } else if (type == IndicatorType.CAS_V2) {
                byte[] cmd = "01RW\r".getBytes(StandardCharsets.US_ASCII);
                p.writeBytes(cmd, cmd.length);
            }
            // TYPE5: device pushes data continuously — no command needed

            long deadline = System.currentTimeMillis() + PROBE_WAIT_MS;
            byte[] buf  = new byte[512];
            int    total = 0;

            while (System.currentTimeMillis() < deadline && total < buf.length) {
                int avail = p.bytesAvailable();
                if (avail > 0) {
                    int toRead = Math.min(avail, buf.length - total);
                    byte[] chunk = new byte[toRead];
                    int n = p.readBytes(chunk, toRead);
                    if (n > 0) {
                        System.arraycopy(chunk, 0, buf, total, n);
                        total += n;
                    }
                } else {
                    try {
                        Thread.sleep(POLL_SLEEP_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (total == 0) {
                log.fine("PortScanner: " + p.getSystemPortName() + " — no data received");
                return null;
            }

            String data = new String(buf, 0, total, StandardCharsets.US_ASCII);
            log.fine("PortScanner: " + p.getSystemPortName()
                    + " raw [" + total + " bytes]: " + printable(data));

            return matchesScale(data, type) ? p.getSystemPortName() : null;

        } catch (Exception e) {
            log.fine("PortScanner: error on " + p.getSystemPortName() + ": " + e.getMessage());
            return null;
        } finally {
            p.closePort();
        }
    }

    /**
     * Returns true when the captured bytes contain a recognisable scale packet
     * for the given indicator type.
     */
    private static boolean matchesScale(String data, IndicatorType type) {
        return switch (type) {
            // CAS NT-500: every weight response line starts with "ST,", "US,", or "OL,"
            case CAS_V1, CAS_V2 ->
                    data.contains("ST,") || data.contains("US,") || data.contains("OL,");
            // Type 5 burst: STX byte (0x02) followed by the '!' landmark in the frame
            case TYPE5 ->
                    data.indexOf('') >= 0 && data.indexOf('!') > 0;
        };
    }

    /** Renders non-printable bytes as \xNN for log output. */
    private static String printable(String s) {
        StringBuilder sb = new StringBuilder(s.length() * 2);
        for (char c : s.toCharArray()) {
            if (c >= 0x20 && c <= 0x7E) sb.append(c);
            else sb.append(String.format("\\x%02X", (int) c));
        }
        return sb.toString();
    }
}
