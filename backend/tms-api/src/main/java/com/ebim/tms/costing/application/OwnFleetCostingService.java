package com.ebim.tms.costing.application;

import com.ebim.tms.costing.domain.OwnFleetCostCalculator;
import com.ebim.tms.costing.domain.OwnFleetCostEstimate;
import com.ebim.tms.costing.domain.OwnFleetCostInputs;
import com.ebim.tms.costing.domain.OwnFleetCostLine;
import com.ebim.tms.costing.domain.OwnFleetCostProfile;
import com.ebim.tms.costing.domain.OwnFleetProfileResolver;
import com.ebim.tms.costing.domain.OwnFleetQuantitySource;
import com.ebim.tms.costing.infrastructure.OwnFleetCostProfileRepository;
import com.ebim.tms.shared.reference.OwnFleetTripLookupPort;
import com.ebim.tms.shared.reference.OwnFleetTripLookupPort.OwnFleetCostableTrip;
import com.ebim.tms.shared.reference.ResourceDutyLookupPort;
import com.ebim.tms.shared.reference.TransportCostNature;
import com.ebim.tms.shared.reference.TransportCostQuote;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What we model a trip costing us on our own fleet (V48, JOB 22).
 *
 * <p>Assembles the inputs and delegates every economic decision to
 * {@link OwnFleetCostCalculator}, which is a pure function. This class does the three things a pure
 * function cannot: resolve which profile is in force, measure the trip, and add the reposition.
 *
 * <h2>Duty time, and why the reposition is charged once</h2>
 *
 * <pre>
 *   Trip A execution 2h
 *   reposition A-&gt;B 40m
 *   Trip B execution 3h
 * </pre>
 *
 * The resource is tied up for 5h40m and the trips add to 5h. The forty minutes are charged to
 * <b>trip B</b>, the shipment the empty run happened <em>for</em> - never to trip A, and never to
 * both. Applied consistently to every trip in a day, the day's charged duty is exactly the
 * resource's duty, with no leg counted twice and none dropped.
 *
 * <p>Costing trip B on its own therefore charges its inbound reposition, because that leg is a real
 * consequence of running it. Costing trip A charges none, because A repositioned from nowhere.
 */
@Service
public class OwnFleetCostingService {

    private final OwnFleetCostProfileRepository profileRepository;
    private final OwnFleetTripLookupPort tripLookupPort;
    private final ResourceDutyLookupPort resourceDutyLookupPort;

    public OwnFleetCostingService(OwnFleetCostProfileRepository profileRepository,
            OwnFleetTripLookupPort tripLookupPort, ResourceDutyLookupPort resourceDutyLookupPort) {
        this.profileRepository = profileRepository;
        this.tripLookupPort = tripLookupPort;
        this.resourceDutyLookupPort = resourceDutyLookupPort;
    }

    /**
     * The internal cost of one trip, or a stated reason there is none.
     *
     * <p>Never throws for an uncostable trip. "We cannot cost this" is a real answer a planner needs
     * to see beside the options that could be costed, and an exception would leave the screen with
     * nothing to say.
     */
    @Transactional(readOnly = true)
    public OwnFleetQuoteView quote(CompanyScope scope, UUID tripId) {
        OwnFleetCostableTrip trip = tripLookupPort
                .findOwnFleetCostableTrip(tripId, scope.companyId())
                .orElseThrow(() -> new com.ebim.tms.shared.api.ResourceNotFoundException(
                        "No shipment " + tripId + " in this company"));

        if (trip.vehicleId() == null) {
            return unavailable(tripId, OwnFleetQuoteUnavailable.NO_VEHICLE_ASSIGNED);
        }
        if (!trip.isOwnFleet()) {
            // A subcontracted shipment has a carrier's PRICE. Modelling an internal cost for it
            // would put a second, softer number beside a real one for the same movement.
            return unavailable(tripId, OwnFleetQuoteUnavailable.NOT_OWN_FLEET);
        }

        LocalDate on = costingDate(trip);
        Optional<OwnFleetCostProfile> resolved = OwnFleetProfileResolver.resolve(
                profileRepository.findCandidates(scope.companyId(), trip.vehicleId(), trip.vehicleTypeId()),
                trip.vehicleId(), trip.vehicleTypeId(), on);

        if (resolved.isEmpty()) {
            // No rates configured. NOT a cost of zero - that would make every unconfigured truck
            // the cheapest option on every comparison, which is the failure this job exists to
            // prevent.
            return unavailable(tripId, OwnFleetQuoteUnavailable.NO_PROFILE_IN_FORCE);
        }

        OwnFleetCostProfile profile = resolved.get();
        OwnFleetCostEstimate estimate =
                OwnFleetCostCalculator.calculate(profile.rates(), inputsFor(scope, trip));
        return toView(tripId, profile, estimate);
    }

