package com.ebim.tms.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One line of a carrier's invoice (migration V46).
 *
 * <p>{@code tripId} is <b>optional</b>, and that is deliberate. An accessorial billed against no
 * particular shipment - a monthly surcharge, a demurrage claim - is a real line, and requiring a
 * trip would force somebody to attach it to an arbitrary one. What a line without a trip cannot do
 * is match, which is exactly what {@link DiscrepancyType#UNMATCHED_TRIP} records.
 */
@Entity
@Table(name = "carrier_invoice_line")
public class CarrierInvoiceLine {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "carrier_invoice_id", nullable = false)
    private CarrierInvoice invoice;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "trip_id")
    private UUID tripId;

    @Column(name = "description", nullable = false)
    private String description;

    /** Quantity and unit amount travel together or not at all - see the V46 CHECK. */
    @Column(name = "quantity")
    private BigDecimal quantity;

    @Column(name = "unit_amount")
    private BigDecimal unitAmount;

    @Column(name = "line_amount", nullable = false)
    private BigDecimal lineAmount;

    /**
     * Which charge this line bills (V64). Null on every V46 line and read as
     * {@link ChargeCode#FREIGHT}; stored as given, so what was keyed stays what was keyed.
     */
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "charge_code")
    private ChargeCode chargeCode;

    /** Why the shipper deducts (V66); required on a PENALTY line, absent on every other. */
    @Column(name = "penalty_reason")
    private String penaltyReason;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "penalty_reference_type")
    private PenaltyReferenceType penaltyReferenceType;

    @Column(name = "penalty_reference_id")
    private UUID penaltyReferenceId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected CarrierInvoiceLine() {
    }

    public CarrierInvoiceLine(UUID companyId, UUID tripId, String description, BigDecimal quantity,
            BigDecimal unitAmount, BigDecimal lineAmount) {
        this(companyId, tripId, description, quantity, unitAmount, lineAmount, null);
    }

    public CarrierInvoiceLine(UUID companyId, UUID tripId, String description, BigDecimal quantity,
            BigDecimal unitAmount, BigDecimal lineAmount, ChargeCode chargeCode) {
        this.chargeCode = chargeCode;
        this.companyId = companyId;
        this.tripId = tripId;
        this.description = description;
        this.quantity = quantity;
        this.unitAmount = unitAmount;
        this.lineAmount = lineAmount;
        if (lineAmount != null && lineAmount.signum() < 0 && this.chargeCode != ChargeCode.PENALTY) {
            // ck_carrier_invoice_line_sign (V66): a negative amount is a deduction, and only a
            // penalty is one. A credit note is its own document with positive amounts.
            throw new IllegalArgumentException("only a PENALTY line carries a negative amount;"
                    + " a credit is a credit note (07)");
        }
        if (this.chargeCode == ChargeCode.PENALTY && (lineAmount == null || lineAmount.signum() >= 0)) {
            throw new IllegalArgumentException("a PENALTY line is a deduction: its amount is negative");
        }
        if ((quantity == null) != (unitAmount == null)) {
            // Mirrors ck_carrier_invoice_line_unit_block: an amount per unit with no unit count is
            // not a partial record, it is an unanswerable one.
            throw new IllegalArgumentException(
                    "a line states a quantity and a unit amount together, or neither");
        }
    }

    /**
     * Why the shipper deducts and what for (V66, ADR-024 part 2, item 6). Only on a PENALTY line,
     * and there the reason is required.
     */
    public CarrierInvoiceLine withPenalty(String reason, PenaltyReferenceType referenceType, UUID referenceId) {
        boolean any = reason != null || referenceType != null || referenceId != null;
        if (chargeCode != ChargeCode.PENALTY) {
            if (any) {
                throw new IllegalArgumentException("only a PENALTY line states a penalty reason or reference");
            }
            return this;
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a PENALTY line says why the shipper deducts it");
        }
        if (referenceId != null && referenceType == null) {
            throw new IllegalArgumentException("a penalty reference id needs its type");
        }
        this.penaltyReason = reason.trim();
        this.penaltyReferenceType = referenceType;
        this.penaltyReferenceId = referenceId;
        return this;
    }

    void attachTo(CarrierInvoice invoice, int lineNumber) {
        this.invoice = invoice;
        this.lineNumber = lineNumber;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public int lineNumber() {
        return lineNumber;
    }

    public UUID tripId() {
        return tripId;
    }

    public String description() {
        return description;
    }

    public BigDecimal quantity() {
        return quantity;
    }

    public BigDecimal unitAmount() {
        return unitAmount;
    }

    public BigDecimal lineAmount() {
        return lineAmount;
    }

    /** The charge this line bills; a V46 line, which has none, reads as {@link ChargeCode#FREIGHT}. */
    public ChargeCode chargeCode() {
        return ChargeCode.orFreight(chargeCode);
    }

    public String penaltyReason() {
        return penaltyReason;
    }

    public PenaltyReferenceType penaltyReferenceType() {
        return penaltyReferenceType;
    }

    public UUID penaltyReferenceId() {
        return penaltyReferenceId;
    }
}
