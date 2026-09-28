package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.AssignmentStatus;
import com.ebim.tms.planning.domain.TripOrderAssignment;
import com.ebim.tms.planning.infrastructure.TripOrderAssignmentRepository;
import com.ebim.tms.shared.reference.OrderHoldPort;
import com.ebim.tms.shared.reference.OrderHoldPort.HeldOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Which trips carry an order that somebody has put on hold since it was planned (ADR-014 section 7).
 *
 * <p>A hold on a committed order unplans nothing - taking an order off a confirmed shipment behind
 * the dispatcher's back would be worse than the problem the hold reports. So planning asks here,
 * in two places: {@link DispatchReadiness} (the trip may not leave) and the Control Tower's advisory
 * {@code ORDER_HOLD_ON_COMMITTED_TRIP} (somebody should know). The hold itself is read through
 * {@link OrderHoldPort}, never from the orders module's tables.
 *
 * <p>Batched: two queries for any number of trips - the open assignments, then the holds.
 */
@Component
public class CommittedOrderHolds {

    private final TripOrderAssignmentRepository assignmentRepository;
    private final OrderHoldPort orderHoldPort;

    public CommittedOrderHolds(TripOrderAssignmentRepository assignmentRepository, OrderHoldPort orderHoldPort) {
        this.assignmentRepository = assignmentRepository;
        this.orderHoldPort = orderHoldPort;
    }

    /** For each trip that has any, the orders on it with an active blocking hold. Absent means none. */
    public Map<UUID, List<HeldOrder>> onTrips(UUID companyId, Collection<UUID> tripIds) {
        if (tripIds.isEmpty()) {
            return Map.of();
        }
        List<TripOrderAssignment> open = assignmentRepository.findByCompanyIdAndTripIdInAndStatus(
                companyId, tripIds, AssignmentStatus.ACTIVE);
        if (open.isEmpty()) {
            return Map.of();
        }
        Set<UUID> orderIds = open.stream().map(TripOrderAssignment::orderId).collect(Collectors.toSet());
        Map<UUID, HeldOrder> held = orderHoldPort.activeBlockingHolds(orderIds, companyId);
        if (held.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<HeldOrder>> byTrip = new LinkedHashMap<>();
        for (TripOrderAssignment assignment : open) {
            HeldOrder order = held.get(assignment.orderId());
            if (order != null) {
                List<HeldOrder> onTrip = byTrip.computeIfAbsent(assignment.tripId(), key -> new ArrayList<>());
                if (!onTrip.contains(order)) {
                    onTrip.add(order);
                }
            }
        }
        return byTrip;
    }
}
