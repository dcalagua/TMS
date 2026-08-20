package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.OriginType;

/**
 * The optional list filters for {@code GET /masterdata/origins}, bound as a
 * {@code @ModelAttribute} alongside {@link com.ebim.tms.shared.api.PageQuery}. Every field is
 * optional; an absent one is simply not applied (see {@code OriginSpecifications}).
 *
 * <p>{@code search} is the single free-text term an autocomplete sends: it matches a substring
 * of either the code or the name. It does not replace {@code code}/{@code name}, which narrow
 * one field each and are what the filter bar uses - {@code search} is what a lookup field needs,
 * where the operator types "LIM" without deciding first whether that is a code or part of a name.
 */
public record OriginFilter(String code, String name, String search, OriginType type, Boolean active) {
}
