package com.ebim.tms.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * The three-way comparison, frozen at the moment it was computed (migration V46).
 *
 * <p>Stored rather than recomputed on read, for the reason V30 stored cost lines and V43 stored the
 * stop ETA: a figure somebody authorised an expenditure against must stay reproducible after the
 * shipments and the tolerance behind it have moved.
 *
 * <p>{@code expectedAmount} is nullable and <b>null means no expected figure exists</b>, never zero.
 * {@code ck_freight_match_unknown_is_not_matched} refuses to call such an invoice MATCHED.
 */
@Entity
@Table(name = "freight_match")
public class FreightMatch {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "carrier_invoice_id", updatable = false, nullable = false)
    private UUID carrierInvoiceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MatchStatus status;

    @Column(name = "expected_amount")
    private BigDecimal expectedAmount;

    @Column(name = "actual_amount")
    private BigDecimal actualAmount;

    @Column(name = "invoiced_amount", nullable = false)
    private BigDecimal invoicedAmount;

    @Column(name = "difference_amount")
    private BigDecimal differenceAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** What was compared against, snapshotted: a tolerance widened next month restates nothing. */
    @Column(name = "tolerance_absolute")
    private BigDecimal toleranceAbsolute;

    @Column(name = "tolerance_percentage")
    private BigDecimal tolerancePercentage;

    @Column(name = "matched_trip_count", nullable = false)
    private int matchedTripCount;

    @Column(name = "unmatched_line_count", nullable = false)
    private int unmatchedLineCount;

    @Column(name = "computed_at", nullable = false)
    private OffsetDateTime computedAt;

    @Column(name = "computed_by")
    private UUID computedBy;

    protected FreightMatch() {
    }

    public FreightMatch(UUID companyId, UUID carrierInvoiceId, String currency, FreightMatchResult result,
            Tolerance tolerance, UUID actorId) {
        this.companyId = companyId;
        this.carrierInvoiceId = carrierInvoiceId;
        this.currency = currency;
        this.computedAt = OffsetDateTime.now();
        this.computedBy = actorId;
        this.toleranceAbsolute = tolerance.absoluteAmount();
        this.tolerancePercentage = tolerance.percentage();
        apply(result);
    }

    /** Re-matching replaces the verdict in place - one current match per invoice (V46). */
    public void apply(FreightMatchResult result) {
        this.status = result.status();
        this.expectedAmount = result.expectedAmount();
        this.actualAmount = result.actualAmount();
        this.invoicedAmount = result.invoicedAmount();
        this.differenceAmount = result.differenceAmount();
        this.matchedTripCount = result.matchedTripCount();
        this.unmatchedLineCount = result.unmatchedLineCount();
        this.computedAt = OffsetDateTime.now();
    }

    public UUID id() {
        return id;
    }

    public UUID carrierInvoiceId() {
        return carrierInvoiceId;
    }

    public MatchStatus status() {
        return status;
    }

    public BigDecimal expectedAmount() {
        return expectedAmount;
    }

    public BigDecimal actualAmount() {
        return actualAmount;
    }

    public BigDecimal invoicedAmount() {
        return invoicedAmount;
    }

    public BigDecimal differenceAmount() {
        return differenceAmount;
    }

    public String currency() {
        return currency;
    }

    public BigDecimal toleranceAbsolute() {
        return toleranceAbsolute;
    }

    public BigDecimal tolerancePercentage() {
        return tolerancePercentage;
    }

    public int matchedTripCount() {
        return matchedTripCount;
    }

    public int unmatchedLineCount() {
        return unmatchedLineCount;
    }

    public OffsetDateTime computedAt() {
        return computedAt;
    }
}
