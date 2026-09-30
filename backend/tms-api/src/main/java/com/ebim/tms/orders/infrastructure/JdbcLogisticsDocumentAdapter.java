package com.ebim.tms.orders.infrastructure;

import com.ebim.tms.shared.reference.LogisticsDocumentPort;
import com.ebim.tms.shared.reference.OrderExternalKey;
import com.ebim.tms.shared.reference.OrderReferencePort;
import com.ebim.tms.shared.security.CompanyScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link LogisticsDocumentPort} over plain SQL (ADR-015, V55): two small tables written whole and
 * read in batches, where an entity graph would add nothing. Every statement names the company.
 */
@Repository
class JdbcLogisticsDocumentAdapter implements LogisticsDocumentPort {

    private final JdbcClient jdbc;
    private final OrderReferencePort orderReferences;

    JdbcLogisticsDocumentAdapter(JdbcClient jdbc, OrderReferencePort orderReferences) {
        this.jdbc = jdbc;
        this.orderReferences = orderReferences;
    }

    private record Stored(UUID id, Command asStored, Set<UUID> orderIds) {
    }

    @Override
    @Transactional
    public Intake upsert(CompanyScope scope, Command command) {
        UUID companyId = scope.companyId();
        Map<OrderExternalKey, UUID> resolved = orderReferences.findIdsByExternalKeys(command.orders(), companyId);
        Set<UUID> orderIds = new LinkedHashSet<>(resolved.values());
        List<OrderExternalKey> linked = command.orders().stream().filter(resolved::containsKey).distinct().toList();
        List<OrderExternalKey> unknown = command.orders().stream().filter(key -> !resolved.containsKey(key))
                .distinct().toList();

        Optional<Stored> existing = find(companyId, command);
        String outcome;
        UUID id;
        if (existing.isEmpty()) {
            id = jdbc.sql("""
                    INSERT INTO tms.logistics_document (company_id, source_system, document_type, document_number,
                        issue_date, issuer_tax_id, recipient_tax_id, recipient_name, amount, currency,
                        external_status, integration_client_id)
                    VALUES (:companyId, :sourceSystem, :documentType, :documentNumber, :issueDate, :issuerTaxId,
                        :recipientTaxId, :recipientName, :amount, :currency, :externalStatus, :clientId)
                    RETURNING id
                    """).paramSource(params(companyId, command)).query(UUID.class).single();
            outcome = "CREATED";
        } else {
            id = existing.get().id();
            if (sameContent(existing.get().asStored(), command) && existing.get().orderIds().equals(orderIds)) {
                return new Intake(id, "UNCHANGED", linked, unknown);
            }
            Map<String, Object> params = params(companyId, command);
            params.put("id", id);
            jdbc.sql("""
                    UPDATE tms.logistics_document SET issue_date = :issueDate, issuer_tax_id = :issuerTaxId,
                        recipient_tax_id = :recipientTaxId, recipient_name = :recipientName, amount = :amount,
                        currency = :currency, external_status = :externalStatus, integration_client_id = :clientId,
                        received_at = now(), version = version + 1
                    WHERE id = :id AND company_id = :companyId
                    """).paramSource(params).update();
            jdbc.sql("DELETE FROM tms.logistics_document_order WHERE document_id = :id AND company_id = :companyId")
                    .param("id", id).param("companyId", companyId).update();
            outcome = "UPDATED";
        }
        for (UUID orderId : orderIds) {
            jdbc.sql("""
                    INSERT INTO tms.logistics_document_order (document_id, order_id, company_id)
                    VALUES (:id, :orderId, :companyId)
                    """).param("id", id).param("orderId", orderId).param("companyId", companyId).update();
        }
        return new Intake(id, outcome, linked, unknown);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<Document>> documentsOf(Set<UUID> orderIds, UUID companyId) {
        Map<UUID, List<Document>> byOrder = new HashMap<>();
        if (orderIds.isEmpty()) {
            return byOrder;
        }
        jdbc.sql("""
                SELECT lo.order_id, d.id, d.source_system, d.document_type, d.document_number, d.issue_date,
                       d.recipient_name, d.amount, d.currency, d.external_status, d.received_at
                FROM tms.logistics_document_order lo
                JOIN tms.logistics_document d ON d.id = lo.document_id AND d.company_id = lo.company_id
                WHERE lo.company_id = :companyId AND lo.order_id IN (:orderIds)
                ORDER BY d.document_type, d.document_number
                """).param("companyId", companyId).param("orderIds", orderIds)
                .query((ResultSet rs, int row) -> {
                    byOrder.computeIfAbsent(rs.getObject("order_id", UUID.class), key -> new ArrayList<>())
                            .add(document(rs));
                    return null;
                }).list();
        return byOrder;
    }

    private Optional<Stored> find(UUID companyId, Command command) {
        Optional<Stored> found = jdbc.sql("""
                SELECT id, issue_date, issuer_tax_id, recipient_tax_id, recipient_name, amount, currency,
                       external_status
                FROM tms.logistics_document
                WHERE company_id = :companyId AND source_system = :sourceSystem AND document_type = :documentType
                  AND document_number = :documentNumber
                """).param("companyId", companyId).param("sourceSystem", command.sourceSystem())
                .param("documentType", command.documentType()).param("documentNumber", command.documentNumber())
                .query((rs, row) -> new Stored(rs.getObject("id", UUID.class), new Command(null,
                        command.sourceSystem(), command.documentType(), command.documentNumber(),
                        rs.getObject("issue_date", java.time.LocalDate.class), rs.getString("issuer_tax_id"),
                        rs.getString("recipient_tax_id"), rs.getString("recipient_name"), rs.getBigDecimal("amount"),
                        rs.getString("currency"), rs.getString("external_status"), List.of()), Set.of()))
                .optional();
        return found.map(stored -> new Stored(stored.id(), stored.asStored(), Set.copyOf(jdbc.sql("""
                SELECT order_id FROM tms.logistics_document_order WHERE document_id = :id AND company_id = :companyId
                """).param("id", stored.id()).param("companyId", companyId).query(UUID.class).list())));
    }

    private static boolean sameContent(Command stored, Command incoming) {
        return Objects.equals(stored.issueDate(), incoming.issueDate())
                && Objects.equals(stored.issuerTaxId(), incoming.issuerTaxId())
                && Objects.equals(stored.recipientTaxId(), incoming.recipientTaxId())
                && Objects.equals(stored.recipientName(), incoming.recipientName())
                && (stored.amount() == null ? incoming.amount() == null
                        : incoming.amount() != null && stored.amount().compareTo(incoming.amount()) == 0)
                && Objects.equals(stored.currency(), incoming.currency())
                && Objects.equals(stored.externalStatus(), incoming.externalStatus());
    }

    private static Map<String, Object> params(UUID companyId, Command command) {
        Map<String, Object> params = new HashMap<>();
        params.put("companyId", companyId);
        params.put("sourceSystem", command.sourceSystem());
        params.put("documentType", command.documentType());
        params.put("documentNumber", command.documentNumber());
        params.put("issueDate", command.issueDate());
        params.put("issuerTaxId", command.issuerTaxId());
        params.put("recipientTaxId", command.recipientTaxId());
        params.put("recipientName", command.recipientName());
        params.put("amount", command.amount());
        params.put("currency", command.currency());
        params.put("externalStatus", command.externalStatus());
        params.put("clientId", command.integrationClientId());
        return params;
    }

    private static Document document(ResultSet rs) throws SQLException {
        return new Document(rs.getObject("id", UUID.class), rs.getString("source_system"), rs.getString("document_type"),
                rs.getString("document_number"), rs.getObject("issue_date", java.time.LocalDate.class),
                rs.getString("recipient_name"), rs.getBigDecimal("amount"), rs.getString("currency"),
                rs.getString("external_status"), rs.getObject("received_at", OffsetDateTime.class));
    }
}
