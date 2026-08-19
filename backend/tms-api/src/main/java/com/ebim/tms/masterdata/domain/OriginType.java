package com.ebim.tms.masterdata.domain;

/**
 * The category of a loading/dispatch point, mirroring the {@code ck_origin_type} check
 * constraint in migration V6. Kept as one flat vocabulary rather than a separate reference
 * table: the brief asks for a category "without overcomplicating", and a fixed, small set of
 * values does not need administration screens of its own.
 */
public enum OriginType {
    WAREHOUSE,
    DISTRIBUTION_CENTER,
    PLANT,
    HUB,
    OTHER
}
