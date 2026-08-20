/**
 * Operational master data: locations, origins, zones, destinations, frequencies and routes.
 *
 * <p>{@code Location} is the canonical physical place (migration V14). {@code Origin} and
 * {@code Destination} are its compatibility projections while {@code route},
 * {@code transport_order}, {@code planning_run} and {@code trip_stop} still reference them;
 * {@code LocationCompatibilityProjector} is the only class that knows about both models. See
 * {@code docs/architecture/ADR_LOCATION_MODEL.md}.
 *
 * <p>A master Route is a planning template, never a calculated trip route.
 */
package com.ebim.tms.masterdata;
