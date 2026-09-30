package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.AssignmentStatus;
import com.ebim.tms.planning.domain.TripOrderAssignment;
import com.ebim.tms.planning.infrastructure.TripOrderAssignmentRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.reference.LogisticsDocumentPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The documents travelling on a trip (ADR-015), derived through the orders it carries - a split
 * order's documents show on every trip that carries part of it.
 */
@Service
public class TripDocumentQueryService {

    /** One entry per order on the trip, with its documents. */
    public record OrderDocuments(UUID orderId, List<LogisticsDocumentPort.Document> documents) {
    }

    private final TripRepository trips;
    private final TripOrderAssignmentRepository assignments;
    private final LogisticsDocumentPort documents;

    public TripDocumentQueryService(TripRepository trips, TripOrderAssignmentRepository assignments,
            LogisticsDocumentPort documents) {
        this.trips = trips;
        this.assignments = assignments;
        this.documents = documents;
    }

    @Transactional(readOnly = true)
    public List<OrderDocuments> documentsOf(CompanyScope scope, UUID tripId) {
        trips.findByIdAndCompanyId(tripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));
        Set<UUID> orderIds = new LinkedHashSet<>();
        assignments.findByTripIdAndStatusOrderByAssignedAtAsc(tripId, AssignmentStatus.ACTIVE)
                .stream().map(TripOrderAssignment::orderId).forEach(orderIds::add);
        Map<UUID, List<LogisticsDocumentPort.Document>> byOrder = documents.documentsOf(orderIds, scope.companyId());
        return orderIds.stream().map(orderId -> new OrderDocuments(orderId, byOrder.getOrDefault(orderId, List.of())))
                .toList();
    }
}
