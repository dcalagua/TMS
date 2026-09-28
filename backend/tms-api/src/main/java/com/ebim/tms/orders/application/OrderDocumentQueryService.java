package com.ebim.tms.orders.application;

import com.ebim.tms.orders.infrastructure.TransportOrderRepository;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.reference.LogisticsDocumentPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The documents of one order (ADR-015), after checking the order is this company's. */
@Service
public class OrderDocumentQueryService {

    private final TransportOrderRepository orders;
    private final LogisticsDocumentPort documents;

    public OrderDocumentQueryService(TransportOrderRepository orders, LogisticsDocumentPort documents) {
        this.orders = orders;
        this.documents = documents;
    }

    @Transactional(readOnly = true)
    public List<LogisticsDocumentPort.Document> documentsOf(CompanyScope scope, UUID orderId) {
        orders.findByIdAndCompanyId(orderId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));
        return documents.documentsOf(Set.of(orderId), scope.companyId()).getOrDefault(orderId, List.of());
    }
}