    /** The quote as planning compares it - the nature preserved, the amount nullable. */
    @Transactional(readOnly = true)
    public TransportCostQuote quoteForComparison(CompanyScope scope, UUID tripId) {
        OwnFleetQuoteView view = quote(scope, tripId);
        return new TransportCostQuote(TransportCostNature.OWN_FLEET_INTERNAL_COST,
                view.comparableTotal(), view.currency(), "Own fleet");
    }

    /**
     * The date whose rates apply: the planned departure, in UTC.
     *
     * <p>An unscheduled trip is costed at today's rates, which is the only defensible choice - it
     * has no date of its own, and refusing to cost it would hide a configured profile from a planner
     * building the day. The estimate is not stored, so nothing is frozen against a date that later
     * turns out to be wrong.
     */
    private static LocalDate costingDate(OwnFleetCostableTrip trip) {
        return trip.startsAt() == null
                ? LocalDate.now(ZoneOffset.UTC)
                : trip.startsAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
    }

    private OwnFleetCostInputs inputsFor(CompanyScope scope, OwnFleetCostableTrip trip) {
        Long execution = trip.executionMinutes();
        Long duty = null;
        OwnFleetQuantitySource dutySource = null;

        if (execution != null) {
            Optional<ResourceDutyLookupPort.Reposition> reposition =
                    resourceDutyLookupPort.findRepositionMinutes(trip.tripId(), scope.companyId());
            if (reposition.isEmpty()) {
                // Not sequenced behind anything: duty is the trip's own execution.
                duty = execution;
                dutySource = OwnFleetQuantitySource.TRIP_EXECUTION_WINDOW;
            } else if (reposition.get().isMeasured()) {
                duty = execution + reposition.get().minutes();
                dutySource = OwnFleetQuantitySource.RESOURCE_DUTY_WINDOW;
            }
            // else: sequenced behind another shipment by a join nobody could measure. Duty stays
            // null. Charging only the execution would understate it by however long the empty run
            // actually takes and would look like a complete answer - the same mistake as costing an
            // unmeasured distance at zero kilometres, one field over.
        }

        return new OwnFleetCostInputs(
                trip.measuredDistanceKm(),
                trip.measuredDistanceKm() == null ? null : OwnFleetQuantitySource.MEASURED_ROUTE,
                duty, dutySource);
    }

    private static OwnFleetQuoteView unavailable(UUID tripId, OwnFleetQuoteUnavailable reason) {
        return new OwnFleetQuoteView(tripId, TransportCostNature.OWN_FLEET_INTERNAL_COST, null,
                null, null, false, null, null, List.of(), reason, List.of());
    }

    private static OwnFleetQuoteView toView(UUID tripId, OwnFleetCostProfile profile,
            OwnFleetCostEstimate estimate) {
        return new OwnFleetQuoteView(
                tripId,
                estimate.nature(),
                estimate.currency(),
                estimate.comparableTotal(),
                estimate.partialSubtotal(),
                estimate.isComplete(),
                profile.id(),
                profile.isVehicleSpecific() ? "VEHICLE" : "VEHICLE_TYPE",
                List.copyOf(estimate.blockingReasons()),
                null,
                estimate.lines().stream().map(OwnFleetCostingService::toLine).toList());
    }

    private static OwnFleetQuoteView.Line toLine(OwnFleetCostLine line) {
        return new OwnFleetQuoteView.Line(
                line.component(),
                line.status().name(),
                line.rate(),
                line.quantity(),
                line.unit() == null ? null : line.unit().name(),
                line.quantitySource(),
                line.amount(),
                line.reason());
    }
}
