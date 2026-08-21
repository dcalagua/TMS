package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The minimal, display-only view of a master data row another business module needs when it
 * only wants "this id's code and name", never the master's own domain type.
 *
 * <p>Carries no {@code active} flag on purpose: a lookup for <em>display</em>
 * ({@link OriginLookupPort#findAllInCompany}/{@link DestinationLookupPort#findAllInCompany})
 * intentionally resolves a deactivated master too, so an already-persisted reference keeps
 * rendering correctly after the master it points at is deactivated - the same invariant
 * {@code docs/database/DATA_MODEL.md} section 9.5 documents for routes. A lookup that must
 * reject an inactive master ({@code findActiveInCompany}) filters before ever returning one.
 *
 * <p>It does carry the master's coordinates, both nullable and always both-or-neither (the
 * {@code ck_*_coordinates_pair} checks in V6/V7 guarantee that at the source). They are here
 * rather than in a second "geo" reference because every consumer that renders a master on a
 * screen may also need to put it on a map - a planned shipment's stop list is the first
 * ({@code docs/domain/SHIPMENT_V2.md}, "Map-ready stops") - and splitting the two would double
 * every batched lookup for one of them. A master with no coordinate yet simply reports null,
 * which is what lets a map degrade gracefully instead of inventing a position.
 *
 * @param latitude  decimal degrees, or null when the master has not been geocoded
 * @param longitude decimal degrees, or null when the master has not been geocoded
 * @param address   the master's free-text address line, or null when it has none
 */
public record MasterReference(UUID id, String code, String name, BigDecimal latitude, BigDecimal longitude,
        String address) {

    /** A reference with no coordinates and no address - for a master that has neither, and for tests. */
    public static MasterReference of(UUID id, String code, String name) {
        return new MasterReference(id, code, name, null, null, null);
    }

    /** True when both coordinates are present, so a caller can decide whether it is mappable. */
    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }
}
