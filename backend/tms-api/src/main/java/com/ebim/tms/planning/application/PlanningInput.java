package com.ebim.tms.planning.application;

import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.RouteTemplate;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Everything a {@link PlanningEngine} is allowed to see: one origin, one date, the orders that
 * are already known to be eligible, the vehicles that are already known to be free, and the
 * corridors that leave that origin.
 *
 * <p>"Already known" is the contract. Deciding whether an order may be planned - its status, its
 * company, its service date, its destination's service calendar - and whether a vehicle is free -
 * active, available, not already booked that day - happens in {@code AutoPlanningService} before
 * this record is built. An engine that re-derived eligibility would be a second place for those
 * rules to be wrong, and swapping in a solver later would mean porting them.
 *
 * @param originId     the planning run's origin; every order here ships from it
 * @param planningDate the run's date, carried for engines that reason about time windows. The V1
 *                     heuristic does not, and says so rather than pretending otherwise
 * @param orders       eligible orders, in no meaningful order - the engine sorts them itself, so
 *                     a proposal does not depend on how the query happened to return rows
 * @param vehicles     assignable vehicles, heaviest capacity first
 *                     ({@code VehicleLookupPort.findAssignableInCompany})
 * @param routes       active corridors leaving {@code originId}, ordered by code
 */
public record PlanningInput(
        UUID originId,
        LocalDate planningDate,
        List<PlannableOrder> orders,
        List<VehicleCapacityReference> vehicles,
        List<RouteTemplate> routes) {

    public PlanningInput {
        orders = List.copyOf(orders);
        vehicles = List.copyOf(vehicles);
        routes = List.copyOf(routes);
    }
}
