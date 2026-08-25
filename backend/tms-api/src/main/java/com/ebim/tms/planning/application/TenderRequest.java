package com.ebim.tms.planning.application;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The terms of an offer. Create and update share one shape, following every other module's
 * convention - and here it is more than a convention: updating a draft tender is exactly
 * "re-state the terms", so a second record would be the same four fields twice.
 *
 * <p>Everything is optional, which is the honest default for a company that tenders by phone under
 * a standing rate card and has no per-shipment price or deadline to state. What a tender always has
 * is a shipment and a carrier, and neither is here: the shipment is the path variable and the
 * carrier is the trip's own (migration V31).
 *
 * <p>No {@code version}. A tender is a short-lived child of a trip, serialised by the trip's row
 * lock, and its state machine already refuses everything a stale client could try - editing a sent
 * offer, answering a withdrawn one, sending a second live one. That is the same reasoning the
 * assignment endpoints use, spelled out in {@code PlanningActionRequest}.
 *
 * @param offeredAmount what TMS offers to pay for the shipment, with {@code currency}. Both or
 *     neither: an amount with no currency is not a price. Zero is legal - a backhaul run at no
 *     charge is a real arrangement - and negative is not
 * @param currency ISO 4217, upper-cased by the service. Not validated against the trip's cost
 *     currency on purpose: what a tariff says a shipment should cost and what somebody offered on
 *     the day are two facts, and the second is allowed to be stated in the currency it was agreed
 *     in
 * @param notes instructions travelling with the offer ("load 06:00, gate B, tail lift required").
 *     Addressed to a person at the carrier, not to a rule in this system
 * @param expiresAt when the offer lapses. Optional, and when given it must be in the future at the
 *     moment the tender is <em>sent</em> rather than at the moment it is drafted - a draft has no
 *     deadline running against it, which is why the check lives in {@code TripTender.send}
 */
public record TenderRequest(
        @DecimalMin(value = "0.00", message = "must not be negative")
        @Digits(integer = 12, fraction = 2, message = "must have at most 2 decimals")
        BigDecimal offeredAmount,

        @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a three-letter ISO 4217 code")
        String currency,

        @Size(max = 1000, message = "must be at most 1000 characters") String notes,

        OffsetDateTime expiresAt) {
}
