package com.ebim.tms.masterdata.domain;

/**
 * What a {@link Location} may <em>do</em>, mirroring {@code ck_location_role} in migration V14.
 * A location holds several of these; {@link LocationType} answers the different question of
 * what the place is.
 *
 * <p>Only two of the seven carry behaviour today, and saying so explicitly is the point of this
 * comment - a "role" that sometimes means capability and sometimes means category is how a
 * master-data model rots:
 *
 * <ul>
 *   <li>{@link #ORIGIN} - the location has a {@code tms.origin} projection, so it can be a route
 *       origin, an order origin and a planning-run origin.</li>
 *   <li>{@link #SHIP_TO} - the location has a {@code tms.destination} projection, so it can be a
 *       route stop, an order destination and a trip stop.</li>
 *   <li>the rest - classification only. They project nothing and gate nothing.</li>
 * </ul>
 *
 * <p>See {@code docs/architecture/ADR_LOCATION_MODEL.md} section 2.
 */
public enum LocationRole {

    ORIGIN,
    SHIP_TO,
    STORE,
    DC,
    PLANT,
    HUB,
    OTHER;

    /** True for the roles that decide whether a compatibility projection exists. */
    public boolean isProjecting() {
        return this == ORIGIN || this == SHIP_TO;
    }
}
