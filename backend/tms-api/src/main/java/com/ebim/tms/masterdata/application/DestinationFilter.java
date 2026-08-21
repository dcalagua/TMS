package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.DestinationType;
import java.util.UUID;

/**
 * The optional list filters for {@code GET /masterdata/destinations}, bound alongside
 * {@link com.ebim.tms.shared.api.PageQuery}. See {@link OriginFilter}, including why
 * {@code search} exists next to {@code code} and {@code name}.
 */
public record DestinationFilter(
        String code, String name, String search, DestinationType type, UUID zoneId, Boolean active) {
}
