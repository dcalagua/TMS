package com.ebim.tms.fleet.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.fleet.domain.WorkSequenceValidator.Rejection;
import com.ebim.tms.fleet.domain.WorkSequenceValidator.ResourceWindow;
import com.ebim.tms.fleet.domain.WorkSequenceValidator.ScheduledTrip;
import com.ebim.tms.shared.reference.ResourceRejectionReason;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Whether a driver and a vehicle can run a day's shipments in the order proposed (V47, debt D5).
 *
 * <p>A pure function, so all of this runs without a database - which is the point. A planner told
 * "this day does not work" must be able to see exactly why from the inputs.
 *
 * <p>The nest that matters most is {@link Reposition}: two shipments that do not overlap in time can
 * still be impossible, because the truck has to get from one to the other. And within it, the case
 * worth the whole feature - {@link Reposition#unmeasurableLegIsNotZero}.
 */
class WorkSequenceValidatorTest {

    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final UUID LOCATION_X = UUID.randomUUID();
    private static final UUID LOCATION_Y = UUID.randomUUID();

    /** 09:00 Lima on the operational date. */
    private static OffsetDateTime at(String localTime) {
        return OffsetDateTime.parse("2026-09-07T" + localTime + ":00-05:00");
    }

    private static ScheduledTrip trip(String number, String start, String end) {
        return new ScheduledTrip(UUID.randomUUID(), number, at(start), at(end), LOCATION_X, LOCATION_Y, true);
    }

    private static final ResourceWindow OPEN_DAY =
            new ResourceWindow(null, null, true, List.of());

    private static List<Rejection> validate(List<ScheduledTrip> trips, Long... reposition) {
        return WorkSequenceValidator.validate(trips, Arrays.asList(reposition), OPEN_DAY, LIMA);
    }

    // --- the core invariant -------------------------------------------------------------

    @Nested
    @DisplayName("the reposition between two shipments")
    class Reposition {

        /** The brief's worked example: ends 09:00, 40 minutes away, next starts 10:00. */
        @Test
        @DisplayName("40 minutes of driving into a 60 minute gap is feasible")
        void feasibleSequence() {
            List<Rejection> rejections = validate(
                    List.of(trip("SH-1", "07:00", "09:00"), trip("SH-2", "10:00", "12:00")),
                    null, 40L);

            assertThat(rejections).isEmpty();
        }

        /** The same drive into a 20 minute gap. */
        @Test
        @DisplayName("40 minutes of driving into a 20 minute gap is not")
        void insufficientReposition() {
            List<Rejection> rejections = validate(
                    List.of(trip("SH-1", "07:00", "09:00"), trip("SH-2", "09:20", "11:00")),
                    null, 40L);

            assertThat(rejections).extracting(Rejection::reason)
                    .containsExactly(ResourceRejectionReason.INSUFFICIENT_REPOSITION_TIME);
        }

        /**
         * The boundary is inclusive: arriving exactly as the next shipment starts is feasible.
         * Getting this wrong loses an hour of capacity on every vehicle, every day.
         */
        @Test
        @DisplayName("arriving exactly on time is allowed")
        void exactBoundaryIsAllowed() {
            List<Rejection> rejections = validate(
                    List.of(trip("SH-1", "07:00", "09:00"), trip("SH-2", "09:40", "11:00")),
                    null, 40L);

            assertThat(rejections).isEmpty();
        }

        @Test
        @DisplayName("one minute past the boundary is not")
        void oneMinutePastIsRefused() {
            List<Rejection> rejections = validate(
                    List.of(trip("SH-1", "07:00", "09:00"), trip("SH-2", "09:39", "11:00")),
                    null, 40L);

            assertThat(rejections).extracting(Rejection::reason)
                    .containsExactly(ResourceRejectionReason.INSUFFICIENT_REPOSITION_TIME);
        }

        /**
         * <b>The case the whole feature turns on.</b> An unmeasurable leg is refused, never assumed
         * to take no time. Calling such a day feasible is the most expensive kind of silence: the
         * truck is committed and the second shipment is late.
         */
        @Test
        @DisplayName("a leg routing could not measure is refused, not silently allowed")
        void unmeasurableLegIsNotZero() {
            List<Rejection> rejections = validate(
                    List.of(trip("SH-1", "07:00", "09:00"), trip("SH-2", "09:01", "11:00")),
                    null, (Long) null);

            assertThat(rejections).extracting(Rejection::reason)
                    .containsExactly(ResourceRejectionReason.ROUTING_UNKNOWN);
        }

        @Test
        @DisplayName("two shipments that clash in time are an overlap, not a short drive")
        void overlapIsItsOwnReason() {
            List<Rejection> rejections = validate(
                    List.of(trip("SH-1", "07:00", "11:00"), trip("SH-2", "09:00", "12:00")),
                    null, 10L);

            // Different mistake, different fix: one needs another truck, the other another time.
            assertThat(rejections).extracting(Rejection::reason)
                    .containsExactly(ResourceRejectionReason.TRIP_OVERLAP);
        }
    }

    // --- the whole sequence is revalidated ----------------------------------------------

    @Nested
    @DisplayName("the whole day is revalidated, never one leg")
    class WholeSequence {

        /**
         * Moving a shipment breaks the leg into it <em>and</em> the leg out of it. A validator that
         * checked only what changed would report a day as feasible with a broken join in the middle.
         */
        @Test
        @DisplayName("a bad middle shipment is reported on both of its joins")
        void reordersAffectBothJoins() {
            List<Rejection> rejections = validate(
                    List.of(trip("SH-1", "06:00", "08:00"),
                            trip("SH-2", "08:10", "10:00"),
                            trip("SH-3", "10:05", "12:00")),
                    null, 40L, 40L);

            assertThat(rejections).hasSize(2);
            assertThat(rejections).extracting(Rejection::sequence).containsExactly(2, 3);
            assertThat(rejections).extracting(Rejection::reason)
                    .containsOnly(ResourceRejectionReason.INSUFFICIENT_REPOSITION_TIME);
        }

        /** Every problem at once, so a planner does not fix one, resubmit, and find another. */
        @Test
        @DisplayName("every problem is reported together, not the first one")
        void reportsEverything() {
            ScheduledTrip mismatch = new ScheduledTrip(UUID.randomUUID(), "SH-9",
                    at("07:00"), at("09:00"), LOCATION_X, LOCATION_Y, false);
            List<Rejection> rejections = WorkSequenceValidator.validate(
                    List.of(mismatch, trip("SH-2", "09:10", "11:00")),
                    Arrays.asList(null, 40L),
                    new ResourceWindow(null, null, false, List.of()), LIMA);

            assertThat(rejections).extracting(Rejection::reason).contains(
                    ResourceRejectionReason.LICENSE_INVALID,
                    ResourceRejectionReason.CARRIER_MISMATCH,
                    ResourceRejectionReason.INSUFFICIENT_REPOSITION_TIME);
        }

        @Test
        @DisplayName("a single shipment needs no reposition and is feasible on its own")
        void singleTripIsFine() {
            assertThat(validate(List.of(trip("SH-1", "07:00", "09:00")), (Long) null)).isEmpty();
        }
    }

    // --- the resource itself -------------------------------------------------------------

    @Nested
    @DisplayName("what the driver and vehicle can do")
    class Resources {

        @Test
        @DisplayName("an expired licence stops the whole day, not one shipment")
        void invalidLicence() {
            List<Rejection> rejections = WorkSequenceValidator.validate(
                    List.of(trip("SH-1", "07:00", "09:00")), Arrays.asList((Long) null),
                    new ResourceWindow(null, null, false, List.of()), LIMA);

            assertThat(rejections).extracting(Rejection::reason)
                    .containsExactly(ResourceRejectionReason.LICENSE_INVALID);
            assertThat(rejections.getFirst().sequence()).isZero();
        }

        @Test
        @DisplayName("work outside the driver's hours is a shift conflict")
        void outsideShift() {
            List<Rejection> rejections = WorkSequenceValidator.validate(
                    List.of(trip("SH-1", "05:00", "09:00")), Arrays.asList((Long) null),
                    new ResourceWindow(LocalTime.of(8, 0), LocalTime.of(18, 0), true, List.of()), LIMA);

            assertThat(rejections).extracting(Rejection::reason)
                    .containsExactly(ResourceRejectionReason.SHIFT_CONFLICT);
        }

        /**
         * A company that configured no shift has said nothing, which is the same reading V41 gave a
         * dock with no calendar. Refusing would make the feature unusable for every installation
         * that has not filled the shift table in.
         */
        @Test
        @DisplayName("a driver with no shift configured is not refused")
        void noShiftMeansNoRule() {
            assertThat(validate(List.of(trip("SH-1", "03:00", "23:00")), (Long) null)).isEmpty();
        }

        /**
         * V42 refused overnight shifts on purpose. Work that crosses midnight therefore cannot be
         * checked against a shift at all, and is refused rather than waved through - accepting it
         * would grant overnight support the model does not have.
         */
        @Test
        @DisplayName("work crossing midnight is refused rather than granted overnight support")
        void midnightIsRefused() {
            ScheduledTrip overnight = new ScheduledTrip(UUID.randomUUID(), "SH-N",
                    at("22:00"), OffsetDateTime.parse("2026-09-08T02:00:00-05:00"),
                    LOCATION_X, LOCATION_Y, true);

            List<Rejection> rejections = WorkSequenceValidator.validate(
                    List.of(overnight), Arrays.asList((Long) null),
                    new ResourceWindow(LocalTime.of(6, 0), LocalTime.of(23, 59), true, List.of()), LIMA);

            assertThat(rejections).extracting(Rejection::reason)
                    .containsExactly(ResourceRejectionReason.SHIFT_CONFLICT);
        }

        @Test
        @DisplayName("a vehicle in the workshop is a maintenance block, not a generic unavailability")
        void maintenanceIsItsOwnReason() {
            ResourceWindow blocked = new ResourceWindow(null, null, true, List.of(
                    new ResourceWindow.Block(at("08:00"), at("12:00"),
                            ResourceRejectionReason.MAINTENANCE_BLOCK)));

            List<Rejection> rejections = WorkSequenceValidator.validate(
                    List.of(trip("SH-1", "07:00", "09:00")), Arrays.asList((Long) null), blocked, LIMA);

            // The person who resolves it is different: a workshop books a truck out, and a planner
            // cannot argue with it.
            assertThat(rejections).extracting(Rejection::reason)
                    .containsExactly(ResourceRejectionReason.MAINTENANCE_BLOCK);
        }

        @Test
        @DisplayName("a driver absence blocks the shipment it overlaps")
        void driverBlock() {
            ResourceWindow blocked = new ResourceWindow(null, null, true, List.of(
                    new ResourceWindow.Block(at("08:00"), at("12:00"),
                            ResourceRejectionReason.DRIVER_UNAVAILABLE)));

            assertThat(WorkSequenceValidator.validate(
                    List.of(trip("SH-1", "07:00", "09:00")), Arrays.asList((Long) null), blocked, LIMA))
                    .extracting(Rejection::reason)
                    .containsExactly(ResourceRejectionReason.DRIVER_UNAVAILABLE);
        }

        /** Half-open, matching V42: a block ending at 09:00 does not affect work starting at 09:00. */
        @Test
        @DisplayName("a block that ends before the shipment starts does not block it")
        void blockBoundaryIsHalfOpen() {
            ResourceWindow blocked = new ResourceWindow(null, null, true, List.of(
                    new ResourceWindow.Block(at("06:00"), at("07:00"),
                            ResourceRejectionReason.VEHICLE_UNAVAILABLE)));

            assertThat(WorkSequenceValidator.validate(
                    List.of(trip("SH-1", "07:00", "09:00")), Arrays.asList((Long) null), blocked, LIMA))
                    .isEmpty();
        }
    }

    // --- the guard that must not be bypassed ---------------------------------------------

    @Nested
    @DisplayName("scheduling grants no authority")
    class NoBackDoor {

        /**
         * <b>The rule V47 must not become an exception to.</b> A shipment accepted by a carrier that
         * does not own this vehicle (V42, debt D2) cannot depart. Putting it into somebody's day
         * reports the conflict and repairs nothing - a work assignment is not an alternative route
         * past a dispatch guard.
         */
        @Test
        @DisplayName("an accepted-carrier mismatch is reported and never cleared")
        void carrierMismatchIsReported() {
            ScheduledTrip mismatch = new ScheduledTrip(UUID.randomUUID(), "SH-M",
                    at("07:00"), at("09:00"), LOCATION_X, LOCATION_Y, false);

            assertThat(WorkSequenceValidator.validate(
                    List.of(mismatch), Arrays.asList((Long) null), OPEN_DAY, LIMA))
                    .extracting(Rejection::reason)
                    .containsExactly(ResourceRejectionReason.CARRIER_MISMATCH);
        }

        @Test
        @DisplayName("a shipment with no known window cannot be checked, and is not assumed fine")
        void unknownWindowIsRefused() {
            ScheduledTrip unknown = new ScheduledTrip(UUID.randomUUID(), "SH-U",
                    null, null, LOCATION_X, LOCATION_Y, true);

            assertThat(WorkSequenceValidator.validate(
                    List.of(unknown), Arrays.asList((Long) null), OPEN_DAY, LIMA))
                    .extracting(Rejection::reason)
                    .containsExactly(ResourceRejectionReason.ROUTING_UNKNOWN);
        }
    }
}
