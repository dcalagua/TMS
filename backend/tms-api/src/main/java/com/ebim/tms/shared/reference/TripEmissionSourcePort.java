package com.ebim.tms.shared.reference;

import com.ebim.tms.shared.reference.TripSettlementLookupPort.TripLoad;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What a trip drove and carried, for its carbon footprint (migration V73), answered by
 * {@code planning}, which owns the trip and measures its route.
 *
 * <p>The distance is the trip workspace's own figure ({@code TripRouteMetrics}, V38): origin to the
 * first stop, then stop to stop, with the provenance of the routing port. A footprint that measured
 * the road a second way would disagree with the distance printed beside it.
 */
public interface TripEmissionSourcePort {

    /**
     * @param distanceKm       the sum of the measured legs; zero when nothing could be measured
     * @param estimated        any leg came from the local estimator rather than a router
     * @param unmeasurableLegs legs that could not be measured at all - a total that is short says so
     * @param provider         what produced the distance, as the routing port names it; null with no leg
     * @param loads            the ACTIVE assignment rows, the ledger cost-to-serve splits on (V68)
     */
    record TripEmissionSource(
            UUID tripId,
            String shipmentNumber,
            boolean cancelled,
            LocalDate planningDate,
            OffsetDateTime departedAt,
            UUID routeId,
            UUID vehicleId,
            UUID vehicleTypeId,
            String vehiclePlate,
            BigDecimal distanceKm,
            boolean estimated,
            int unmeasurableLegs,
            String provider,
            List<TripLoad> loads) {

        public TripEmissionSource {
            loads = List.copyOf(loads);
        }
    }

    /** One trip of {@code companyId}; empty when it is not this company's. */
    Optional<TripEmissionSource> findSource(UUID tripId, UUID companyId);

    /**
     * The trips not cancelled whose planning date falls in {@code [from, to]}, oldest first, at most
     * {@code limit} of them.
     */
    List<TripEmissionSource> findInPeriod(UUID companyId, LocalDate from, LocalDate to, int limit);
}
