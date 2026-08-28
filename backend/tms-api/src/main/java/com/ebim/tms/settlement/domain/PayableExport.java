package com.ebim.tms.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * The hand-off to whoever pays (migration V46).
 *
 * <p><b>TMS does not pay.</b> This is the artifact an ERP consumes, and the row that proves it was
 * handed over once. {@code uq_payable_export_invoice} makes "once" a database fact: two clicks on
 * Export cannot create two obligations.
 *
 * <p>The payload is stored as it was handed over, so "what exactly did we send to accounting" is
 * answerable a year later without regenerating it from data that has since moved - the same argument
 * V30 makes for snapshotting the winning rate card.
 */
@Entity
@Table(name = "payable_export")
public class PayableExport {

    public enum Format { JSON, CSV }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "carrier_invoice_id", updatable = false, nullable = false)
    private UUID carrierInvoiceId;

    /** What a downstream system deduplicates on, so a retried export is the same obligation. */
    @Column(name = "export_reference", updatable = false, nullable = false)
    private String exportReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", updatable = false, nullable = false)
    private Format format;

    @Column(name = "payload", updatable = false, nullable = false)
    private String payload;

    @Column(name = "exported_at", updatable = false, nullable = false)
    private OffsetDateTime exportedAt;

    @Column(name = "exported_by", updatable = false, nullable = false)
    private UUID exportedBy;

    protected PayableExport() {
    }

    public PayableExport(UUID companyId, UUID carrierInvoiceId, String exportReference, Format format,
            String payload, UUID exportedBy) {
        this.companyId = companyId;
        this.carrierInvoiceId = carrierInvoiceId;
        this.exportReference = exportReference;
        this.format = format;
        this.payload = payload;
        this.exportedBy = exportedBy;
        this.exportedAt = OffsetDateTime.now();
    }

    public UUID id() {
        return id;
    }

    public UUID carrierInvoiceId() {
        return carrierInvoiceId;
    }

    public String exportReference() {
        return exportReference;
    }

    public Format format() {
        return format;
    }

    public String payload() {
        return payload;
    }

    public OffsetDateTime exportedAt() {
        return exportedAt;
    }
}
