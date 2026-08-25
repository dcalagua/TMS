package com.ebim.tms.rates.domain;

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
import org.hibernate.annotations.UuidGenerator;

/**
 * One persisted line of an estimate (migration V30) - the stored form of a {@link CostLine}.
 *
 * <p>A pure child of its {@link TripCost} in lifecycle terms: only ever created or removed through
 * {@link TripCost#recordEstimate}, which replaces the whole set. Nothing edits a line in place,
 * because a line has no meaning apart from the calculation that produced it.
 *
 * <p>Carries its own {@code companyId}, denormalized from the parent, for the same reason
 * {@code RouteStop} does: it is the target of a composite tenant foreign key
 * ({@code fk_trip_cost_component_cost_company}).
 */
@Entity
@Table(name = "trip_cost_component")
public class TripCostComponent {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_cost_id", nullable = false, updatable = false)
    private TripCost tripCost;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "component", updatable = false, nullable = false)
    private RateComponent component;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", updatable = false, nullable = false)
    private CostComponentStatus status;

    @Column(name = "rate", updatable = false, precision = 14, scale = 4)
    private BigDecimal rate;

    @Column(name = "quantity", updatable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", updatable = false)
    private CostUnit unit;

    @Enumerated(EnumType.STRING)
    @Column(name = "quantity_source", updatable = false)
    private CostQuantitySource quantitySource;

    @Column(name = "amount", updatable = false, nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", updatable = false)
    private CostComponentReason reason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    protected TripCostComponent() {
        // JPA
    }

    TripCostComponent(TripCost tripCost, UUID companyId, CostLine line) {
        this.tripCost = tripCost;
        this.companyId = companyId;
        this.component = line.component();
        this.status = line.status();
        this.rate = line.rate();
        this.quantity = line.quantity();
        this.unit = line.unit();
        this.quantitySource = line.quantitySource();
        this.amount = line.amount();
        this.reason = line.reason();
    }

    public UUID id() {
        return id;
    }

    public RateComponent component() {
        return component;
    }

    public CostComponentStatus status() {
        return status;
    }

    public BigDecimal rate() {
        return rate;
    }

    public BigDecimal quantity() {
        return quantity;
    }

    public CostUnit unit() {
        return unit;
    }

    public CostQuantitySource quantitySource() {
        return quantitySource;
    }

    public BigDecimal amount() {
        return amount;
    }

    public CostComponentReason reason() {
        return reason;
    }

    /** Whether this line contributed to the total - see {@link CostComponentStatus}. */
    public boolean isApplied() {
        return status == CostComponentStatus.APPLIED;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
