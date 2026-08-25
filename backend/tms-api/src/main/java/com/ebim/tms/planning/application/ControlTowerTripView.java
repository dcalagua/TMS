package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DepartureTimeliness;
import java.time.OffsetDateTime;

/**
 * One row of the control tower's operational table: the shipment header everything else in the
 * product already speaks, plus the four things a supervisor is actually scanning for.
 *
 * <p><b>Composed of {@link TripView} rather than repeating it.</b> A control tower row is a trip
 * seen through one lens, not a different object: the shipment number, origin, carrier, vehicle,
 * driver and capacity are resolved by {@code TripViewAssembler} exactly as they are on the Trips
 * screen, and a flattened copy here would be forty fields to keep in step with it forever. The
 * lens is the five monitoring fields below, and they are the only thing this record adds.
 *
 * @param departureTimeliness the server's verdict on this trip's departure - never derived in the
 *     browser from {@code plannedDepartureAt} and {@code actualDepartureAt}, because two screens
 *     deriving it would eventually disagree about what "late" means
 * @param departureDelayMinutes how late, in minutes, or null when the trip is not late. Always
 *     reported so a screen can distinguish three minutes from ninety-five instead of painting both
 *     red - see {@code DepartureDelay} on why V1 has no threshold of its own
 * @param stopsResolved how many of {@code stopsTotal} have been completed, skipped or failed. The
 *     progress a dispatcher reads as "4 of 7"; note that a skipped stop counts as resolved,
 *     because somebody dealt with it
 * @param stopsPastWindow how many of this trip's stops are past the time they should have been
 *     served by, whether the vehicle got there late or has not got there at all
 *     ({@code StopServiceWindow})
 * @param nextStopSequence the position of the first stop still outstanding, or null when the trip
 *     has none left - "where is it up to", without the caller reading the whole stop list
 * @param nextStopDueAt when that stop's service window closes, resolved to an instant in the
 *     company's zone, or null when it has no window
 * @param openExceptions problems raised on this trip that nobody has closed. The column that says
 *     which row to open first
 */
public record ControlTowerTripView(
        TripView trip,
        DepartureTimeliness departureTimeliness,
        Long departureDelayMinutes,
        int stopsTotal,
        int stopsResolved,
        int stopsPastWindow,
        Integer nextStopSequence,
        OffsetDateTime nextStopDueAt,
        long openExceptions) {
}
