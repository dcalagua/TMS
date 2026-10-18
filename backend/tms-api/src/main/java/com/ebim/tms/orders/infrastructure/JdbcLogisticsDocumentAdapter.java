package com.ebim.tms.orders.infrastructure;

import com.ebim.tms.orders.domain.LogisticsDocumentShape;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.reference.LogisticsDocumentLinkListener;
import com.ebim.tms.shared.reference.LogisticsDocumentPort;
import com.ebim.tms.shared.reference.OrderExternalKey;
import com.ebim.tms.shared.reference.OrderReferencePort;
import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link LogisticsDocumentPort} over plain SQL (ADR-015, V55; ADR-028, V69): three small tables
 * written whole and read in batches, where an entity graph would add nothing. Every statement names
 * the company.
 */
@Repository
class JdbcLogisticsDocumentAdapter implements LogisticsDocumentPort {

    private static final TypeReference<List<Item>> ITEMS = new TypeReference<>() { };

    private static final String DOCUMENT_COLUMNS = """
            d.id, d.source_system, d.document_type, d.document_number, d.issue_date, d.issuer_tax_id,
            d.recipient_tax_id, d.recipient_name, d.amount, d.currency, d.external_status, d.received_at,
            d.fiscal_country, d.fiscal_document_code, d.authorization_number, d.transport_start_date,
            d.transport_end_date, d.carrier_tax_id, d.carrier_name, d.vehicle_plate, d.origin_address,
            d.transfer_reason""";

    private final JdbcClient jdbc;
    private final OrderReferencePort orderReferences;
    private final ObjectMapper json;
    /** Planning's document tracking (ADR-018), told of every new or changed link. */
    private final List<LogisticsDocumentLinkListener> linkListeners;

    JdbcLogisticsDocumentAdapter(JdbcClient jdbc, OrderReferencePort orderReferences, ObjectMapper json,
            List<LogisticsDocumentLinkListener> linkListeners) {
        this.jdbc = jdbc;
        this.orderReferences = orderReferences;
        this.json = json;
        this.linkListeners = List.copyOf(linkListeners);
    }

    private record Stored(UUID id, Command asStored, Set<UUID> orderIds) {
    }

    @Override
    @Transactional
    public Intake upsert(CompanyScope scope, Command command) {
        requireWellFormed(command);
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
                        external_status, integration_client_id, fiscal_country, fiscal_document_code,
                        authorization_number, transport_start_date, transport_end_date, carrier_tax_id,
                        carrier_name, vehicle_plate, origin_address, transfer_reason)
                    VALUES (:companyId, :sourceSystem, :documentType, :documentNumber, :issueDate, :issuerTaxId,
                        :recipientTaxId, :recipientName, :amount, :currency, :externalStatus, :clientId,
                        :fiscalCountry, :fiscalDocumentCode, :authorizationNumber, :transportStartDate,
                        :transportEndDate, :carrierTaxId, :carrierName, :vehiclePlate, :originAddress,
                        :transferReason)
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
                        fiscal_country = :fiscalCountry, fiscal_document_code = :fiscalDocumentCode,
                        authorization_number = :authorizationNumber, transport_start_date = :transportStartDate,
                        transport_end_date = :transportEndDate, carrier_tax_id = :carrierTaxId,
                        carrier_name = :carrierName, vehicle_plate = :vehiclePlate, origin_address = :originAddress,
                        transfer_reason = :transferReason, received_at = now(), version = version + 1
                    WHERE id = :id AND company_id = :companyId
                    """).paramSource(params).update();
            jdbc.sql("DELETE FROM tms.logistics_document_order WHERE document_id = :id AND company_id = :companyId")
                    .param("id", id).param("companyId", companyId).update();
            jdbc.sql("DELETE FROM tms.logistics_document_recipient WHERE document_id = :id AND company_id = :companyId")
                    .param("id", id).param("companyId", companyId).update();
            outcome = "UPDATED";
        }
        for (UUID orderId : orderIds) {
            jdbc.sql("""
                    INSERT INTO tms.logistics_document_order (document_id, order_id, company_id)
                    VALUES (:id, :orderId, :companyId)
                    """).param("id", id).param("orderId", orderId).param("companyId", companyId).update();
        }
        insertRecipients(companyId, id, command.recipients());
        if (!orderIds.isEmpty()) {
            for (LogisticsDocumentLinkListener listener : linkListeners) {
                listener.documentLinked(scope, id, command.documentType(), command.documentNumber(),
                        Set.copyOf(orderIds));
            }
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
        record Row(UUID orderId, Document document) {
        }
        List<Row> rows = jdbc.sql("SELECT lo.order_id, " + DOCUMENT_COLUMNS + """

