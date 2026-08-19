package com.ebim.tms.masterdata.application;

/** The optional list filters for {@code GET /masterdata/frequencies}. See {@link OriginFilter}. */
public record FrequencyFilter(String code, String name, Boolean active) {
}
