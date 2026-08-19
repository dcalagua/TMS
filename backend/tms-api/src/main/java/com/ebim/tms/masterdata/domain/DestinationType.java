package com.ebim.tms.masterdata.domain;

/**
 * The category of a delivery/service point, mirroring the {@code ck_destination_type} check
 * constraint in migration V7. Kept as one flat vocabulary, same reasoning as {@link OriginType}:
 * a fixed, small set of values does not need administration screens of its own.
 */
public enum DestinationType {
    CUSTOMER,
    STORE,
    BRANCH,
    HUB,
    DISTRIBUTION_CENTER,
    DELIVERY_POINT
}