                FROM tms.logistics_document_order lo
                JOIN tms.logistics_document d ON d.id = lo.document_id AND d.company_id = lo.company_id
                WHERE lo.company_id = :companyId AND lo.order_id IN (:orderIds)
                ORDER BY d.document_type, d.document_number
                """).param("companyId", companyId).param("orderIds", orderIds)
                .query((ResultSet rs, int row) -> new Row(rs.getObject("order_id", UUID.class), document(rs, List.of())))
                .list();
        Set<UUID> documentIds = new LinkedHashSet<>();
        rows.forEach(row -> documentIds.add(row.document().id()));
        Map<UUID, List<Recipient>> recipients = recipientsOf(companyId, documentIds);
        rows.forEach(row -> byOrder.computeIfAbsent(row.orderId(), key -> new ArrayList<>())
                .add(withRecipients(row.document(), recipients.getOrDefault(row.document().id(), List.of()))));
        return byOrder;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, FiscalIdentity> fiscalIdentitiesOf(Collection<UUID> documentIds, UUID companyId) {
        Map<UUID, FiscalIdentity> found = new HashMap<>();
        if (documentIds.isEmpty()) {
            return found;
        }
        jdbc.sql("""
                SELECT id, fiscal_country, fiscal_document_code, authorization_number
                FROM tms.logistics_document
                WHERE company_id = :companyId AND id IN (:ids)
                  AND (fiscal_country IS NOT NULL OR fiscal_document_code IS NOT NULL
                       OR authorization_number IS NOT NULL)
                """).param("companyId", companyId).param("ids", Set.copyOf(documentIds))
                .query((rs, row) -> found.put(rs.getObject("id", UUID.class),
                        new FiscalIdentity(rs.getString("fiscal_country"), rs.getString("fiscal_document_code"),
                                rs.getString("authorization_number"))))
                .list();
        return found;
    }

    /** ADR-028: shape only, never against the tax authority. One 400 naming every problem. */
    private static void requireWellFormed(Command command) {
        Fiscal fiscal = command.fiscal();
        List<String> violations = LogisticsDocumentShape.violations(new LogisticsDocumentShape.Subject(
                command.documentType(), command.documentNumber(), command.issuerTaxId(), fiscal.fiscalCountry(),
                fiscal.fiscalDocumentCode(), fiscal.authorizationNumber(), fiscal.transportStartDate(),
                fiscal.transportEndDate()));
        if (!violations.isEmpty()) {
            throw new InvalidRequestException(String.join(" ", violations));
        }
    }

    private void insertRecipients(UUID companyId, UUID documentId, List<Recipient> recipients) {
        int sequence = 0;
        for (Recipient recipient : recipients) {
            sequence++;
            Map<String, Object> params = new HashMap<>();
            params.put("documentId", documentId);
            params.put("companyId", companyId);
            params.put("sequence", sequence);
            params.put("taxId", recipient.taxId());
            params.put("name", recipient.name());
            params.put("destinationAddress", recipient.destinationAddress());
            params.put("transferReason", recipient.transferReason());
            params.put("establishment", recipient.destinationEstablishmentCode());
            params.put("route", recipient.route());
            params.put("relatedCode", recipient.relatedDocumentCode());
            params.put("relatedNumber", recipient.relatedDocumentNumber());
            params.put("relatedAuthorization", recipient.relatedDocumentAuthorizationNumber());
            params.put("relatedDate", recipient.relatedDocumentDate());
            params.put("items", json.writeValueAsString(recipient.items()));
            jdbc.sql("""
                    INSERT INTO tms.logistics_document_recipient (document_id, company_id, sequence, tax_id, name,
                        destination_address, transfer_reason, destination_establishment_code, route,
                        related_document_code, related_document_number, related_document_authorization_number,
                        related_document_date, items)
                    VALUES (:documentId, :companyId, :sequence, :taxId, :name, :destinationAddress, :transferReason,
                        :establishment, :route, :relatedCode, :relatedNumber, :relatedAuthorization, :relatedDate,
                        CAST(:items AS jsonb))
                    """).paramSource(params).update();
        }
    }

    private Map<UUID, List<Recipient>> recipientsOf(UUID companyId, Set<UUID> documentIds) {
        Map<UUID, List<Recipient>> byDocument = new LinkedHashMap<>();
        if (documentIds.isEmpty()) {
            return byDocument;
        }
        jdbc.sql("""
                SELECT document_id, tax_id, name, destination_address, transfer_reason,
                       destination_establishment_code, route, related_document_code, related_document_number,
                       related_document_authorization_number, related_document_date, items::text AS items
                FROM tms.logistics_document_recipient
                WHERE company_id = :companyId AND document_id IN (:ids)
                ORDER BY document_id, sequence
                """).param("companyId", companyId).param("ids", documentIds)
                .query((rs, row) -> byDocument.computeIfAbsent(rs.getObject("document_id", UUID.class),
                        key -> new ArrayList<>()).add(new Recipient(rs.getString("tax_id"), rs.getString("name"),
                                rs.getString("destination_address"), rs.getString("transfer_reason"),
                                rs.getString("destination_establishment_code"), rs.getString("route"),
                                rs.getString("related_document_code"), rs.getString("related_document_number"),
                                rs.getString("related_document_authorization_number"),
                                rs.getObject("related_document_date", LocalDate.class),
                                json.readValue(rs.getString("items"), ITEMS))))
                .list();
        return byDocument;
    }

    private Optional<Stored> find(UUID companyId, Command command) {
        Optional<Document> found = jdbc.sql("SELECT " + DOCUMENT_COLUMNS + """

