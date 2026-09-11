package com.ebim.tms.fleet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.shared.api.ConflictException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a confirmed day's work may and may not become (migration V47), provable with no database.
 *
 * <p>The assertion that matters is {@link #rewritingAConfirmedDayUnconfirmsIt()}. {@code confirm} is
 * the <em>only</em> place a work assignment's feasibility is enforced; before this rule existed, a
 * {@code PUT} could replace the vehicle, the driver and the whole sequence of a {@code CONFIRMED}
 * day and leave it {@code CONFIRMED} - so the one gate in this module was not bypassed by breaking
 * it but by walking round it afterwards, and the row went on saying the day had been checked.
 *
 * <p>The rest of the class is the other half of that rule: an edit that changes nothing must not
 * un-confirm anything, or every re-submitted form would quietly reopen a commitment.
 */
class WorkAssignmentTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID VEHICLE = UUID.randomUUID();
    private static final UUID OTHER_VEHICLE = UUID.randomUUID();
    private static final UUID DRIVER = UUID.randomUUID();
    private static final UUID OTHER_DRIVER = UUID.randomUUID();
    private static final UUID TRIP_A = UUID.randomUUID();
    private static final UUID TRIP_B = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.of(2026, 3, 17);

    @Test
    @DisplayName("replacing the sequence of a confirmed day returns it to PLANNED")
    void rewritingAConfirmedDayUnconfirmsIt() {
        WorkAssignment assignment = confirmedDayRunning(TRIP_A);

        assignment.replaceTrips(List.of(trip(TRIP_A), trip(TRIP_B)), ACTOR);

        // The day now names a shipment that was never checked against the one before it, so it is
        // no longer a day anybody confirmed. Confirming it again is what re-runs the validator.
        assertThat(assignment.status()).isEqualTo(WorkAssignment.Status.PLANNED);
        assertThat(assignment.trips()).extracting(WorkAssignmentTrip::tripId)
                .containsExactly(TRIP_A, TRIP_B);
    }

    @Test
    @DisplayName("reordering a confirmed day returns it to PLANNED: the order is what was checked")
    void reorderingAConfirmedDayUnconfirmsIt() {
        WorkAssignment assignment = confirmedDayRunning(TRIP_A, TRIP_B);

        assignment.replaceTrips(List.of(trip(TRIP_B), trip(TRIP_A)), ACTOR);

        // Same shipments, different order - and the order is the whole question: the drive from one
        // to the next is not the same drive backwards.
        assertThat(assignment.status()).isEqualTo(WorkAssignment.Status.PLANNED);
    }

    @Test
    @DisplayName("swapping the vehicle of a confirmed day returns it to PLANNED")
    void swappingTheVehicleUnconfirms() {
        WorkAssignment assignment = confirmedDayRunning(TRIP_A);

        assignment.assignVehicle(OTHER_VEHICLE, ACTOR);

        assertThat(assignment.status()).isEqualTo(WorkAssignment.Status.PLANNED);
        assertThat(assignment.vehicleId()).isEqualTo(OTHER_VEHICLE);
    }

    @Test
    @DisplayName("swapping the driver of a confirmed day returns it to PLANNED")
    void swappingTheDriverUnconfirms() {
        WorkAssignment assignment = confirmedDayRunning(TRIP_A);

        assignment.assignDriver(OTHER_DRIVER, ACTOR);

        assertThat(assignment.status()).isEqualTo(WorkAssignment.Status.PLANNED);
        assertThat(assignment.driverId()).isEqualTo(OTHER_DRIVER);
    }

    @Test
    @DisplayName("clearing the driver of a confirmed day returns it to PLANNED")
    void clearingTheDriverUnconfirms() {
        WorkAssignment assignment = confirmedDayRunning(TRIP_A);

        assignment.assignDriver(null, ACTOR);

        // A confirmed day was checked against somebody's shift and licence. With nobody on it there
        // is nothing left that check was about.
        assertThat(assignment.status()).isEqualTo(WorkAssignment.Status.PLANNED);
    }

    @Test
    @DisplayName("re-sending exactly what the day already says leaves it confirmed")
    void anIdenticalUpdateIsIdempotent() {
        WorkAssignment assignment = confirmedDayRunning(TRIP_A, TRIP_B);

        // What WorkAssignmentService.update does on a PUT that changed nothing: the same resources
        // and the same sequence, in fresh rows carrying refreshed planned times.
        assignment.assignVehicle(VEHICLE, ACTOR);
        assignment.assignDriver(DRIVER, ACTOR);
        assignment.annotate(null, ACTOR);
        assignment.replaceTrips(List.of(trip(TRIP_A), trip(TRIP_B)), ACTOR);

        assertThat(assignment.status()).isEqualTo(WorkAssignment.Status.CONFIRMED);
        assertThat(assignment.trips()).extracting(WorkAssignmentTrip::sequence).containsExactly(1, 2);
    }

    @Test
    @DisplayName("a note is commentary and never un-confirms the day")
    void annotatingDoesNotUnconfirm() {
        WorkAssignment assignment = confirmedDayRunning(TRIP_A);

        assignment.annotate("Gate 4, ask for Ines", ACTOR);

        assertThat(assignment.status()).isEqualTo(WorkAssignment.Status.CONFIRMED);
        assertThat(assignment.notes()).isEqualTo("Gate 4, ask for Ines");
    }

    @Test
    @DisplayName("a cancelled day is still refused every edit, un-confirmation or not")
    void aCancelledDayStaysClosed() {
        WorkAssignment assignment = confirmedDayRunning(TRIP_A);
        assignment.cancel(ACTOR);

        assertThatThrownBy(() -> assignment.replaceTrips(List.of(trip(TRIP_B)), ACTOR))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> assignment.assignVehicle(OTHER_VEHICLE, ACTOR))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> assignment.annotate("anything", ACTOR))
                .isInstanceOf(ConflictException.class);
        assertThat(assignment.status()).isEqualTo(WorkAssignment.Status.CANCELLED);
    }

    private static WorkAssignment confirmedDayRunning(UUID... tripIds) {
        WorkAssignment assignment =
                new WorkAssignment(COMPANY, DAY, VEHICLE, DRIVER, null, ACTOR);
        assignment.replaceTrips(java.util.Arrays.stream(tripIds).map(WorkAssignmentTest::trip).toList(), ACTOR);
        assignment.confirm(ACTOR);
        assertThat(assignment.status()).isEqualTo(WorkAssignment.Status.CONFIRMED);
        return assignment;
    }

    private static WorkAssignmentTrip trip(UUID tripId) {
        OffsetDateTime start = DAY.atTime(8, 0).atOffset(java.time.ZoneOffset.UTC);
        return new WorkAssignmentTrip(COMPANY, tripId, start, start.plusHours(2), 30);
    }
}
