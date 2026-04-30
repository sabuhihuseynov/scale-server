package com.scale.protocol;

import com.scale.model.IndicatorType;
import com.scale.model.WeightReading;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * ═══════════════════════════════════════════════════════════════════════
 * CAS NT-500 format (IndicatorType 1 and 2):
 * 20-char ASCII line, received after \n terminator, \r\n stripped.
 * <p>
 * Offset  Length  Content
 * ──────  ──────  ───────────────────────────────────────────────────
 * 0       2       Status:  "ST"=stable | "US"=unstable | "OL"=overload
 * 2       1       ","
 * 3       2       Weight type: "GS"=gross | "NT"=net
 * 5       1       ","
 * 6       1       Device-ID character
 * 7       1       Lamp-flags byte (bit-field)
 * 8       1       ","
 * 9       8       Weight  (right-aligned ASCII, e.g. "  110.900")
 * 17      2       Unit    (e.g. "kg")
 * 19      1       trailing
 * ═══════════════════════════════════════════════════════════════════════
 * Type 5 burst format (IndicatorType 5):
 * STX-framed packet pushed continuously by the device.
 * <p>
 * Offset  Length  Content
 * ──────  ──────  ───────────────────────────────────────────────────
 * 0       1       STX (0x02)
 * 5       6       Weight string (6 ASCII chars)
 * ...     ...     '!' character somewhere in frame
 * 20      1       CR  (0x0D)
 * <p>
 * Frame is located by finding '!' and stepping back one byte to STX.
 * ═══════════════════════════════════════════════════════════════════════
 */
public final class ScaleProtocol {

    private static final Logger log = Logger.getLogger(ScaleProtocol.class.getName());

    /**
     * Sentinel meaning "weight field could not be parsed" for Type 5.
     * NaN is used instead of 0.0 because 0.0 is a valid weight (empty platform, tare applied).
     */
    private static final double PARSE_FAILURE = Double.NaN;

