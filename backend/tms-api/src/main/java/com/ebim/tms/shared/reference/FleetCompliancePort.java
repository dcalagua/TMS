package com.ebim.tms.shared.reference;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Whether a vehicle's and a driver's papers allow them to work on a given day (migration V72),
 * answered by {@code fleet} for {@code planning} without either module depending on the other.
 *
 * <p>The day is a parameter for the reason {@link DriverLookupPort} gives about the licence: an
 * assignment asks about the trip's planning date, a dispatch about today, and a port that chose one
 * would be wrong about the other.
 *
 * <p>Implemented in {@code fleet.application.FleetComplianceService}, because which documents are
 * mandatory and which block is a company <em>rule</em>, not a lookup.
 */
public interface FleetCompliancePort {

    /** A port that finds nothing: for a caller built without fleet, such as a unit test. */
    FleetCompliancePort NONE = (companyId, vehicleId, driverId, on) -> List.of();

    /**
     * Every finding for the vehicle and the driver on {@code on}, vehicle first. Either id may be
     * null, and an id of another company finds nothing.
     */
    List<ComplianceFinding> findingsFor(UUID companyId, UUID vehicleId, UUID driverId, LocalDate on);

    /** The blocking findings only - what refuses an assignment or stops a dispatch. */
    default List<ComplianceFinding> blockingFor(UUID companyId, UUID vehicleId, UUID driverId, LocalDate on) {
        return findingsFor(companyId, vehicleId, driverId, on).stream().filter(ComplianceFinding::blocking).toList();
    }
}
