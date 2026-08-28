package com.ebim.tms.shared.reference;

import java.util.UUID;

/**
 * The little the appointments module needs to know about shipments and places (migration V41).
 *
 * <p>In {@code shared.reference} and not in the appointments module, for the reason every other
 * cross-module port is: a planning class implementing an interface that lived in appointments would
 * make planning depend on appointments, which {@code ModuleBoundaryTest} refuses - and it caught
 * exactly that when this port was first written in the wrong package.
 *
 * <p>Two questions, both answered elsewhere. Appointments must not read {@code tms.trip},
 * {@code tms.trip_stop} or {@code tms.location} directly: those belong to planning and masterdata,
 * and a module that reached into them would stop being extractable - the rule
 * {@code ModuleBoundaryTest} enforces and the reason every other cross-module conversation in this
 * product goes through a port.
 */
public interface AppointmentTripPort {

    /** Whether the shipment exists in this company. */
    boolean tripExists(UUID tripId, UUID companyId);

    /** Whether the stop belongs to that shipment, in this company. */
    boolean stopBelongsToTrip(UUID tripStopId, UUID tripId, UUID companyId);
}
