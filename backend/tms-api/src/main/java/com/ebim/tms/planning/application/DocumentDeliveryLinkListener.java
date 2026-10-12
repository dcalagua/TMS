package com.ebim.tms.planning.application;

import com.ebim.tms.planning.infrastructure.DocumentDeliveryQueries;
import com.ebim.tms.planning.infrastructure.DocumentDeliveryQueries.TripOrder;
import com.ebim.tms.shared.reference.LogisticsDocumentLinkListener;
import com.ebim.tms.shared.security.CompanyScope;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Starts tracking a document that arrives for an order already on a dispatched trip (ADR-018
 * section 4). The other trigger is {@link DocumentDeliveryTracker#trackTrip}, when the dispatch
 * arrives; between them a document is tracked whichever comes first.
 *
 * <p>Depends on planning's own queries only - not on {@code LogisticsDocumentPort} - because the
 * port's implementation calls this class, and the event already carries what is needed.
 */
@Component
public class DocumentDeliveryLinkListener implements LogisticsDocumentLinkListener {

    private final DocumentDeliveryQueries queries;

    public DocumentDeliveryLinkListener(DocumentDeliveryQueries queries) {
        this.queries = queries;
    }

    @Override
    public void documentLinked(CompanyScope scope, UUID documentId, String documentType, String documentNumber,
            Set<UUID> orderIds) {
        List<TripOrder> carrying = queries.visibleTripsCarrying(scope.companyId(), orderIds);
        Map<UUID, Set<UUID>> ordersByTrip = new LinkedHashMap<>();
        carrying.forEach(pair -> ordersByTrip.computeIfAbsent(pair.tripId(), key -> new LinkedHashSet<>())
                .add(pair.orderId()));
        ordersByTrip.forEach((tripId, orders) -> queries.materialize(scope.companyId(), tripId, documentId,
                documentType, documentNumber, orders));
    }
}
