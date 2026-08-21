package com.ebim.tms.rates.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * What one trip was estimated at and what it actually cost (migration V30), together with the
 * lines that explain the estimate.
 *
 * <p>The two figures never overwrite each other. That is the entity's reason to exist: the number
 * a company is managed by is the <em>difference</em>, and a model where recording the invoice
 * replaced the quote would destroy it.
 *
 * <p>Owns its {@link TripCostComponent} lines - {@link #recordEstimate} replaces the whole set,
 * nothing edits one - and refuses every write once {@link #close} has been called, which is what
 * "settled" means here.
 */
@Entity
@Table(name = "trip_cost")
public class TripCost {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "trip_id", updatable = false, nullable = false)
    private UUID tripId;

    /**
     * The operating day of the trip this cost belongs to, copied from it at construction and never
     * changed - {@code updatable = false} so no code path can even try (migration V33).
     *
     * <p>Carried here rather than reached through {@code trip_id} so that a cost total over a date
     * range stays inside this module's table: the join that would otherwise answer it is
     * {@code rates} reading {@code planning}'s rows, which is the dependency
     * {@code ModuleBoundaryTest} exists to prevent. The same denormalization {@code Trip} itself
     * makes of {@code PlanningRun}'s date, for the same class of reason.
     */
    @Column(name = "planning_date", updatable = false, nullable = false)
    private LocalDate planningDate;

    /**
     * One currency for both halves, fixed by whichever of the two was recorded first. An actual in
     * another currency is refused by {@code TripCostService} rather than converted: TMS has no
     * rates of exchange and inventing one would be the most expensive kind of guess.
     */
    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "estimated_amount", precision = 14, scale = 2)
    private BigDecimal estimatedAmount;

    @Column(name = "estimated_at")
    private OffsetDateTime estimatedAt;

    @Column(name = "estimated_by")
    private UUID estimatedBy;

    @Column(name = "rate_card_id")
    private UUID rateCardId;

    /**
     * What the card said when the estimate was calculated. Snapshotted beside the live
     * {@link #rateCardId} for the same reason {@code Trip.snapshotMaxWeightKg} is (V11): a figure a
     * decision was made against must not be rewritten by a later edit to the master.
     */
    @Column(name = "rate_card_code")
    private String rateCardCode;

    @Column(name = "rate_card_name")
    private String rateCardName;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_card_scope")
    private RateCardScope rateCardScope;

    @Column(name = "actual_amount", precision = 14, scale = 2)
    private BigDecimal actualAmount;

    @Column(name = "actual_reference")
    private String actualReference;

    @Column(name = "actual_notes")
    private String actualNotes;

    @Column(name = "actual_recorded_at")
    private OffsetDateTime actualRecordedAt;

    @Column(name = "actual_recorded_by")
    private UUID actualRecordedBy;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "closed_by")
    private UUID closedBy;

    @OneToMany(mappedBy = "tripCost", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TripCostComponent> components = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected TripCost() {
        // JPA
    }

    /**
     * An empty cost for a trip. Never persisted in this state - {@code ck_trip_cost_not_empty}
     * refuses a row with neither figure - so a caller always follows it with
     * {@link #recordEstimate} or {@link #recordActual} in the same transaction.
     */
    public TripCost(UUID companyId, UUID tripId, LocalDate planningDate, String currency, UUID actorId) {
        this.companyId = companyId;
        this.tripId = tripId;
        this.planningDate = planningDate;
        this.currency = currency;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public UUID tripId() {
        return tripId;
    }

    public LocalDate planningDate() {
        return planningDate;
    }

    public String currency() {
        return currency;
    }

    public BigDecimal estimatedAmount() {
        return estimatedAmount;
    }

    public OffsetDateTime estimatedAt() {
        return estimatedAt;
    }

    public UUID estimatedBy() {
        return estimatedBy;
    }

    public UUID rateCardId() {
        return rateCardId;
    }

    public String rateCardCode() {
        return rateCardCode;
    }

    public String rateCardName() {
        return rateCardName;
    }

    public RateCardScope rateCardScope() {
        return rateCardScope;
    }

    public BigDecimal actualAmount() {
        return actualAmount;
    }

    public String actualReference() {
        return actualReference;
    }

    public String actualNotes() {
        return actualNotes;
    }

    public OffsetDateTime actualRecordedAt() {
        return actualRecordedAt;
    }

    public UUID actualRecordedBy() {
        return actualRecordedBy;
    }

    public OffsetDateTime closedAt() {
        return closedAt;
    }

    public UUID closedBy() {
        return closedBy;
    }

    public boolean isClosed() {
        return closedAt != null;
    }

    public boolean hasEstimate() {
        return estimatedAmount != null;
    }

    public boolean hasActual() {
        return actualAmount != null;
    }

    /** The estimate's lines, in {@link RateComponent} declaration order. */
    public List<TripCostComponent> components() {
        return components.stream()
                .sorted(Comparator.comparing(component -> component.component().ordinal()))
                .toList();
    }

    /**
     * {@code actual - estimated}, or null when either is missing. Positive means the shipment cost
     * more than the tariff said it would.
     *
     * <p>Derived and never stored: a stored variance is a third number that can disagree with the
     * two it is made of.
     */
    public BigDecimal variance() {
        return hasEstimate() && hasActual() ? actualAmount.subtract(estimatedAmount) : null;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public UUID updatedBy() {
        return updatedBy;
    }

    /**
     * Writes an estimate over whatever was there before, replacing every line.
     *
     * <p>Re-estimating is deliberately in-place rather than a second row: a shipment has one price
     * from one agreement, and keeping every calculation ever run would turn a cost into a log.
     * What was superseded is not lost - {@code AuditAction.COST_ESTIMATED} records each run with
     * its amount and its card.
     *
     * @throws IllegalStateException if the cost is closed, or if the estimate's currency differs
     *     from the one already recorded - both refused by {@code TripCostService} first, with a
     *     message an operator can act on; this is the last line of defense
     */
    public void recordEstimate(CostEstimate estimate, RateCard card, UUID actorId) {
        requireOpen();
        if (!currency.equals(estimate.currency())) {
            throw new IllegalStateException("trip cost is in " + currency + " and rate card "
                    + card.code() + " is in " + estimate.currency());
        }
        this.estimatedAmount = estimate.amount();
        this.estimatedAt = OffsetDateTime.now();
        this.estimatedBy = actorId;
        this.rateCardId = card.id();
        this.rateCardCode = card.code();
        this.rateCardName = card.name();
        this.rateCardScope = card.scope();
        components.clear();
        estimate.lines().forEach(line -> components.add(new TripCostComponent(this, companyId, line)));
        this.updatedBy = actorId;
    }

    /**
     * Removes the lines of the current estimate, leaving the estimate's own figures alone.
     *
     * <p>Separate from {@link #recordEstimate}, and called immediately before it <em>with a flush
     * in between</em> ({@code TripCostService.applyEstimate}), for a persistence reason worth
     * stating out loud: Hibernate executes every INSERT of a flush before its DELETEs, so
     * replacing the lines within one unit of work would try to insert the new {@code BASE} row
     * while the old one was still there and break
     * {@code uq_trip_cost_component_cost_component}. Two flushes cost one extra round trip on a
     * re-estimate and nothing at all on a first one.
     */
    public void clearComponents() {
        components.clear();
    }

    /**
     * Records - or corrects - what the carrier actually charged. Correctable until the cost is
     * closed, because an invoice arriving in two parts, or arriving wrong, is the ordinary case.
     */
    public void recordActual(BigDecimal amount, String reference, String notes, UUID actorId) {
        requireOpen();
        this.actualAmount = amount;
        this.actualReference = reference;
        this.actualNotes = notes;
        this.actualRecordedAt = OffsetDateTime.now();
        this.actualRecordedBy = actorId;
        this.updatedBy = actorId;
    }

    /**
     * Settles the cost: after this every write is refused, including a re-estimate, which would
     * restate a figure somebody has already paid against.
     *
     * @throws IllegalStateException if there is no actual to settle, or it is closed already
     */
    public void close(UUID actorId) {
        requireOpen();
        if (!hasActual()) {
            throw new IllegalStateException("a trip cost with no actual amount cannot be closed");
        }
        this.closedAt = OffsetDateTime.now();
        this.closedBy = actorId;
        this.updatedBy = actorId;
    }

    /**
     * Un-settles a cost that was closed by mistake.
     *
     * <p>Present, and audited as its own action, because the alternative is worse: without it a
     * mis-clicked Close freezes a wrong number permanently and the only remedy is somebody editing
     * the database by hand, which is exactly the kind of repair this product must never require.
     * It restores nothing and changes no figure - it only makes the row writable again, so the
     * correction that follows is itself recorded.
     */
    public void reopen(UUID actorId) {
        if (!isClosed()) {
            throw new IllegalStateException("a trip cost that is not closed cannot be reopened");
        }
        this.closedAt = null;
        this.closedBy = null;
        this.updatedBy = actorId;
    }

    private void requireOpen() {
        if (isClosed()) {
            throw new IllegalStateException("trip cost " + id + " is closed and cannot be changed");
        }
    }
}