    private ScaleProtocol() {
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public dispatch
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Route the raw packet string to the correct parser based on indicator type.
     * Returns null if the packet is malformed or contains no weight.
     */
    public static WeightReading parse(String packet, IndicatorType type,
                                      String deviceName, double scaleToKq) {
        return switch (type) {
            case TYPE5 -> parseType5(packet, deviceName, scaleToKq);
            case CAS_V1, CAS_V2 -> parseCas(packet, deviceName, scaleToKq);
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CAS NT-500 parser  (Type 1 and Type 2)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parse a CAS NT-500 weight packet.
     *
     * @param packet     line received from serial port (20+ chars, \r\n stripped)
     * @param deviceName label for the WeightReading
     * @param scaleToKq  unit multiplier (1.0 = already in kg)
     * @return parsed WeightReading, or null if packet is invalid
     */
    public static WeightReading parseCas(String packet, String deviceName, double scaleToKq) {
        if (packet == null || packet.length() < 20) {
            log.fine(() -> "CAS: packet too short ("
                    + (packet == null ? "null" : packet.length()) + " chars): " + packet);
            return null;
        }

        // ── Status field ────────────────────────────────────────────────────
        // Bytes [0, 2): "ST"=stable | "US"=unstable | "OL"=overload
        // "US" still sends the current reading but the load is mid-movement.
        // "OL" means the truck exceeded the scale's capacity.
        String statusStr = packet.substring(0, 2).toUpperCase();

        // ── Weight field ────────────────────────────────────────────────────
        // Bytes [9, 17): 8-char right-aligned float, e.g. "  110.900"
        final double weight = extractCasWeight(packet, statusStr, scaleToKq);

        // ── Weight-type field ───────────────────────────────────────────────
        // Bytes [3, 5): "GS"=gross | "NT"=net
        final String weightType = packet.substring(3, 5).strip().toUpperCase();

        // ── Unit field ──────────────────────────────────────────────────────
        // Bytes [17, 19): typically "kg" or "lb"
        final String unit = packet.substring(17, 19).strip();

        // ── Lamp-flags byte ─────────────────────────────────────────────────
        // Byte [7]: bit-field encoding which indicator lamps are lit.
        final int lampByte = extractLampByte(packet);

        log.fine(() -> String.format(
                "CAS parsed: status=%s weight=%.3f unit=%s type=%s lamp=0x%02X",
                statusStr, weight, unit, weightType, lampByte));

        return new WeightReading(
                deviceName,
                roundTo3(weight),
                unit.isEmpty() ? "kg" : unit,
                statusStr.equals("ST"),                  // stable
                statusStr.equals("OL"),                  // overload
                weightType.equals("NT") ? "net" : "gross",
                packet,
                decodeLampFlags(lampByte)
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Type 5 burst-read parser
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parse a Type 5 STX-framed weight packet from the raw burst buffer.
     *
     * @param rawBuffer  raw string from the serial burst read (may contain multiple frames)
     * @param deviceName label for the WeightReading
     * @param scaleToKq  unit multiplier
     * @return parsed WeightReading, or null if no valid frame found
     */
    public static WeightReading parseType5(String rawBuffer, String deviceName, double scaleToKq) {
        if (rawBuffer == null || rawBuffer.isEmpty()) {
            return null;
        }

        // ── Locate frame anchor ─────────────────────────────────────────────
        // The '!' character is a known landmark inside the 21-byte packet.
        // We find it, step one byte back, and arrive at STX (0x02).
        int bangIdx = rawBuffer.indexOf('!');
        if (bangIdx < 1) {
            // bangIdx == -1 → '!' not in buffer yet (partial burst read)
            // bangIdx ==  0 → stepping back would yield index -1 (impossible)
            log.fine("Type5: '!' not found or at position 0 — incomplete burst, skipping");
            return null;
        }

        // Slice from STX to end; require at least 21 chars for the full frame.
        // A short frame means the burst read caught the device mid-transmission;
        // the next burst (300 ms later) will include the remainder.
        String frame = rawBuffer.substring(bangIdx - 1);
        if (frame.length() < 21) {
            log.fine(() -> "Type5: frame too short (" + frame.length() + " chars, need 21)");
            return null;
        }

        // Take exactly the 21-byte window: [STX][19 bytes][CR]
        final String chars = frame.substring(0, 21);

        // ── Frame boundary validation ───────────────────────────────────────
        // Confirms we anchored on a real frame and not a '!' inside another field.
        if (chars.charAt(0) != 0x02 || chars.charAt(20) != 0x0D) {
            log.fine(() -> String.format(
                    "Type5: invalid frame markers — [0]=0x%02X (want 0x02), [20]=0x%02X (want 0x0D)",
                    (int) chars.charAt(0), (int) chars.charAt(20)));
            return null;
        }

        // ── Weight field ────────────────────────────────────────────────────
        // Bytes [5, 11) of the validated frame: 6-char weight string.
        // NaN return signals parse failure so we can early-return cleanly.
        final double weight = extractType5Weight(chars, scaleToKq);
        if (Double.isNaN(weight)) {
            log.fine(() -> "Type5: weight parse failed in frame: "
                    + chars.chars()
                    .mapToObj(c -> c < 0x20
                            ? String.format("\\x%02X", c)
                            : String.valueOf((char) c))
                    .collect(Collectors.joining()));
            return null;
        }

        log.fine(() -> String.format("Type5 parsed: weight=%.3f kg", weight));

        // Type 5 only transmits confirmed-stable readings (device holds until
        // stable before sending), so stable=true and overload=false are always correct.
        // No lamp-flag byte exists in this format.
        return new WeightReading(
                deviceName,
                roundTo3(weight),
                "kg",
                true,       // always stable — device only sends stable readings
                false,      // overload not reported in Type 5 format
                "gross",    // Type 5 provides gross weight only
                chars,
                Map.of()    // immutable empty map — no lamp flags in Type 5
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private field-extraction helpers
    //
    // Each helper wraps a try-catch so the call site is a single assignment,
    // satisfying Java's "effectively final" requirement for lambda capture.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extract the weight from a CAS packet.
     *
     * @param packet    validated 20+-char CAS line
     * @param statusStr the already-extracted 2-char status prefix ("ST"/"US"/"OL")
     * @param scaleToKq unit multiplier
     * @return weight in kg (0.0 for non-ST packets or on parse failure)
     */
    private static double extractCasWeight(String packet, String statusStr, double scaleToKq) {
        // Only "ST" readings carry a real weight; "US" and "OL" values are undefined.
        if (!statusStr.equals("ST")) {
            return 0.0;
        }
        try {
            // strip() removes right-alignment spaces: "  110.900" → "110.900"
            return Double.parseDouble(packet.substring(9, 17).strip()) * scaleToKq;
        } catch (NumberFormatException e) {
            log.fine(() -> "CAS: non-numeric weight field: '"
                    + packet.substring(9, 17) + "' → using 0.0");
            return 0.0;
        }
    }

    /**
     * Extract the lamp-flags byte from a CAS packet (offset 7).
     * Wrapped in try-catch so a future refactor that relaxes the length check
     * cannot silently propagate an exception into the receive thread.
     */
    private static int extractLampByte(String packet) {
        try {
            return packet.charAt(7);
        } catch (StringIndexOutOfBoundsException e) {
            log.fine("CAS: lamp byte missing — packet was unexpectedly short");
            return 0;
        }
    }

    /**
     * Extract the weight from a validated Type 5 frame (bytes [5, 11)).
     * Returns {@link Double#NaN} on failure — 0.0 is a valid measurement (empty platform).
     *
     * @param chars a pre-validated 21-char Type 5 frame
     */
    private static double extractType5Weight(String chars, double scaleToKq) {
        try {
            return Double.parseDouble(chars.substring(5, 11).strip()) * scaleToKq;
        } catch (NumberFormatException e) {
            return PARSE_FAILURE;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared utilities
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Decode the CAS lamp-flags byte into a named boolean map.
     * <p>
     * Bit layout (from CAS NT-500 protocol documentation):
     * bit 0 (0x01) → zero indicator lamp
     * bit 1 (0x02) → tare lamp
     * bit 2 (0x04) → net weight lamp
     * bit 6 (0x40) → hold lamp
     * bit 7 (0x80) → stable lamp  (duplicates the "ST" prefix; useful for UI)
     */
    private static Map<String, Boolean> decodeLampFlags(int lamp) {
        // Explicit initial capacity 8 (next power-of-2 above 5 entries) avoids
        // HashMap's internal resize, which would allocate a larger backing array.
        Map<String, Boolean> m = HashMap.newHashMap(8);
        m.put("zero", (lamp & 0b00000001) != 0);
        m.put("tare", (lamp & 0b00000010) != 0);
        m.put("net", (lamp & 0b00000100) != 0);
        m.put("hold", (lamp & 0b01000000) != 0);
        m.put("stable", (lamp & 0b10000000) != 0);
        return m;
    }

    private static double roundTo3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
