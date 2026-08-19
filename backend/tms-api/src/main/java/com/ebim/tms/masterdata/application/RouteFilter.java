package com.ebim.tms.masterdata.application;

import java.util.UUID;

/**
 * The optional list filters for {@code GET /masterdata/routes}, bound alongside
 * {@link com.ebim.tms.shared.api.PageQuery}. See {@link OriginFilter}.
 */
public record RouteFilter(String code, String name, UUID originId, UUID zoneId, Boolean active) {
}
