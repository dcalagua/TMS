package com.ebim.tms.settlement.domain;

import java.util.Set;

/**
 * Which charge an invoice line bills (migration V64, ADR-024 part 1).
 *
 * <p>The names are the {@code RateComponent} names a carrier bills, so a line can be compared with
 * the component of the same name in the shipment's cost breakdown - settlement does not depend on
 * {@code rates} for that, it matches by name through {@code TripCostLookupPort}. Two names are not
 * components:
 *
 * <ul>
 *   <li>{@link #FREIGHT} - an all-in line. What every V46 line was, and what a null
 *       {@code charge_code} reads as.
 *   <li>{@link #PENALTY} - the shipper's deduction recorded on the carrier's document. Excluded
 *       from the expected-cost comparison (ADR-024 part 2, item 6).
 * </ul>
 *
 * <p>{@code MINIMUM_ADJUSTMENT} and {@code MAXIMUM_ADJUSTMENT} are not billable codes: a carrier
 * bills a base, and the tariff's floor or ceiling is part of what that base was expected to be, so
 * they are compared under {@link #BASE}.
 */
public enum ChargeCode {
    FREIGHT,
    BASE,
    DISTANCE,
    WEIGHT,
    VOLUME,
    PALLETS,
    LOADING_METERS,
    STOP_OFF,
    FUEL_SURCHARGE,
    WAITING_TIME,
    TOLL,
    OTHER_ACCESSORIAL,
    PENALTY;

    /** Null is a V46 line, which billed the shipment whole. */
    public static ChargeCode orFreight(ChargeCode code) {
        return code == null ? FREIGHT : code;
    }

    /** A rate component of the shipment's cost breakdown, rather than all-in or a deduction. */
    public boolean isComponent() {
        return this != FREIGHT && this != PENALTY;
    }

    /** The cost-breakdown component names this code is compared against. Empty unless a component. */
    public Set<String> componentNames() {
        return switch (this) {
            case FREIGHT, PENALTY -> Set.of();
            case BASE -> Set.of("BASE", "MINIMUM_ADJUSTMENT", "MAXIMUM_ADJUSTMENT");
            default -> Set.of(name());
        };
    }
}
