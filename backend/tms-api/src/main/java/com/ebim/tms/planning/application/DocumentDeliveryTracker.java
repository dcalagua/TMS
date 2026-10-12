package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.AssignmentStatus;
import com.ebim.tms.planning.domain.TripOrderAssignment;
import com.ebim.tms.planning.infrastructure.DocumentDeliveryQueries;
import com.ebim.tms.planning.infrastructure.TripOrderAssignmentRepository;
import com.ebim.tms.shared.reference.LogisticsDocumentPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materialises the {@code PENDING} tracking rows of a trip when its warehouse dispatch arrives
 * (ADR-018 section 4): one per document of the orders it carries. Idempotent - a second dispatch
 * revision, or a document already tracked by {@link DocumentDeliveryLinkListener}, adds nothing.
 */
@Service
public class DocumentDeliveryTracker {

    private final DocumentDeliveryQueries queries;
    private final TripOrderAssignmentRepository assignments;
    private final LogisticsDocumentPort documents;

    public DocumentDeliveryTracker(DocumentDeliveryQueries queries, TripOrderAssignmentRepository assignments,
            LogisticsDocumentPort documents) {
        this.queries = queries;
        this.assignments = assignments;
        this.documents = documents;
    }

    @Transactional
    public void trackTrip(CompanyScope scope, UUID tripId) {
        if (!queries.isVisibleTrip(scope.companyId(), tripId)) {
            return;
        }
        Set<UUID> orderIds = new LinkedHashSet<>();
        assignments.findByTripIdAndStatusOrderByAssignedAtAsc(tripId, AssignmentStatus.ACTIVE).stream()
                .map(TripOrderAssignment::orderId).forEach(orderIds::add);
        Map<UUID, List<LogisticsDocumentPort.Document>> byOrder = documents.documentsOf(orderIds, scope.companyId());

        Map<UUID, LogisticsDocumentPort.Document> byId = new LinkedHashMap<>();
        Map<UUID, Set<UUID>> ordersByDocument = new LinkedHashMap<>();
        byOrder.forEach((orderId, list) -> list.forEach(document -> {
            byId.putIfAbsent(document.id(), document);
            ordersByDocument.computeIfAbsent(document.id(), key -> new LinkedHashSet<>()).add(orderId);
        }));
        byId.forEach((documentId, document) -> queries.materialize(scope.companyId(), tripId, documentId,
                document.documentType(), document.documentNumber(), ordersByDocument.get(documentId)));
    }
}
