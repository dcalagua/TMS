package com.ebim.tms.masterdata.domain;

/**
 * What a {@link Location} physically <em>is</em>, mirroring the {@code ck_location_type} check
 * constraint in migration V14.
 *
 * <p>The vocabulary is the union of the two enums the retired {@code tms.origin} (V6) and
 * {@code tms.destination} (V7) masters used, so V14's backfill could carry every legacy row
 * across without reinterpreting its type. V23 repointed every consumer at {@link Location} and
 * removed the projections, so the narrowing conversions this enum used to carry - and the
 * information they lost - are gone with them.
 *
 * <p>How a location may be <em>used</em> is a different question, answered by
 * {@link LocationRole}. One value here, a set there.
 */
public enum LocationType {

    WAREHOUSE,
    DISTRIBUTION_CENTER,
    PLANT,
    HUB,
    OTHER,
    CUSTOMER,
    STORE,
    BRANCH,
    DELIVERY_POINT
}
