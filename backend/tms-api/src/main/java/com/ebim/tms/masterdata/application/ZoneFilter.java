package com.ebim.tms.masterdata.application;

/** The optional list filters for {@code GET /masterdata/zones}. See {@link OriginFilter}. */
public record ZoneFilter(String code, String name, Boolean active) {
}
