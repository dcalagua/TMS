package com.ebim.tms.fleet.application;

/** The optional list filters for {@code GET /fleet/carriers}, bound alongside {@code PageQuery}. */
public record CarrierFilter(String code, String businessName, String taxIdValue, Boolean active) {
}
