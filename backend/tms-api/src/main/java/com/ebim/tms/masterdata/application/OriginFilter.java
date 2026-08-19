package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.OriginType;

/**
 * The optional list filters for {@code GET /masterdata/origins}, bound as a
 * {@code @ModelAttribute} alongside {@link com.ebim.tms.shared.api.PageQuery}. Every field is
 * optional; an absent one is simply not applied (see {@code OriginSpecifications}).
 */
public record OriginFilter(String code, String name, OriginType type, Boolean active) {
}
