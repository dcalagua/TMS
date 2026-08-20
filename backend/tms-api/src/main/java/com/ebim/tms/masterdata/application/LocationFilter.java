package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.LocationRole;
import com.ebim.tms.masterdata.domain.LocationType;
import java.util.UUID;

/**
 * The optional list filters for {@code GET /masterdata/locations}, bound alongside
 * {@link com.ebim.tms.shared.api.PageQuery}.
 *
 * <p>One {@code search} box rather than separate {@code code} and {@code name} parameters:
 * locations are the master an operator looks something up in, and they look it up by whichever
 * of code, name or external reference they happen to remember.
 */
public record LocationFilter(String search, LocationType type, LocationRole role, UUID zoneId, Boolean active) {
}
