package com.ebim.tms.masterdata.domain;

/**
 * How a {@link Location} may be <em>used</em> in a movement, mirroring {@code ck_location_role}
 * as migration V23 leaves it. A location may hold both; {@link LocationType} answers the
 * different question of what the place <em>is</em>.
 *
 * <p>V14 shipped seven values - the two below plus {@code STORE}, {@code DC}, {@code PLANT},
 * {@code HUB} and {@code OTHER} - and its own ADR recorded that the last five carried no
 * behaviour. They were classification, which is precisely what {@link LocationType} already
 * expresses, with one value per place instead of a set. V23 removed them, because a screen that
 * shows "Type: Store" next to "Roles: Store" is teaching an operator that the master
 * contradicts itself.
 *
 * <ul>
 *   <li>{@link #ORIGIN} - the location may ship: a route origin, an order origin, a planning-run
 *       origin.</li>
 *   <li>{@link #DESTINATION} - the location may receive: a route stop, an order destination, a
 *       trip stop.</li>
 * </ul>
 *
 * <p>One store holds both, and that is the point of the model: it is the destination of the
 * delivery and the origin of the return, as one row, with one address and one pair of
 * coordinates.
 *
 * <p>Widening this vocabulary later ({@code PICKUP}, {@code CROSS_DOCK}, {@code RETURN_POINT})
 * means one migration relaxing {@code ck_location_role_role} and one value here. It is deferred
 * until a functional requirement asks for it - see {@code docs/domain/LOCATIONS.md}.
 */
public enum LocationRole {

    ORIGIN,
    DESTINATION
}
