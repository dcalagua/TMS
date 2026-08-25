/**
 * Rates and costing: the commercial agreements a carrier is paid under ({@code tms.rate_card}) and
 * what one shipment was estimated at and actually cost ({@code tms.trip_cost}).
 *
 * <p>A module of its own rather than a corner of {@code planning}, because the two answer to
 * different people: planning is judged on whether the truck went out, costing on whether the
 * invoice matched what was agreed. It reads a trip through {@code TripCostingLookupPort} and is
 * called back at confirmation through {@code TripCostEstimationPort}, so neither module imports
 * the other ({@code ModuleBoundaryTest}).
 *
 * <p>The rules, and what was deliberately left out of V1, are in
 * {@code docs/domain/RATES_COSTING_V1.md}.
 */
package com.ebim.tms.rates;
