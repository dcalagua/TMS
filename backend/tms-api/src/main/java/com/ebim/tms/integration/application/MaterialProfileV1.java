package com.ebim.tms.integration.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One material profile as the warehouse sends it ({@code WAREHOUSE_EXECUTION_V1.md} section 7.1,
 * ADR-022). A published contract: changes are additive.
 *
 * <p>A record cannot tell an absent field from an explicit {@code null}, and the contract needs
 * both (absent keeps, {@code null} clears). The controller therefore reads which fields the JSON
 * named and carries that list beside this record - see {@link MaterialProfileDelivery}.
 *
 * @param materialCode only in a batch item; on the PUT the path carries it, and a body that also
 *     names it must name the same code
 * @param sourceSystem in a batch item, optional: the batch's own {@code sourceSystem} applies
 */
public record MaterialProfileV1(
        String materialCode,
        String sourceSystem,
        OffsetDateTime sourceUpdatedAt,
        String description,
        BigDecimal lengthM,
        BigDecimal widthM,
        BigDecimal heightM,
        BigDecimal unitWeightKg,
        BigDecimal unitVolumeM3,
        String stackMode,
        Integer stackMaxQuantity,
        BigDecimal nestIncrementM,
        BigDecimal maxTopLoadKg,
        Boolean orientationLocked) {
}
