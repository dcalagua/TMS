package com.ebim.tms.tracking.application;

import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.reference.TrackedTrip;
import com.ebim.tms.shared.reference.TrackingReport;
import com.ebim.tms.shared.reference.TripTrackingLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.tracking.domain.TrackingPosition;
import com.ebim.tms.tracking.domain.TrackingProviderPort;
import com.ebim.tms.tracking.infrastructure.TrackingPositionRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side of tracking: where one shipment is, and where it has been recently.
 *
 * <p>Stored positions first, the provider only as a fallback. A deployment receiving a push feed
 * never touches {@link TrackingProviderPort} at all, which is the ordering that matters: the
 * common case is a database read, and an external system is consulted only when TMS has nothing of
 * its own to say.
 *
 * <p><b>A pulled position is not stored.</b> It is returned and forgotten, and that is deliberate:
 * a GET that writes is a GET that fails on a read-only replica and a cache with no invalidation.
 * When a pull provider actually exists, the right home for persistence is a poller that runs on its
 * own schedule against its own quota - see ADR-007, which records that decision as the next one to
 * make rather than pre-empting it here.
 */
@Service
public class TrackingQueryService {

    private final TrackingPositionRepository positionRepository;
    private final TripTrackingLookupPort tripLookup;
    private final TrackingProviderPort provider;
    private final TrackingProperties properties;

    public TrackingQueryService(TrackingPositionRepository positionRepository, TripTrackingLookupPort tripLookup,
            TrackingProviderPort provider, TrackingProperties properties) {
        this.positionRepository = positionRepository;
        this.tripLookup = tripLookup;
        this.provider = provider;
        this.properties = properties;
    }

    /**
     * One shipment's tracking, or 404 when this company has no such trip - never 403, for the
     * reason {@link ResourceNotFoundException} states.
     *
     * <p>Any status is readable, including {@code DRAFT} and {@code CANCELLED}. Reading where a
     * shipment got to is not the same authority as reporting where it is, and refusing the read
     * would mean the track of a trip that was cancelled mid-route - exactly the one somebody
     * reviews afterwards - could never be looked at again.
     */
    @Transactional(readOnly = true)
    public TripTrackingView get(CompanyScope scope, UUID tripId) {
        TrackedTrip trip = tripLookup.findById(scope.companyId(), tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip " + tripId + " was not found."));

        List<TrackingPosition> recent = positionRepository.findByCompanyIdAndTripIdOrderByOccurredAtDesc(
                scope.companyId(), tripId, Limit.of(properties.trackLimit()));

        // Newest first from the index, reversed once here so every caller draws a trail in the
        // direction the vehicle travelled instead of each deciding for itself.
        List<TrackingPositionView> track = new ArrayList<>(recent.stream().map(TrackingQueryService::toView).toList());
        Collections.reverse(track);

        TrackingPositionView last = recent.isEmpty()
                ? pulled(scope.companyId(), trip).orElse(null)
                : toView(recent.get(0));

        return new TripTrackingView(
                trip.shipmentNumber(),
                trip.status(),
                trip.trackable(),
                provider.isEnabled() || !recent.isEmpty(),
                trip.vehicleCode(),
                trip.vehicleLicensePlate(),
                last,
                track);
    }

    /**
     * The provider fallback, consulted only when nothing has ever been reported for this shipment.
     *
     * <p>Not consulted for a trip that is not out on the road: a vendor would happily report where
     * the truck is parked tonight, and showing that against a shipment which has not left would be
     * TMS presenting a true fact as an answer to a different question.
     */
    private Optional<TrackingPositionView> pulled(UUID companyId, TrackedTrip trip) {
        if (!provider.isEnabled() || !trip.trackable()) {
            return Optional.empty();
        }
        return provider.lastKnownPosition(companyId, trip).map(TrackingQueryService::toView);
    }

    private static TrackingPositionView toView(TrackingPosition position) {
        return new TrackingPositionView(position.id(), position.occurredAt(), position.receivedAt(),
                position.latitude(), position.longitude(), position.speedKph(), position.headingDegrees(),
                position.provider(), position.externalVehicleReference());
    }

    /**
     * A pulled report has no row, so it has no id and no {@code receivedAt}. Both stay null rather
     * than being filled with "now": the pair exists to measure feed latency, and a received time
     * this method invented would report a latency of zero that nothing measured. A null id is also
     * how a caller tells a pulled position from a stored one, which is worth being able to do.
     */
    private static TrackingPositionView toView(TrackingReport report) {
        return new TrackingPositionView(null, report.occurredAt(), null, report.latitude(), report.longitude(),
                report.speedKph(), report.headingDegrees(), report.provider(), report.externalVehicleReference());
    }
}
