package com.ebim.tms.fleet.domain;

import com.ebim.tms.shared.reference.ResourceRejectionReason;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Whether one driver and one vehicle can actually run a day's shipments in the order proposed
 * (migration V47, closing debt D5).
 *
 * <p><b>A pure function.</b> No repository, no clock, no randomness - the same shape
 * {@code StopScheduleEngine}, {@code FreightMatcher} and the planning engines have, and for the same
 * reason: a planner told "this day does not work" must be able to see exactly why from the inputs,
 * and a rule that needs a database to exercise is a rule nobody tests while changing it.
 *
 * <h2>The core invariant</h2>
 *
 * <pre>
 *   previous.end + reposition(previous.lastStop, next.origin) &lt;= next.start
 * </pre>
 *
 * <p>Two shipments that do not overlap in time can still be impossible, because the truck has to
 * get from one to the other. That drive is measured through the routing port - <b>never invented</b>.
 *
 * <h2>What it refuses to assume</h2>
 *
 * <p>An unmeasurable leg is {@link ResourceRejectionReason#ROUTING_UNKNOWN}, not zero. A day built
 * on a reposition nobody measured is a day nobody has checked, and calling it feasible would be the
 * most expensive kind of silence - the truck is committed and the second shipment is late. This is
 * the same rule V43 applies to stop ETAs and V45 to delivered quantities.
 *
 * <p>A shipment with no known start or end is equally refused: the sequence cannot be reasoned about
 * at all, and "we could not tell" is not "it is fine".
 */
public final class WorkSequenceValidator {

    private WorkSequenceValidator() {
    }

    /**
     * One shipment as the validator needs it.
     *
     * @param startsAt when the vehicle leaves for it, or null when the shipment has no planned
     *                 departure
     * @param endsAt   when it finishes its last stop, or null when that could not be computed -
     *                 which for a multi-stop shipment means an ETA leg could not be measured (V43)
     * @param carrierMatches whether the accepted carrier owns this vehicle. False is reported and
     *                 never repaired here: scheduling grants no authority (V42, debt D2)
     */
    public record ScheduledTrip(
            UUID tripId,
            String shipmentNumber,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            UUID startLocationId,
            UUID endLocationId,
            boolean carrierMatches) {
    }

    /**
     * What the driver and vehicle can do that day.
     *
     * @param shiftStart the driver's hours on this day, or null when they have none configured -
     *                   which is treated as "no shift rule", not as "no hours"
     * @param licenseValid whether the licence is good on the operational date
     * @param blocks     when either resource cannot work, already resolved for this day
     */
    public record ResourceWindow(
            LocalTime shiftStart,
            LocalTime shiftEnd,
            boolean licenseValid,
            List<Block> blocks) {

        /** One period a resource cannot work, and which resource it is about. */
        public record Block(OffsetDateTime from, OffsetDateTime until, ResourceRejectionReason reason) {
        }
    }

    /**
     * One thing wrong with the proposed day.
     *
     * @param sequence which position in the day it is about, 1-based; 0 for a whole-day problem
     */
    public record Rejection(int sequence, UUID tripId, ResourceRejectionReason reason, String detail) {
    }

    /**
     * Validates the WHOLE sequence, always.
     *
     * <p>Never only the element that changed. Moving shipment 2 breaks the leg into it <em>and</em>
     * the leg out of it, and a validator that checked one of them would report a day as feasible
     * with a broken join in the middle. Every operation - add, remove, reorder, swap the driver,
     * swap the vehicle - re-runs this from the start.
     *
     * @param repositionMinutes driving time into each shipment from the previous one, indexed the
     *                          same as {@code trips}. Element 0 is ignored (nothing to reposition
     *                          from); a null anywhere else means routing could not measure that leg
     * @return every problem found, empty when the day works. All of them, not the first - a planner
     *         fixing one at a time and re-submitting is how a five-minute change takes an hour
     */
    public static List<Rejection> validate(List<ScheduledTrip> trips, List<Long> repositionMinutes,
            ResourceWindow window, ZoneId zone) {
        List<Rejection> rejections = new ArrayList<>();

        if (!window.licenseValid()) {
            rejections.add(new Rejection(0, null, ResourceRejectionReason.LICENSE_INVALID,
                    "The driver's licence is not valid on this date."));
        }

        for (int index = 0; index < trips.size(); index++) {
            ScheduledTrip trip = trips.get(index);
            int position = index + 1;

            if (!trip.carrierMatches()) {
                // Reported, never repaired. A shipment agreed with one carrier and carrying
                // another's truck cannot depart (V42), and putting it in somebody's day must not
                // change that - a work assignment is not an alternative route past a dispatch guard.
                rejections.add(new Rejection(position, trip.tripId(),
                        ResourceRejectionReason.CARRIER_MISMATCH,
                        "Shipment " + trip.shipmentNumber() + " was accepted by a carrier that does not"
                                + " own this vehicle, so it cannot depart on it."));
            }

            if (trip.startsAt() == null || trip.endsAt() == null) {
                // Nothing can be reasoned about a shipment with no window. "We could not tell" is
                // not "it is fine".
                rejections.add(new Rejection(position, trip.tripId(),
                        ResourceRejectionReason.ROUTING_UNKNOWN,
                        "Shipment " + trip.shipmentNumber() + " has no known start and end, so the day"
                                + " around it cannot be checked."));
                continue;
            }

            rejections.addAll(shiftRejections(trip, position, window, zone));
            rejections.addAll(blockRejections(trip, position, window));

            if (index > 0) {
                rejections.addAll(sequenceRejections(trips.get(index - 1), trip, position,
                        index < repositionMinutes.size() ? repositionMinutes.get(index) : null));
            }
        }
        return List.copyOf(rejections);
    }

    /**
     * The join between two consecutive shipments: the overlap check and the reposition check.
     *
     * <p>Overlap is checked first and reported on its own, because two shipments that genuinely
     * clash is a different mistake from two that are merely too close together - one needs a
     * different truck, the other needs a different time.
     */
    private static List<Rejection> sequenceRejections(ScheduledTrip previous, ScheduledTrip current,
            int position, Long repositionMinutes) {
        List<Rejection> rejections = new ArrayList<>();
        if (previous.startsAt() == null || previous.endsAt() == null) {
            return rejections;
        }

        if (current.startsAt().isBefore(previous.endsAt())) {
            rejections.add(new Rejection(position, current.tripId(), ResourceRejectionReason.TRIP_OVERLAP,
                    "Shipment " + current.shipmentNumber() + " starts before " + previous.shipmentNumber()
                            + " has finished."));
            return rejections;
        }

        if (repositionMinutes == null) {
            // The leg could not be measured. Never zero - see the class comment.
            rejections.add(new Rejection(position, current.tripId(), ResourceRejectionReason.ROUTING_UNKNOWN,
                    "The drive from " + previous.shipmentNumber() + " to " + current.shipmentNumber()
                            + " could not be measured, so this day has not been checked."));
            return rejections;
        }

        OffsetDateTime readyAt = previous.endsAt().plusMinutes(repositionMinutes);
        if (readyAt.isAfter(current.startsAt())) {
            rejections.add(new Rejection(position, current.tripId(),
                    ResourceRejectionReason.INSUFFICIENT_REPOSITION_TIME,
                    "The vehicle finishes " + previous.shipmentNumber() + " and needs " + repositionMinutes
                            + " minutes to reach " + current.shipmentNumber() + ", which starts before it"
                            + " can get there."));
        }
        return rejections;
    }

    /**
     * Whether the work falls inside the driver's hours.
     *
     * <p>A driver with no shift configured is <b>not</b> refused: a company that has configured
     * nothing has said nothing, which is the same reading V41 gave a dock with no calendar. Refusing
     * would make the feature unusable for every installation that has not filled the shift table in.
     *
     * <p>Compared in the depot's local time, never the server's - the rule V41 paid for and V42
     * encoded by storing minutes since local midnight.
     */
    private static List<Rejection> shiftRejections(ScheduledTrip trip, int position, ResourceWindow window,
            ZoneId zone) {
        if (window.shiftStart() == null || window.shiftEnd() == null) {
            return List.of();
        }
        LocalTime start = trip.startsAt().atZoneSameInstant(zone).toLocalTime();
        LocalTime end = trip.endsAt().atZoneSameInstant(zone).toLocalTime();

        // An assignment is one operational date and V42 has no overnight shift, so work that runs
        // past midnight cannot be checked against the shift at all. Refused rather than waved
        // through: silently accepting it would grant overnight support the shift model does not
        // have, and the validator would be checking against a rule that does not exist.
        boolean crossesMidnight = end.isBefore(start);
        if (crossesMidnight || start.isBefore(window.shiftStart()) || end.isAfter(window.shiftEnd())) {
            return List.of(new Rejection(position, trip.tripId(), ResourceRejectionReason.SHIFT_CONFLICT,
                    crossesMidnight
                            ? "Shipment " + trip.shipmentNumber() + " runs past midnight, and a driver"
                                    + " shift is a single day's hours."
                            : "Shipment " + trip.shipmentNumber() + " runs " + start + "-" + end
                                    + ", outside the driver's " + window.shiftStart() + "-"
                                    + window.shiftEnd() + "."));
        }
        return List.of();
    }

    /** Whether either resource is blocked while this shipment runs. Half-open, matching V42. */
    private static List<Rejection> blockRejections(ScheduledTrip trip, int position, ResourceWindow window) {
        List<Rejection> rejections = new ArrayList<>();
        for (ResourceWindow.Block block : window.blocks()) {
            boolean overlaps = trip.startsAt().isBefore(block.until()) && trip.endsAt().isAfter(block.from());
            if (overlaps) {
                rejections.add(new Rejection(position, trip.tripId(), block.reason(),
                        "Shipment " + trip.shipmentNumber() + " runs while the "
                                + (block.reason() == ResourceRejectionReason.DRIVER_UNAVAILABLE ? "driver" : "vehicle")
                                + " is unavailable."));
            }
        }
        return rejections;
    }
}
