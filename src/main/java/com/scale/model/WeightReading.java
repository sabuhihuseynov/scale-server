package com.scale.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable snapshot of a single scale measurement.
 * <p>
 * All weights are normalised to kg before construction.
 */
public final class WeightReading {

    /**
     * Serial port or logical device label (e.g. "CAS NT-500").
     */
    public final String device;

    /**
     * Weight in kg, rounded to 3 decimal places.
     */
    public final double weight;

    /**
     * Unit string as reported by device (e.g. "kg", "lb"). Defaults to "kg".
     */
    public final String unit;

    /**
     * True if the device reported a stable reading ("ST" prefix in CAS).
     */
    public final boolean stable;

    /**
     * True if the device reported an overload condition ("OL" prefix in CAS).
     */
    public final boolean overload;

    /**
     * "gross" or "net" (from GS/NT field in CAS format).
     */
    public final String weightType;

    /**
     * Raw string/bytes received from the device (for debugging).
     */
    public final String raw;

    /**
     * ISO-8601 timestamp of when this reading was created.
     */
    public final String timestamp;

    public WeightReading(
            String device,
            double weight,
            String unit,
            boolean stable,
            boolean overload,
            String weightType,
            String raw) {

        this.device = Objects.requireNonNull(device, "device");
        this.weight = weight;
        this.unit = (unit == null || unit.isBlank()) ? "kg" : unit.strip();
        this.stable = stable;
        this.overload = overload;
        this.weightType = (weightType == null) ? "gross" : weightType;
        this.raw = (raw == null) ? "" : raw;
        this.timestamp = Instant.now().toString();
    }

    @Override
    public String toString() {
        return String.format(
                "WeightReading{device='%s', weight=%.3f %s, stable=%b, overload=%b, type=%s}",
                device, weight, unit, stable, overload, weightType);
    }
}
