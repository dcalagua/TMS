package com.ebim.tms.iam.application;

import java.math.BigDecimal;

/**
 * The two numbers load geometry runs on for the selected company (V62, ADR-022 sections 4-5), as
 * the settings screen shows them: the value in force and where it comes from.
 *
 * <p>Read-only on purpose. {@code CompanyProfileRequest} does not carry these columns and the
 * settings upsert does not write them, so the screen states them rather than offering a field the
 * save would ignore.
 *
 * @param referenceHeightM       the usable height {@code H} order lines are stacked against when they
 *                               are written; never null
 * @param referenceHeightSource  {@code COMPANY} when {@code company_settings.reference_load_height_m}
 *                               is set, else {@code DEPLOYMENT} (the property
 *                               {@code tms.planning.geometry.default-reference-height-m})
 * @param floorFillFactor        the share of a vehicle's {@code length x width} a load can cover when
 *                               the vehicle type does not declare its own; never null
 * @param floorFillFactorSource  {@code COMPANY} or {@code DEPLOYMENT}, as above
 */
public record CompanyLoadGeometryView(
        BigDecimal referenceHeightM,
        String referenceHeightSource,
        BigDecimal floorFillFactor,
        String floorFillFactorSource) {
}
