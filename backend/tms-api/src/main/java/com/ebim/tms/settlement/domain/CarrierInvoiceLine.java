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
        this.companyId = companyId;
        this.tripId = tripId;
        this.description = description;
        this.quantity = quantity;
        this.unitAmount = unitAmount;
        this.lineAmount = lineAmount;
        if ((quantity == null) != (unitAmount == null)) {
            // Mirrors ck_carrier_invoice_line_unit_block: an amount per unit with no unit count is
            // not a partial record, it is an unanswerable one.
            throw new IllegalArgumentException(
                    "a line states a quantity and a unit amount together, or neither");
        }
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
}
