package com.ebim.tms.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One part of a freight claim (migration V71): an order line, a quantity, an amount.
 *
 * <p>Its own aggregate member rather than a collection on {@link FreightClaim}: the lines are
 * rewritten whole while the claim is OPEN, and a JPA collection cleared and refilled flushes its
 * inserts before its deletes - which {@code uq_freight_claim_line_number} would refuse. The service
 * deletes, flushes and inserts, in that order.
 */
@Entity
@Table(name = "freight_claim_line")
public class FreightClaimLine {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "freight_claim_id", updatable = false, nullable = false)
    private UUID freightClaimId;

    @Column(name = "line_number", updatable = false, nullable = false)
    private int lineNumber;

    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    @Column(name = "order_line_id", updatable = false)
    private UUID orderLineId;

    @Column(name = "description", updatable = false, nullable = false)
    private String description;

    @Column(name = "quantity", updatable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "uom", updatable = false)
    private String uom;

    @Column(name = "amount", updatable = false, nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    protected FreightClaimLine() {
        // JPA
    }

    public FreightClaimLine(UUID companyId, UUID freightClaimId, int lineNumber, UUID orderId, UUID orderLineId,
            String description, BigDecimal quantity, String uom, BigDecimal amount) {
        this.companyId = companyId;
        this.freightClaimId = freightClaimId;
        this.lineNumber = lineNumber;
        this.orderId = orderId;
        this.orderLineId = orderLineId;
        this.description = description;
        this.quantity = quantity;
        this.uom = uom;
        this.amount = amount.setScale(2, RoundingMode.HALF_UP);
    }

    public UUID id() {
        return id;
    }

    public UUID freightClaimId() {
        return freightClaimId;
    }

    public int lineNumber() {
        return lineNumber;
    }

    public UUID orderId() {
        return orderId;
    }

    public UUID orderLineId() {
        return orderLineId;
    }

    public String description() {
        return description;
    }

    public BigDecimal quantity() {
        return quantity;
    }

    public String uom() {
        return uom;
    }

    public BigDecimal amount() {
        return amount;
    }
}
