package com.ebim.tms.shared.reference;

import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The documents that travel with orders (ADR-015): recorded from the ERP and read per order or per
 * trip. Informational - nothing here moves an order, a trip or a delivery.
 */
public interface LogisticsDocumentPort {

    /** Creates or replaces a document by (sourceSystem, documentType, documentNumber). */
    Intake upsert(CompanyScope scope, Command command);

    /** The documents of each order in {@code orderIds}, batched and company-scoped. */
    Map<UUID, List<Document>> documentsOf(Set<UUID> orderIds, UUID companyId);

    record Command(UUID integrationClientId, String sourceSystem, String documentType, String documentNumber,
            LocalDate issueDate, String issuerTaxId, String recipientTaxId, String recipientName, BigDecimal amount,
            String currency, String externalStatus, List<OrderExternalKey> orders) {
    }

    /**
     * @param outcome CREATED, UPDATED or UNCHANGED
     * @param unknownOrders the order keys that matched no order of the company: linked later by a re-send
     */
    record Intake(UUID id, String outcome, List<OrderExternalKey> linkedOrders, List<OrderExternalKey> unknownOrders) {
    }

    record Document(UUID id, String sourceSystem, String documentType, String documentNumber, LocalDate issueDate,
            String recipientName, BigDecimal amount, String currency, String externalStatus, OffsetDateTime receivedAt) {
    }
}
