package com.ebim.tms.costing.domain;

import java.math.BigDecimal;

/**
 * One line of an own-fleet estimate (V48, JOB 22).
 *
 * <p>Shaped deliberately like {@code rates.CostLine} - status, rate, quantity, unit, source, amount
 * - so that a screen showing a carrier breakdown and one showing an own-fleet breakdown read the
 * same way and a reader moving between them is not learning a second layout. It is a separate type
 * rather than the same one because its component vocabulary is different
 * ({@link OwnFleetComponent}, not {@code RateComponent}), and forcing one record to carry either
 * would mean a component field nobody can switch on.
 *
 * @param amount zero for a line that could not be calculated, so that any sum over these lines is a
 *               plain sum - and never the whole story on its own, which is why
 *               {@link OwnFleetCostEstimate#comparableTotal()} exists and can be null
 */
public record OwnFleetCostLine(
        OwnFleetComponent component,
        OwnFleetLineStatus status,
        BigDecimal rate,
        BigDecimal quantity,
        OwnFleetUnit unit,
        OwnFleetQuantitySource quantitySource,
        BigDecimal amount,
        OwnFleetCostReason reason) {

    static OwnFleetCostLine flat(OwnFleetComponent component, BigDecimal rate) {
        return new OwnFleetCostLine(component, OwnFleetLineStatus.APPLIED, rate, null, null,
                OwnFleetQuantitySource.PROFILE_FLAT, rate, null);
    }

    static OwnFleetCostLine measured(OwnFleetComponent component, BigDecimal rate, BigDecimal quantity,
            BigDecimal amount, OwnFleetQuantitySource source) {
        return new OwnFleetCostLine(component, OwnFleetLineStatus.APPLIED, rate, quantity,
                component.unit(), source, amount, null);
    }

    /**
     * A component the profile charges for and this trip could not supply an input to.
     *
     * <p>Carries the rate that would have applied, because "we charge 0.65 per km and do not know
     * the kilometres" is a materially different message from "we do not charge for fuel", and a
     * screen that dropped the rate could not tell them apart.
     */
    static OwnFleetCostLine notCalculable(OwnFleetComponent component, BigDecimal rate, OwnFleetCostReason reason) {
        return new OwnFleetCostLine(component, OwnFleetLineStatus.NOT_CALCULABLE, rate, null,
                component.unit(), null, BigDecimal.ZERO, reason);
    }

    public boolean isApplied() {
        return status == OwnFleetLineStatus.APPLIED;
    }
}
