package com.ebim.tms.masterdata.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The AI's proposed stop order, measured (ADR-030). A proposal, not a change: the form shows it next
 * to the current order and the planner applies it or not. Nothing has been saved.
 *
 * @param destinationIds     every requested stop exactly once, in the proposed order
 * @param currentDistanceKm  origin -> stops in the order the planner sent, by the same matrix
 * @param proposedDistanceKm origin -> stops in the proposed order; the drive back is not counted
 * @param improved           whether the proposal is strictly shorter than the current order
 * @param estimated          whether any leg is a coordinate estimate rather than a routing
 *                           provider's answer - with no provider configured, always true
 * @param rationale          the model's explanation, in Spanish, naming stops by their position in
 *                           the current order ("parada 3"); may be empty
 * @param model              the model that answered
 */
public record RouteStopOptimizationView(
        List<UUID> destinationIds,
        BigDecimal currentDistanceKm,
        BigDecimal proposedDistanceKm,
        boolean improved,
        boolean estimated,
        String rationale,
        String model) {
}
