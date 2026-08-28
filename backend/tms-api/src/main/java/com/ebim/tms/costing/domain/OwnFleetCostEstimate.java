package com.ebim.tms.costing.domain;

import com.ebim.tms.shared.reference.TransportCostNature;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What we model this trip costing us (V48, JOB 22).
 *
 * <p><b>The total can be null, and that is the point of the type.</b> {@link #comparableTotal()} is
 * present only when every component the profile charges for could actually be calculated. If the
 * profile charges per kilometre and routing could not measure the trip, there is no total - not a
 * total missing the fuel.
 *
 * <p>The alternative was to sum what could be calculated and label it "partial". That number would
 * have been usable by accident: a planner comparing options would see the trip whose distance is
 * unknown come out cheapest, because the components it could not calculate contributed nothing.
 * <b>The system must not reward a plan for missing its own costs.</b> So the breakdown is still
 * produced - {@link #lines()} shows what applied and what did not, which is what a person needs to
 * fix it - and the comparable figure is withheld.
 *
 * <p>{@link #partialSubtotal()} exists for that diagnostic screen and is deliberately named so it
 * cannot be mistaken for the total. Nothing that makes a decision reads it.
 */
public record OwnFleetCostEstimate(
        String currency,
        BigDecimal comparableTotal,
        BigDecimal partialSubtotal,
        List<OwnFleetCostLine> lines) {

    public OwnFleetCostEstimate {
        lines = List.copyOf(lines);
    }

    /** Always an internal cost. There is no margin in this number and it binds nobody. */
    public TransportCostNature nature() {
        return TransportCostNature.OWN_FLEET_INTERNAL_COST;
    }

    /** Whether every component this profile charges for could be calculated. */
    public boolean isComplete() {
        return comparableTotal != null;
    }

    public List<OwnFleetCostLine> notCalculableLines() {
        return lines.stream().filter(line -> !line.isApplied()).toList();
    }

    /** The distinct reasons the total is unavailable, for a screen that has to say what to fix. */
    public Set<OwnFleetCostReason> blockingReasons() {
        return lines.stream()
                .filter(line -> !line.isApplied())
                .map(OwnFleetCostLine::reason)
                .collect(Collectors.toCollection(() -> java.util.EnumSet.noneOf(OwnFleetCostReason.class)));
    }
}
