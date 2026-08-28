package com.ebim.tms.planning.application;

import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.RouteTemplate;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
        List<RouteTemplate> routes,
        /**
         * Distances and drive times between the places this run touches, resolved by
         * {@code AutoPlanningService} before the engine runs (JOB 05). {@link TravelMatrix#EMPTY}
         * when no location has coordinates, which every engine must tolerate - see the matrix's
         * own comment for why this is handed in rather than fetched.
         */
        TravelMatrix travel,
        /**
         * How long a vehicle spends at each destination, by location id. Absent means unknown,
         * which an engine treats as zero rather than as a reason to refuse: a service time nobody
         * has configured is not the same as a stop that takes no time, but planning a day around
         * the first assumption is better than planning none.
         */
        Map<UUID, Integer> serviceMinutesByLocation,
        /** The shift a proposed trip must fit inside. Never null - see {@link PlanningShift}. */
        PlanningShift shift) {

    public PlanningInput {
        orders = List.copyOf(orders);
        vehicles = List.copyOf(vehicles);
        routes = List.copyOf(routes);
        travel = travel == null ? TravelMatrix.EMPTY : travel;
        serviceMinutesByLocation = serviceMinutesByLocation == null ? Map.of()
                : Map.copyOf(serviceMinutesByLocation);
        shift = shift == null ? PlanningShift.DEFAULT : shift;
    }

    /**
     * The pre-V38 shape: orders, vehicles and routes with no distances at all.
     *
     * <p>Kept because {@link HeuristicPlanningEngine} genuinely does not use the new fields and its
     * tests should not have to pretend otherwise - a V1 test that had to supply an empty matrix and
     * a default shift to say nothing about either would be noise around the rules it is checking.
     */
    public PlanningInput(UUID originId, LocalDate planningDate, List<PlannableOrder> orders,
            List<VehicleCapacityReference> vehicles, List<RouteTemplate> routes) {
        this(originId, planningDate, orders, vehicles, routes, TravelMatrix.EMPTY, Map.of(),
                PlanningShift.DEFAULT);
    }

    /** How long a stop at {@code locationId} takes, or zero when nobody has said. */
    public int serviceMinutesAt(UUID locationId) {
        return serviceMinutesByLocation.getOrDefault(locationId, 0);
    }
}
