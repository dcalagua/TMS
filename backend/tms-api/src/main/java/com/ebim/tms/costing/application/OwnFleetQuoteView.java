package com.ebim.tms.costing.application;

import com.ebim.tms.costing.domain.OwnFleetComponent;
import com.ebim.tms.costing.domain.OwnFleetCostReason;
import com.ebim.tms.costing.domain.OwnFleetQuantitySource;
import com.ebim.tms.shared.reference.TransportCostNature;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What a trip is modelled to cost us, as the API returns it (V48, JOB 22).
 *
 * @param nature          always {@code OWN_FLEET_INTERNAL_COST}, sent explicitly rather than
 *                        implied by the endpoint, so a client holding this beside a carrier quote
 *                        cannot lose which kind of number it is
 * @param comparableTotal <b>null when a component the profile charges for could not be
 *                        calculated.</b> Never zero standing in for that, and never a partial sum
 *                        promoted to a total
 * @param partialSubtotal what the calculable lines add up to. For a person diagnosing a gap, never
 *                        for a decision - a plan must not look cheap for missing its own costs
 * @param unavailableReason why there is no total, or null when there is one
 */
public record OwnFleetQuoteView(
        UUID tripId,
        TransportCostNature nature,
        String currency,
        BigDecimal comparableTotal,
        BigDecimal partialSubtotal,
        boolean complete,
        UUID profileId,
        String profileScope,
        List<OwnFleetCostReason> blockingReasons,
        OwnFleetQuoteUnavailable unavailableReason,
        List<Line> lines) {

    public OwnFleetQuoteView {
        blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** One row of the breakdown, carrying quantity, rate, amount and provenance as the brief requires. */
    public record Line(
            OwnFleetComponent component,
            String status,
            BigDecimal rate,
            BigDecimal quantity,
            String unit,
            OwnFleetQuantitySource quantitySource,
            BigDecimal amount,
            OwnFleetCostReason reason) {
    }
}
