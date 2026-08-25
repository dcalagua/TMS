/**
 * Operational master data: locations, zones, frequencies and routes.
 *
 * <p>{@code Location} is the one physical place. What it is, is {@code LocationType}; how it may
 * be used in a movement, is the set of {@code LocationRole}s it holds - {@code ORIGIN},
 * {@code DESTINATION}, or both. An "origin" and a "destination" are therefore views of this
 * master, not records of their own: the same store is the destination of the delivery and the
 * origin of the return, as one row.
 *
 * <p>The separate {@code tms.origin} / {@code tms.destination} masters that V6 and V7 created
 * were retired by V23, which repointed {@code route}, {@code route_stop},
 * {@code transport_order}, {@code planning_run} and {@code trip_stop} at {@code tms.location}.
 * See {@code docs/domain/LOCATIONS.md} and {@code docs/architecture/ADR_LOCATION_MODEL.md}.
 *
 * <p>A master Route is a planning template, never a calculated trip route.
 */
package com.ebim.tms.masterdata;
