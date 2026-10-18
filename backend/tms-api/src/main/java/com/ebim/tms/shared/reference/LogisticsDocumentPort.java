package com.ebim.tms.shared.reference;

import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The documents that travel with orders (ADR-015, ADR-028): recorded from the ERP and read per order
 * or per trip. Informational - nothing here moves an order, a trip or a delivery, and TMS never
 * issues, signs or validates a document before a tax authority.
 */
public interface LogisticsDocumentPort {

    /** Creates or replaces a document by (sourceSystem, documentType, documentNumber). */
    Intake upsert(CompanyScope scope, Command command);

    /** The documents of each order in {@code orderIds}, batched and company-scoped. */
    Map<UUID, List<Document>> documentsOf(Set<UUID> orderIds, UUID companyId);

    /**
     * The fiscal identity of each document in {@code documentIds} that has one (ADR-028), batched and
     * company-scoped. A document received without any fiscal field is absent from the map.
     */
    Map<UUID, FiscalIdentity> fiscalIdentitiesOf(Collection<UUID> documentIds, UUID companyId);

    record Command(UUID integrationClientId, String sourceSystem, String documentType, String documentNumber,
            LocalDate issueDate, String issuerTaxId, String recipientTaxId, String recipientName, BigDecimal amount,
            String currency, String externalStatus, List<OrderExternalKey> orders, Fiscal fiscal,
            List<Recipient> recipients) {

        public Command {
            fiscal = fiscal == null ? Fiscal.NONE : fiscal;
            recipients = recipients == null ? List.of() : List.copyOf(recipients);
        }
    }

    /**
     * The tax authority's identity of the document and the transport it covers (ADR-028, V69). Every
     * field is optional; {@link #NONE} is a document received without any of them.
     *
     * @param fiscalCountry ISO 3166-1 alpha-2 of the issuing authority
     * @param fiscalDocumentCode the authority's code for the class of document ('06' SRI guia de
     *     remision, '09'/'31' SUNAT GRE), as sent
     * @param authorizationNumber SRI clave de acceso (49 digits) or SUNAT reference, as sent
     */
    record Fiscal(String fiscalCountry, String fiscalDocumentCode, String authorizationNumber,
            LocalDate transportStartDate, LocalDate transportEndDate, String carrierTaxId, String carrierName,
            String vehiclePlate, String originAddress, String transferReason) {

        public static final Fiscal NONE = new Fiscal(null, null, null, null, null, null, null, null, null, null);
    }

    /** A destinatario of a guide (ADR-028): who receives, where, why, under which sales document. */
    record Recipient(String taxId, String name, String destinationAddress, String transferReason,
            String destinationEstablishmentCode, String route, String relatedDocumentCode,
            String relatedDocumentNumber, String relatedDocumentAuthorizationNumber, LocalDate relatedDocumentDate,
            List<Item> items) {

        public Recipient {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /** A line of a guide, as received. Display-only: never reconciled against the order lines. */
    record Item(String code, String additionalCode, String description, BigDecimal quantity, String unit) {
    }

    /**
     * @param outcome CREATED, UPDATED or UNCHANGED
     * @param unknownOrders the order keys that matched no order of the company: linked later by a re-send
     */
    record Intake(UUID id, String outcome, List<OrderExternalKey> linkedOrders, List<OrderExternalKey> unknownOrders) {
    }

    record Document(UUID id, String sourceSystem, String documentType, String documentNumber, LocalDate issueDate,
            String recipientName, BigDecimal amount, String currency, String externalStatus, OffsetDateTime receivedAt,
            String issuerTaxId, String recipientTaxId, Fiscal fiscal, List<Recipient> recipients) {
    }

    /** What the carrier portal shows of a document's fiscal identity (ADR-028). */
    record FiscalIdentity(String fiscalCountry, String fiscalDocumentCode, String authorizationNumber) {
    }
}
