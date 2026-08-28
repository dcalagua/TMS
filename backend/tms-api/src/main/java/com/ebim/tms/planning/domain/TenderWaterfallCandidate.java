package com.ebim.tms.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One carrier's place on a waterfall's list, and what became of it (migration V40).
 *
 * <p>The quoted amount is a <b>snapshot</b>, not a live figure: it is what this carrier was ranked
 * on at the moment the list was built, kept with the rate card that produced it. A price re-read
 * later could differ - cards are edited, cards expire - and the whole value of a stored ranking is
 * that the order a dispatcher approved is the order that gets walked.
 */
@Entity
@Table(name = "tender_waterfall_candidate")
public class TenderWaterfallCandidate {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "waterfall_id", nullable = false, updatable = false)
    private TenderWaterfall waterfall;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "rank", updatable = false, nullable = false)
    private int rank;

    @Column(name = "carrier_id", updatable = false, nullable = false)
    private UUID carrierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private WaterfallCandidateStatus status = WaterfallCandidateStatus.PENDING;

    @Column(name = "quoted_amount", updatable = false, precision = 14, scale = 2)
    private BigDecimal quotedAmount;

    @Column(name = "quoted_currency", updatable = false)
    private String quotedCurrency;

    @Column(name = "rate_card_id", updatable = false)
    private UUID rateCardId;

    @Column(name = "tender_id")
    private UUID tenderId;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected TenderWaterfallCandidate() {}

    TenderWaterfallCandidate(TenderWaterfall waterfall, UUID companyId, int rank, UUID carrierId,
            BigDecimal quotedAmount, String quotedCurrency, UUID rateCardId) {
        this.waterfall = waterfall;
        this.companyId = companyId;
        this.rank = rank;
        this.carrierId = carrierId;
        this.quotedAmount = quotedAmount;
        this.quotedCurrency = quotedCurrency;
        this.rateCardId = rateCardId;
    }

    /** The offer went out, through {@code tenderId}. */
    public void offered(UUID tenderId) {
        this.status = WaterfallCandidateStatus.OFFERED;
        this.tenderId = tenderId;
        this.decidedAt = null;
    }

    /**
     * The carrier answered, or the clock did.
     *
     * <p>Refuses anything but a decided status, so a caller cannot walk a candidate backwards into
     * {@code PENDING} and have the waterfall offer it a second time - which would let one refusal
     * consume two attempts, or none.
     */
    public void decided(WaterfallCandidateStatus outcome, OffsetDateTime at) {
        if (!outcome.isDecided() || outcome == WaterfallCandidateStatus.SKIPPED) {
            throw new IllegalArgumentException(outcome + " is not an answer to an offer");
        }
        this.status = outcome;
        this.decidedAt = at;
    }

    /** Never offered, because the waterfall ended first. */
    void skip(OffsetDateTime at) {
        this.status = WaterfallCandidateStatus.SKIPPED;
        this.decidedAt = at;
    }

    public UUID id() {
        return id;
    }

    public int rank() {
        return rank;
    }

    public UUID carrierId() {
        return carrierId;
    }

    public WaterfallCandidateStatus status() {
        return status;
    }

    public BigDecimal quotedAmount() {
        return quotedAmount;
    }

    public String quotedCurrency() {
        return quotedCurrency;
    }

    public UUID rateCardId() {
        return rateCardId;
    }

    public UUID tenderId() {
        return tenderId;
    }

    public OffsetDateTime decidedAt() {
        return decidedAt;
    }
}
