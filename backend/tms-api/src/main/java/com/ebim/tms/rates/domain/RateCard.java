package com.ebim.tms.rates.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * What one carrier charges this company for a shipment, between two dates (migration V30).
 *
 * <p>A card is <em>deactivated, never deleted</em>, like every other master in this schema: a trip
 * costed from it keeps pointing at it, and {@code fk_trip_cost_rate_card} makes deletion
 * impossible while one does. What it said at the time is snapshotted onto {@link TripCost}
 * separately, so editing a card tomorrow never restates yesterday's estimate.
 *
 * <p>Carries no calculation of its own beyond the four questions selection asks
 * ({@link #coversDate}, {@link #appliesToScopeOf}, {@link #appliesToVehicleType},
 * {@link #hasAnyComponent}). Turning a card plus a shipment into money is
 * {@link TripCostCalculator}'s job, which is a pure function precisely so it can be tested without
 * any of this.
 */
@Entity
@Table(name = "rate_card")
public class RateCard {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    /**
     * Who is paid under this agreement. Never null and never edited to another carrier: a card is
     * an agreement with one counterparty, and re-pointing it at a second one would silently
     * restate every estimate that has ever cited it. Moving a tariff to another carrier is a new
     * card - see {@link #applyChanges}, which does not take one.
     */
    @Column(name = "carrier_id", updatable = false, nullable = false)
    private UUID carrierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false)
    private RateCardScope scope;

    /** Set exactly when {@link #scope} is {@link RateCardScope#ORIGIN} ({@code ck_rate_card_scope_target}). */
    @Column(name = "origin_id")
    private UUID originId;

    /** Set exactly when {@link #scope} is {@link RateCardScope#ROUTE}. */
    @Column(name = "route_id")
    private UUID routeId;

    /** Null means "any vehicle type" - see {@link #appliesToVehicleType}. */
    @Column(name = "vehicle_type_id")
    private UUID vehicleTypeId;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    /** Inclusive; null is an open-ended agreement. */
    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "base_amount", precision = 14, scale = 2)
    private BigDecimal baseAmount;

    @Column(name = "amount_per_km", precision = 14, scale = 4)
    private BigDecimal amountPerKm;

    @Column(name = "amount_per_kg", precision = 14, scale = 4)
    private BigDecimal amountPerKg;

    @Column(name = "amount_per_m3", precision = 14, scale = 4)
    private BigDecimal amountPerM3;

    @Column(name = "amount_per_pallet", precision = 14, scale = 4)
    private BigDecimal amountPerPallet;

    @Column(name = "minimum_amount", precision = 14, scale = 2)
    private BigDecimal minimumAmount;

    // The V39 components. Every one nullable, and null means "this agreement says nothing about
    // it" rather than "it is zero" - the distinction the migration's closing note spends a
    // paragraph on, because only the first is true.
    @Column(name = "amount_per_stop", precision = 14, scale = 4)
    private BigDecimal amountPerStop;

    @Column(name = "fuel_surcharge_percent", precision = 7, scale = 4)
    private BigDecimal fuelSurchargePercent;

    @Column(name = "amount_per_waiting_hour", precision = 14, scale = 4)
    private BigDecimal amountPerWaitingHour;

    @Column(name = "toll_amount", precision = 14, scale = 2)
    private BigDecimal tollAmount;

    @Column(name = "accessorial_amount", precision = 14, scale = 2)
    private BigDecimal accessorialAmount;

    @Column(name = "accessorial_label")
    private String accessorialLabel;

    @Column(name = "maximum_amount", precision = 14, scale = 2)
    private BigDecimal maximumAmount;

    @Column(name = "destination_id")
    private UUID destinationId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

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

    protected RateCard() {
        // JPA
    }

    /** The pre-V39 shape: no lane, so no destination. */
    public RateCard(UUID companyId, String code, String name, UUID carrierId, RateCardScope scope, UUID originId,
            UUID routeId, UUID vehicleTypeId, String currency, LocalDate validFrom, LocalDate validTo,
            RateComponents components, UUID actorId) {
        this(companyId, code, name, carrierId, scope, originId, null, routeId, vehicleTypeId, currency,
                validFrom, validTo, components, actorId);
    }

    public RateCard(UUID companyId, String code, String name, UUID carrierId, RateCardScope scope, UUID originId,
            UUID destinationId, UUID routeId, UUID vehicleTypeId, String currency, LocalDate validFrom,
            LocalDate validTo, RateComponents components, UUID actorId) {
        this.companyId = companyId;
        this.destinationId = destinationId;
        this.code = code;
        this.name = name;
        this.carrierId = carrierId;
        this.scope = scope;
        this.originId = originId;
        this.routeId = routeId;
        this.vehicleTypeId = vehicleTypeId;
        this.currency = currency;
        this.validFrom = validFrom;
        this.validTo = validTo;
        applyComponents(components);
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public UUID carrierId() {
        return carrierId;
    }

    public RateCardScope scope() {
        return scope;
    }

    public UUID originId() {
        return originId;
    }

    public UUID routeId() {
        return routeId;
    }

    public UUID vehicleTypeId() {
        return vehicleTypeId;
    }

    public String currency() {
        return currency;
    }

    public LocalDate validFrom() {
        return validFrom;
    }

    public LocalDate validTo() {
        return validTo;
    }

    public BigDecimal baseAmount() {
        return baseAmount;
    }

    public BigDecimal amountPerKm() {
        return amountPerKm;
    }

    public BigDecimal amountPerKg() {
        return amountPerKg;
    }

    public BigDecimal amountPerM3() {
        return amountPerM3;
    }

    public BigDecimal amountPerPallet() {
        return amountPerPallet;
    }

    public BigDecimal minimumAmount() {
        return minimumAmount;
    }

    public boolean active() {
        return active;
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

    /** The rate for one measured component, or null when this card does not charge for it. */
    public BigDecimal rateFor(RateComponent component) {
        return switch (component) {
            case DISTANCE -> amountPerKm;
            case WEIGHT -> amountPerKg;
            case VOLUME -> amountPerM3;
            case PALLETS -> amountPerPallet;
            case STOP_OFF -> amountPerStop;
            case FUEL_SURCHARGE -> fuelSurchargePercent;
            case WAITING_TIME -> amountPerWaitingHour;
            case BASE, TOLL, OTHER_ACCESSORIAL, MINIMUM_ADJUSTMENT, MAXIMUM_ADJUSTMENT ->
                    throw new IllegalArgumentException(component + " is a flat amount and has no unit rate");
        };
    }

    /** The flat amount for one component, or null when this card does not charge it. */
    public BigDecimal flatAmountFor(RateComponent component) {
        return switch (component) {
            case BASE -> baseAmount;
            case TOLL -> tollAmount;
            case OTHER_ACCESSORIAL -> accessorialAmount;
            case DISTANCE, WEIGHT, VOLUME, PALLETS, STOP_OFF, FUEL_SURCHARGE, WAITING_TIME,
                 MINIMUM_ADJUSTMENT, MAXIMUM_ADJUSTMENT ->
                    throw new IllegalArgumentException(component + " is not a flat charge");
        };
    }

    public BigDecimal amountPerStop() {
        return amountPerStop;
    }

    public BigDecimal fuelSurchargePercent() {
        return fuelSurchargePercent;
    }

    public BigDecimal amountPerWaitingHour() {
        return amountPerWaitingHour;
    }

    public BigDecimal tollAmount() {
        return tollAmount;
    }

    public BigDecimal accessorialAmount() {
        return accessorialAmount;
    }

    /** What the accessorial appears under on a breakdown. Travels with the amount or not at all. */
    public String accessorialLabel() {
        return accessorialLabel;
    }

    public BigDecimal maximumAmount() {
        return maximumAmount;
    }

    /** The lane's far end. Set only for {@link RateCardScope#LANE}. */
    public UUID destinationId() {
        return destinationId;
    }

    /**
     * Whether this card charges anything at all - {@code ck_rate_card_has_a_component}'s twin.
     *
     * <p>Neither the minimum nor the maximum counts, which V30 decided and V39 keeps: a floor is a
     * rule <em>about</em> other charges, not a charge. A card saying only "never less than 200"
     * states a constraint on a price that does not exist, and pricing from it would conjure 200
     * out of nothing.
     */
    public boolean hasAnyComponent() {
        return baseAmount != null || amountPerKm != null || amountPerKg != null
                || amountPerM3 != null || amountPerPallet != null
                || amountPerStop != null || fuelSurchargePercent != null || amountPerWaitingHour != null
                || tollAmount != null || accessorialAmount != null;
    }

    /**
     * Whether the agreement is in force on {@code date}, both bounds inclusive. The date asked
     * about is always the trip's planning date and never today - see {@code CostableTrip}.
     */
    public boolean coversDate(LocalDate date) {
        return !date.isBefore(validFrom) && (validTo == null || !date.isAfter(validTo));
    }

    /**
     * Whether this card's scope covers a shipment leaving {@code tripOriginId} and built from
     * {@code tripRouteId} (either of which may be null).
     *
     * <p>A {@code CARRIER}-scoped card covers everything. The other two are equality on the id
     * they name - and a card that names a corridor the shipment was not built from does not cover
     * it, even if the shipment happens to visit the same places. A route is what the planner
     * <em>chose</em>, and pricing by coincidence is not pricing.
     */
    public boolean appliesToScopeOf(UUID tripOriginId, UUID tripRouteId, UUID tripDestinationId) {
        return switch (scope) {
            case CARRIER -> true;
            case ORIGIN -> Objects.equals(originId, tripOriginId);
            // Both ends, and a null destination never matches: a multi-drop shipment has no lane
            // (see CostableTrip.soleDestinationId), and Objects.equals(null, null) would otherwise
            // price it against whichever lane card also happened to have a null - which is exactly
            // the "pricing by coincidence" this method's own comment refuses.
            case LANE -> tripDestinationId != null
                    && Objects.equals(originId, tripOriginId)
                    && Objects.equals(destinationId, tripDestinationId);
            case ROUTE -> Objects.equals(routeId, tripRouteId);
        };
    }

    /**
     * Whether this card may price a shipment running on {@code tripVehicleTypeId}. A card naming
     * no type applies to any vehicle; a card naming one applies only to that type - never as a
     * fallback for a trip whose type is unknown, because "we do not know what it goes on" is not a
     * reason to charge the articulated rate.
     */
    public boolean appliesToVehicleType(UUID tripVehicleTypeId) {
        return vehicleTypeId == null || vehicleTypeId.equals(tripVehicleTypeId);
    }

    /**
     * Whether this card's validity overlaps {@code otherFrom}..{@code otherTo} - both bounds
     * inclusive, and {@code otherTo} null meaning open-ended.
     */
    public boolean overlaps(LocalDate otherFrom, LocalDate otherTo) {
        boolean startsAfterOtherEnds = otherTo != null && validFrom.isAfter(otherTo);
        boolean endsBeforeOtherStarts = validTo != null && validTo.isBefore(otherFrom);
        return !startsAfterOtherEnds && !endsBeforeOtherStarts;
    }

    /**
     * Whether this card is the same agreement as one described by these four keys - the identity
     * {@code RateCardService} checks overlaps within, and the same tuple
     * {@code uq_rate_card_active_agreement} is built on.
     */
    public boolean isSameAgreementAs(UUID otherCarrierId, RateCardScope otherScope, UUID otherOriginId,
            UUID otherRouteId, UUID otherVehicleTypeId) {
        return carrierId.equals(otherCarrierId)
                && scope == otherScope
                && Objects.equals(originId, otherOriginId)
                && Objects.equals(routeId, otherRouteId)
                && Objects.equals(vehicleTypeId, otherVehicleTypeId);
    }

    /**
     * Edits everything about the agreement except who it is with. {@code carrierId} is absent on
     * purpose - see the field comment.
     */
    /** The pre-V39 shape: no lane, so no destination. */
    public void applyChanges(String code, String name, RateCardScope scope, UUID originId, UUID routeId,
            UUID vehicleTypeId, String currency, LocalDate validFrom, LocalDate validTo,
            RateComponents components, UUID actorId) {
        applyChanges(code, name, scope, originId, null, routeId, vehicleTypeId, currency, validFrom, validTo,
                components, actorId);
    }

    public void applyChanges(String code, String name, RateCardScope scope, UUID originId, UUID destinationId,
            UUID routeId, UUID vehicleTypeId, String currency, LocalDate validFrom, LocalDate validTo,
            RateComponents components, UUID actorId) {
        this.code = code;
        this.destinationId = destinationId;
        this.name = name;
        this.scope = scope;
        this.originId = originId;
        this.routeId = routeId;
        this.vehicleTypeId = vehicleTypeId;
        this.currency = currency;
        this.validFrom = validFrom;
        this.validTo = validTo;
        applyComponents(components);
        this.updatedBy = actorId;
    }

    public void activate(UUID actorId) {
        this.active = true;
        this.updatedBy = actorId;
    }

    public void deactivate(UUID actorId) {
        this.active = false;
        this.updatedBy = actorId;
    }

    private void applyComponents(RateComponents components) {
        this.baseAmount = components.baseAmount();
        this.amountPerKm = components.amountPerKm();
        this.amountPerKg = components.amountPerKg();
        this.amountPerM3 = components.amountPerM3();
        this.amountPerPallet = components.amountPerPallet();
        this.minimumAmount = components.minimumAmount();
        this.amountPerStop = components.amountPerStop();
        this.fuelSurchargePercent = components.fuelSurchargePercent();
        this.amountPerWaitingHour = components.amountPerWaitingHour();
        this.tollAmount = components.tollAmount();
        this.accessorialAmount = components.accessorialAmount();
        this.accessorialLabel = components.accessorialLabel();
        this.maximumAmount = components.maximumAmount();
    }
}
