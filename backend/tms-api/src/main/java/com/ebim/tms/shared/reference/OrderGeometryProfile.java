package com.ebim.tms.shared.reference;

import com.ebim.tms.shared.geometry.GeometryLine;
import com.ebim.tms.shared.geometry.OrderGeometry;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What planning needs to re-stack one order for a vehicle (ADR-022 section 5): its lines as load
 * geometry reads them, the height the stored figures were computed under, and those stored figures.
 *
 * <p>Only orders with at least one measured line have a profile - for every other order the stored
 * figures do not depend on any height, and {@link PlannableOrder} already carries them. Loaded in one
 * query for a whole set of orders ({@link OrderPlanningPort#geometryProfilesOf}), never per order,
 * and never for the planning board's list.
 *
 * @param referenceHeightM the height the order's stored floor and planning volume were computed under
 * @param totalVolumeM3    the order's declared volume total
 * @param planningVolumeM3 the stored planning volume (V62), at {@code referenceHeightM}
 * @param floorAreaM2      the stored floor area, at {@code referenceHeightM}: the V37 ledger's figure
 */
public record OrderGeometryProfile(UUID orderId, BigDecimal referenceHeightM, BigDecimal totalVolumeM3,
        BigDecimal planningVolumeM3, BigDecimal floorAreaM2, List<GeometryLine> lines) {

    public OrderGeometryProfile {
        lines = List.copyOf(lines);
    }

    /** The order's geometry stacked against {@code usableHeightM}. */
    public OrderGeometry at(BigDecimal usableHeightM) {
        return OrderGeometry.of(lines, totalVolumeM3, usableHeightM);
    }
}
