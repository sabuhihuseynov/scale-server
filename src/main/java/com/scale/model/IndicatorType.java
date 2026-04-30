package com.scale.model;

/**
 * Scale indicator protocol types.
 * <p>
 * ┌──────┬──────────────┬───────────────────┬───────────────────────────────────┐
 * │ Code │  Name        │  Poll command      │  Packet format                    │
 * ├──────┼──────────────┼───────────────────┼───────────────────────────────────┤
 * │  1   │  CAS_V1      │  byte 0x01         │  "ST/US/OL GS/NT ... weight kg"   │
 * │  2   │  CAS_V2      │  "01RW\r"          │  same 20-char CAS format          │
 * │  5   │  TYPE5       │  none (burst)      │  STX...weight[5:11]...!...CR      │
 * └──────┴──────────────┴───────────────────┴───────────────────────────────────┘
 */
public enum IndicatorType {

    /**
     * CAS NT-500 v1 — polls device with raw byte 0x01.
     */
    CAS_V1(1),

    /**
     * CAS NT-500 v2 — polls device with ASCII command "01RW\r".
     */
    CAS_V2(2),

    /**
     * Burst-read indicator — device pushes STX-framed data continuously.
     */
    TYPE5(5);

    public final int code;

    IndicatorType(int code) {
        this.code = code;
    }

    public static IndicatorType fromCode(int code) {
        for (IndicatorType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown indicator type: " + code
                + "  (valid: 1, 2, 5)");
    }

    /**
     * Returns true for CAS_V1 and CAS_V2 (both use readline mode).
     */
    public boolean isLineBased() {
        return this == CAS_V1 || this == CAS_V2;
    }
}