                FROM tms.logistics_document d
                WHERE d.company_id = :companyId AND d.source_system = :sourceSystem
                  AND d.document_type = :documentType AND d.document_number = :documentNumber
                """).param("companyId", companyId).param("sourceSystem", command.sourceSystem())
                .param("documentType", command.documentType()).param("documentNumber", command.documentNumber())
                .query((rs, row) -> document(rs, List.of()))
                .optional();
        return found.map(document -> {
            List<Recipient> recipients = recipientsOf(companyId, Set.of(document.id()))
                    .getOrDefault(document.id(), List.of());
            Command asStored = new Command(null, command.sourceSystem(), command.documentType(),
                    command.documentNumber(), document.issueDate(), document.issuerTaxId(), document.recipientTaxId(),
                    document.recipientName(), document.amount(), document.currency(), document.externalStatus(),
                    List.of(), document.fiscal(), recipients);
            Set<UUID> orderIds = Set.copyOf(jdbc.sql("""
                    SELECT order_id FROM tms.logistics_document_order WHERE document_id = :id AND company_id = :companyId
                    """).param("id", document.id()).param("companyId", companyId).query(UUID.class).list());
            return new Stored(document.id(), asStored, orderIds);
        });
    }

    private static boolean sameContent(Command stored, Command incoming) {
        return Objects.equals(stored.issueDate(), incoming.issueDate())
                && Objects.equals(stored.issuerTaxId(), incoming.issuerTaxId())
                && Objects.equals(stored.recipientTaxId(), incoming.recipientTaxId())
                && Objects.equals(stored.recipientName(), incoming.recipientName())
                && sameAmount(stored.amount(), incoming.amount())
                && Objects.equals(stored.currency(), incoming.currency())
                && Objects.equals(stored.externalStatus(), incoming.externalStatus())
                && Objects.equals(stored.fiscal(), incoming.fiscal())
                && normalised(stored.recipients()).equals(normalised(incoming.recipients()));
    }

    private static boolean sameAmount(BigDecimal stored, BigDecimal incoming) {
        return stored == null ? incoming == null : incoming != null && stored.compareTo(incoming) == 0;
    }

    /** 12.50 and 12.5 are the same quantity: compared by value, not by scale. */
    private static List<Recipient> normalised(List<Recipient> recipients) {
        return recipients.stream().map(recipient -> new Recipient(recipient.taxId(), recipient.name(),
                recipient.destinationAddress(), recipient.transferReason(), recipient.destinationEstablishmentCode(),
                recipient.route(), recipient.relatedDocumentCode(), recipient.relatedDocumentNumber(),
                recipient.relatedDocumentAuthorizationNumber(), recipient.relatedDocumentDate(),
                recipient.items().stream().map(item -> new Item(item.code(), item.additionalCode(), item.description(),
                        item.quantity() == null ? null : item.quantity().stripTrailingZeros(), item.unit())).toList()))
                .toList();
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
        Fiscal fiscal = command.fiscal();
        params.put("fiscalCountry", fiscal.fiscalCountry());
        params.put("fiscalDocumentCode", fiscal.fiscalDocumentCode());
        params.put("authorizationNumber", fiscal.authorizationNumber());
        params.put("transportStartDate", fiscal.transportStartDate());
        params.put("transportEndDate", fiscal.transportEndDate());
        params.put("carrierTaxId", fiscal.carrierTaxId());
        params.put("carrierName", fiscal.carrierName());
        params.put("vehiclePlate", fiscal.vehiclePlate());
        params.put("originAddress", fiscal.originAddress());
        params.put("transferReason", fiscal.transferReason());
        return params;
    }

    private static Document document(ResultSet rs, List<Recipient> recipients) throws SQLException {
        Fiscal fiscal = new Fiscal(rs.getString("fiscal_country"), rs.getString("fiscal_document_code"),
                rs.getString("authorization_number"), rs.getObject("transport_start_date", LocalDate.class),
                rs.getObject("transport_end_date", LocalDate.class), rs.getString("carrier_tax_id"),
                rs.getString("carrier_name"), rs.getString("vehicle_plate"), rs.getString("origin_address"),
                rs.getString("transfer_reason"));
        return new Document(rs.getObject("id", UUID.class), rs.getString("source_system"), rs.getString("document_type"),
                rs.getString("document_number"), rs.getObject("issue_date", LocalDate.class),
                rs.getString("recipient_name"), rs.getBigDecimal("amount"), rs.getString("currency"),
                rs.getString("external_status"), rs.getObject("received_at", OffsetDateTime.class),
                rs.getString("issuer_tax_id"), rs.getString("recipient_tax_id"), fiscal, recipients);
    }

    private static Document withRecipients(Document document, List<Recipient> recipients) {
        return new Document(document.id(), document.sourceSystem(), document.documentType(), document.documentNumber(),
                document.issueDate(), document.recipientName(), document.amount(), document.currency(),
                document.externalStatus(), document.receivedAt(), document.issuerTaxId(), document.recipientTaxId(),
                document.fiscal(), recipients);
    }
}
