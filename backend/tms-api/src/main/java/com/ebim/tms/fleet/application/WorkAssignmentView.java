package com.ebim.tms.fleet.application;

import com.ebim.tms.fleet.domain.WorkAssignment;
import com.ebim.tms.fleet.domain.WorkSequenceValidator;
import com.ebim.tms.shared.reference.ResourceRejectionReason;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A day's work, with every conflict named (migration V47).
 *
 * <p>The rule this record follows: <b>a conflict is never reduced to "not available".</b> The system
 * knows whether a licence expired, a truck is in the workshop or a gap is too short to drive, and
 * those are three different problems with three different fixes. Telling a planner only that
 * something is wrong makes them go and find out which.
 *
 * @param feasible true when the sequence has no conflicts. <b>Not a permission</b>: the shipments in
 *                 it are still dispatched one at a time, and every guard that refuses one today
 *                 goes on refusing it
 */
public record WorkAssignmentView(
        UUID id,
        LocalDate operationalDate,
        UUID vehicleId,
        String vehicleCode,
        UUID driverId,
        String driverName,
        WorkAssignment.Status status,
        String notes,
        long version,
        boolean feasible,
        List<TripView> trips,
        List<ConflictView> conflicts) {

    /**
     * @param repositionMinutes driving time from the previous shipment. Null on the first, and null
     *                          when routing could not measure it - which the screen shows as unknown
     *                          rather than as nothing
     */
    public record TripView(
            UUID tripId,
            String shipmentNumber,
            int sequence,
            OffsetDateTime plannedStart,
            OffsetDateTime plannedEnd,
            Integer repositionMinutes) {
    }

    /** @param sequence which shipment it is about, 1-based; 0 for a whole-day problem */
    public record ConflictView(int sequence, UUID tripId, ResourceRejectionReason reason, String detail) {

        static ConflictView of(WorkSequenceValidator.Rejection rejection) {
            return new ConflictView(rejection.sequence(), rejection.tripId(), rejection.reason(),
                    rejection.detail());
        }
    }
}
