package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.StopExecutionStatus;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of the "which deliveries are running late" panel: a stop on a shipment that is out, that
 * nobody has resolved, ordered so the most overdue is first.
 *
 * <p>Flat rather than composed of {@code TripStopView}: that record is the trip workspace's row and
 * carries what a dispatcher needs while working <em>one</em> shipment - the allowed transitions,
 * the dwell time, the notes. This is a pointer into that screen from a list that spans every
 * shipment of the day, so it carries the shipment number instead, and stops at the four facts that
 * decide whether somebody picks up the phone.
 *
 * @param windowEndsAt      the planned window end resolved to a real instant in the company's zone
 *     - the stop stores a local time with no date, and {@code StopServiceWindow} is the one place
 *     that decides which day and which zone it belongs to
 * @param minutesPastWindow how far past that instant the stop is - to the arrival when the vehicle
 *     got there, to now when it has not - or null when it is still inside its window or has none.
 *     Null is the honest answer for a stop with no window: it cannot be late against nothing
 */
public record ControlTowerStopView(
        UUID stopId,
        UUID tripId,
        String shipmentNumber,
        int sequence,
        UUID destinationId,
        String destinationCode,
        String destinationName,
        StopExecutionStatus executionStatus,
        LocalTime serviceWindowStart,
        LocalTime serviceWindowEnd,
        OffsetDateTime windowEndsAt,
        Long minutesPastWindow,
        OffsetDateTime actualArrivalAt,
        String vehicleLicensePlate) {
}
