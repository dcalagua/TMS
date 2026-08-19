package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.DestinationType;
import java.util.UUID;

/**
 * The optional list filters for {@code GET /masterdata/destinations}, bound alongside
 * {@link com.ebim.tms.shared.api.PageQuery}. See {@link OriginFilter}.
 */
public record DestinationFilter(String code, String name, DestinationType type, UUID zoneId, Boolean active) {
}
