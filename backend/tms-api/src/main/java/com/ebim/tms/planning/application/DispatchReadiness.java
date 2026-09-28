package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.shared.reference.DriverLicenseStatus;
import com.ebim.tms.shared.reference.DriverLookupPort;
import com.ebim.tms.shared.reference.DriverReference;
import com.ebim.tms.shared.reference.ResourceAvailabilityPort;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * <b>The</b> list of reasons a committed trip cannot leave the dock (ADR-013 section 12).
 *
 * <p>Before this existed the list was written twice: {@code TripExecutionService.dispatch} refused
 * on five checks, and {@code ControlTowerService} re-derived two of them to warn a dispatcher at
 * 06:00. The two could drift, and a third reader - a warehouse system confirming that the truck
 * already left, which must <em>record</em> a failed check rather than refuse the fact - would have
 * made it three. Every reader now asks this class, and only decides what a blocker means to it:
 *
 * <ul>
 *   <li>a person dispatching: the first blocker is the 409 they read;</li>
 *   <li>the Control Tower: the blockers are its panel, judged at the planned departure;</li>
 *   <li>an external dispatch: the blockers become discrepancies on the document, because the truck
 *       is gone whatever TMS thinks of it.</li>
 * </ul>
 *
 * <p><b>Order matters.</b> {@link #evaluate} returns blockers in the order the gate has always
 * tried them - vehicle, driver, carrier, resources - so the first one is the sentence a
 * dispatcher has always been shown.
 *
 * <p>Nothing here is a new rule. Each check still has its layers underneath: {@code Trip} refuses
 * in the aggregate and the V25/V42 constraints refuse in the database.
 */
@Component
public class DispatchReadiness {

    /** Which checks a reader wants; the gate wants all of them. */
    public enum Check {
        VEHICLE_OPERABLE,
        DRIVER_OPERABLE,
        CARRIER_OWNS_VEHICLE,
        RESOURCES_AVAILABLE
    }

    /** Why a trip cannot leave, stable enough for a client to switch on. */
    public enum BlockerCode {
        NO_VEHICLE,
        VEHICLE_NOT_OPERABLE,
        DRIVER_NOT_ACTIVE,
        DRIVER_LICENCE_EXPIRED,
        AWAITING_CARRIER_VEHICLE,
        VEHICLE_UNAVAILABLE,
        DRIVER_UNAVAILABLE
    }

    /**
     * One reason.
     *
     * @param message the sentence the gate refuses with, naming the trip
     * @param detail  the short form a board shows beside a trip it already names
     */
    public record Blocker(BlockerCode code, String message, String detail) {
    }

    public static final Set<Check> ALL = EnumSet.allOf(Check.class);

    private final VehicleLookupPort vehicleLookupPort;
    private final DriverLookupPort driverLookupPort;
    private final ResourceAvailabilityPort resourceAvailabilityPort;

    public DispatchReadiness(VehicleLookupPort vehicleLookupPort, DriverLookupPort driverLookupPort,
            ResourceAvailabilityPort resourceAvailabilityPort) {
        this.vehicleLookupPort = vehicleLookupPort;
        this.driverLookupPort = driverLookupPort;
        this.resourceAvailabilityPort = resourceAvailabilityPort;
    }

    /** Every blocker for a departure at {@code at}, in the order the gate tries them. */
    public List<Blocker> evaluate(CompanyScope scope, Trip trip, OffsetDateTime at) {
        return evaluate(scope, trip, at, ALL);
    }

    /** The blockers among {@code checks} only, in the gate's order. */
    public List<Blocker> evaluate(CompanyScope scope, Trip trip, OffsetDateTime at, Set<Check> checks) {
        List<Blocker> blockers = new ArrayList<>();
        if (checks.contains(Check.VEHICLE_OPERABLE)) {
            vehicle(scope, trip).ifPresent(blockers::add);
        }
        if (checks.contains(Check.DRIVER_OPERABLE)) {
            driver(scope, trip).ifPresent(blockers::add);
        }
        if (checks.contains(Check.CARRIER_OWNS_VEHICLE)) {
            carrier(trip).ifPresent(blockers::add);
        }
        if (checks.contains(Check.RESOURCES_AVAILABLE) && at != null) {
            resources(scope, trip, at).ifPresent(blockers::add);
        }
        return List.copyOf(blockers);
    }

    /**
     * The vehicle must still be one this company may send out. A committed trip without one is
     * unreachable through the API ({@code ck_trip_confirmed_is_complete}, V25); it is reported
     * anyway so that a raw data fix produces this sentence and not a {@code NullPointerException}.
     */
    Optional<Blocker> vehicle(CompanyScope scope, Trip trip) {
        UUID vehicleId = trip.vehicleId();
        if (vehicleId == null) {
            return Optional.of(new Blocker(BlockerCode.NO_VEHICLE,
                    "Trip " + trip.tripNumber() + " has no vehicle assigned.", "No vehicle assigned."));
        }
        if (vehicleLookupPort.findAssignable(vehicleId, scope.companyId()).isEmpty()) {
            return Optional.of(new Blocker(BlockerCode.VEHICLE_NOT_OPERABLE,
                    "Trip " + trip.tripNumber() + " is assigned a vehicle that is no longer active and available.",
                    "The vehicle is no longer active and available."));
        }
        return Optional.empty();
    }

    /**
     * A named driver must still be active and licensed today in the company's zone. A trip with no
     * driver passes: naming one is never required (V26).
     */
    Optional<Blocker> driver(CompanyScope scope, Trip trip) {
        UUID driverId = trip.driverId();
        if (driverId == null) {
            return Optional.empty();
        }
        Optional<DriverReference> driver = driverLookupPort.findAssignable(driverId, scope.companyId());
        if (driver.isEmpty()) {
            return Optional.of(new Blocker(BlockerCode.DRIVER_NOT_ACTIVE,
                    "Trip " + trip.tripNumber() + " is assigned a driver who is no longer active.",
                    "The driver is no longer active."));
        }
        if (driver.get().licenseStatusOn(scope.today()) == DriverLicenseStatus.EXPIRED) {
            return Optional.of(new Blocker(BlockerCode.DRIVER_LICENCE_EXPIRED,
                    "Driver " + driver.get().code() + " has a licence that expired on "
                            + driver.get().licenseExpiresOn() + " and cannot run trip " + trip.tripNumber() + ".",
                    "Driver licence expired on " + driver.get().licenseExpiresOn() + "."));
        }
        return Optional.empty();
    }

    /** Accepted by a carrier that does not own the vehicle on it (V42, debt D2). */
    Optional<Blocker> carrier(Trip trip) {
        if (!trip.awaitsCarrierVehicle()) {
            return Optional.empty();
        }
        return Optional.of(new Blocker(BlockerCode.AWAITING_CARRIER_VEHICLE,
                "Trip " + trip.tripNumber() + " was accepted by a carrier that does not"
                        + " own the vehicle assigned to it. Assign one of that carrier's vehicles before dispatching.",
                "Accepted by a carrier that does not own the vehicle assigned to it."));
    }

    /** Neither the vehicle nor the driver may be blocked at {@code at} (V42). */
    Optional<Blocker> resources(CompanyScope scope, Trip trip, OffsetDateTime at) {
        return resourceAvailabilityPort.findBlock(scope.companyId(), trip.vehicleId(), trip.driverId(), at)
                .map(block -> new Blocker(
                        "vehicle".equals(block.resource()) ? BlockerCode.VEHICLE_UNAVAILABLE : BlockerCode.DRIVER_UNAVAILABLE,
                        "Trip " + trip.tripNumber() + " cannot depart: its " + block.resource() + " is unavailable ("
                                + block.reason() + ") until " + block.endsAt() + ".",
                        "Unavailable (" + block.reason() + ") until " + block.endsAt() + "."));
    }
}
