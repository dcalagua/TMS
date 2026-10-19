package com.ebim.tms.shared.reference;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * How {@code orders} reads a material's warehouse profile without depending on
 * {@code masterdata} (ADR-022 section 1; {@code ModuleBoundaryTest}). Implemented by
 * {@code masterdata.infrastructure.MaterialProfileLookupAdapter}.
 */
public interface MaterialProfileLookupPort {

    /**
     * The profiles of {@code codes} in {@code companyId}, keyed by material code, in one query.
     * Matching is exact - byte for byte, no case folding - and a code with no profile is absent
     * from the map.
     */
    Map<String, MaterialProfileSnapshot> byCodes(UUID companyId, Set<String> codes);
}
