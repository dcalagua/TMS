package com.ebim.tms.shared.reference;

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
 */
public record MasterReference(UUID id, String code, String name) {
}
