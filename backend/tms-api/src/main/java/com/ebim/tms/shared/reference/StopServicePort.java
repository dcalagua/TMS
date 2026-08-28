package com.ebim.tms.shared.reference;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * How long a place takes to serve (migration V43).
 *
 * <p>Its own port for the reason {@link LocationTimeZonePort} is one: {@link MasterReference} is
 * read by five modules and none of the others needs a service time. This matters for exactly one
 * thing - building a stop schedule - and the alternative is widening a shared record for one caller
 * or letting {@code planning} read {@code tms.location}, which {@code ModuleBoundaryTest} refuses
 * and should.
 *
 * <p>Only the service time, deliberately. A stop's <em>window</em> is not a property of the place:
 * {@code tms.trip_stop.service_window_start/end} (V11) is per shipment, because the same
 * destination receives at different hours for different customers, and the trip already has it.
 *
 * <p>Batched, not per-id. A shipment with twelve stops resolves them in one query, never twelve -
 * the same discipline {@code VehicleLookupPort} and {@code TripViewAssembler} follow.
 */
public interface StopServicePort {

    /**
     * The service time of each of these locations, in minutes, keyed by location id.
     *
     * <p>A location absent from the result is one this company cannot see. A location present with
     * zero has configured nothing - which is a real answer, not "instant" but "nobody said", and a
     * schedule that adds zero is the honest reading of it.
     */
    Map<UUID, Integer> findServiceMinutes(Collection<UUID> locationIds, UUID companyId);
}
