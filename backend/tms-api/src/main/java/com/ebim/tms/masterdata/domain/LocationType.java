package com.ebim.tms.masterdata.domain;

/**
 * What a {@link Location} physically <em>is</em>, mirroring the {@code ck_location_type} check
 * constraint in migration V14.
 *
 * <p>This is deliberately the <b>union</b> of {@link OriginType} (V6) and
 * {@link DestinationType} (V7) rather than a new vocabulary: the V14 backfill has to carry
 * every legacy row across without reinterpreting its type, and a value that existed on either
 * side has to survive. {@link #toOriginType()} and {@link #toDestinationType()} narrow it back
 * for the compatibility projections, which is where the loss - documented as D-3 in
 * {@code docs/architecture/ADR_LOCATION_MODEL.md} - happens.
 *
 * <p>What a location may <em>do</em> is a different question, answered by {@link LocationRole}.
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
    DELIVERY_POINT;

    /**
     * The canonical type an origin's own type widens to. Total and exhaustive rather than
     * {@code valueOf(name())}: the two vocabularies happen to overlap by name today, and a
     * reflective lookup would turn a future divergence into a runtime failure instead of a
     * compile error.
     */
    public static LocationType from(OriginType type) {
        return switch (type) {
            case WAREHOUSE -> WAREHOUSE;
            case DISTRIBUTION_CENTER -> DISTRIBUTION_CENTER;
            case PLANT -> PLANT;
            case HUB -> HUB;
            case OTHER -> OTHER;
        };
    }

    /** The canonical type a destination's own type widens to; see {@link #from(OriginType)}. */
    public static LocationType from(DestinationType type) {
        return switch (type) {
            case CUSTOMER -> CUSTOMER;
            case STORE -> STORE;
            case BRANCH -> BRANCH;
            case HUB -> HUB;
            case DISTRIBUTION_CENTER -> DISTRIBUTION_CENTER;
            case DELIVERY_POINT -> DELIVERY_POINT;
        };
    }

    /**
     * The {@code tms.origin} type this projects to. {@code OTHER} is the catch-all for the
     * values V6's vocabulary never had, which is exactly what {@code OTHER} is for there.
     */
    public OriginType toOriginType() {
        return switch (this) {
            case WAREHOUSE -> OriginType.WAREHOUSE;
            case DISTRIBUTION_CENTER -> OriginType.DISTRIBUTION_CENTER;
            case PLANT -> OriginType.PLANT;
            case HUB -> OriginType.HUB;
            case OTHER, CUSTOMER, STORE, BRANCH, DELIVERY_POINT -> OriginType.OTHER;
        };
    }

    /**
     * The {@code tms.destination} type this projects to. {@code DELIVERY_POINT} is V7's
     * generic value and therefore the catch-all on this side.
     */
    public DestinationType toDestinationType() {
        return switch (this) {
            case CUSTOMER -> DestinationType.CUSTOMER;
            case STORE -> DestinationType.STORE;
            case BRANCH -> DestinationType.BRANCH;
            case HUB -> DestinationType.HUB;
            case DISTRIBUTION_CENTER -> DestinationType.DISTRIBUTION_CENTER;
            case WAREHOUSE, PLANT, OTHER, DELIVERY_POINT -> DestinationType.DELIVERY_POINT;
        };
    }
}
