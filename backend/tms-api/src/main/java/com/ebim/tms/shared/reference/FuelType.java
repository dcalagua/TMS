package com.ebim.tms.shared.reference;

/**
 * What a vehicle type runs on (migration V73). Descriptive: it says what the emission factor was
 * measured for, so a footprint can be read by somebody who did not configure it.
 */
public enum FuelType {
    DIESEL,
    GASOLINE,
    LPG,
    CNG,
    ELECTRIC,
    HYBRID,
    OTHER
}
