package com.ebim.tms.appointments.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The dock booking's transition table, provable with no database (migration V41).
 *
 * <p>The assertion that matters most is {@link #occupancySetMatchesTheDatabase()}: the Java set and
 * the exclusion constraint's {@code WHERE} clause say the same thing in two languages, and if they
 * drifted the application would believe a door was free that the database would then refuse - a
 * 500 in front of a dispatcher instead of a slot.
 */
class AppointmentStatusTest {

    /**
     * Mirrors the {@code WHERE} clause of {@code ex_appointment_no_double_booking} literally.
     *
     * <p>Written out here rather than derived from the enum, on purpose: deriving it from the thing
     * under test would assert only that the code equals itself. This is the database's sentence,
     * transcribed, and the test is whether the code still agrees with it.
     */
    private static final Set<AppointmentStatus> RELEASES_THE_DOOR_IN_SQL =
            EnumSet.of(AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW);

    @Test
    @DisplayName("the statuses that hold a door are exactly the ones the exclusion constraint keeps")
    void occupancySetMatchesTheDatabase() {
        Set<AppointmentStatus> holdsInJava = EnumSet.allOf(AppointmentStatus.class).stream()
                .filter(AppointmentStatus::occupiesTheDoor)
                .collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(AppointmentStatus.class)));
        Set<AppointmentStatus> holdsInSql = EnumSet.complementOf(EnumSet.copyOf(RELEASES_THE_DOOR_IN_SQL));

        assertThat(holdsInJava)
                .as("Java and ex_appointment_no_double_booking must agree about which bookings hold a door")
                .isEqualTo(holdsInSql);
    }

    @Test
    @DisplayName("a request already holds the door: an unconfirmed booking is not a free slot")
    void requestedHoldsTheDoor() {
        // Two trucks promised one door is the whole failure this feature exists to prevent, and it
        // would happen on the first unconfirmed request if REQUESTED did not hold the slot.
        assertThat(AppointmentStatus.REQUESTED.occupiesTheDoor()).isTrue();
    }

    @Test
    @DisplayName("a completed booking still holds its slot in history")
    void completedStillHolds() {
        // Two trucks recorded as having used one door at the same time is a history that cannot be
        // true, so COMPLETED stays inside the constraint.
        assertThat(AppointmentStatus.COMPLETED.occupiesTheDoor()).isTrue();
    }

    @Test
    @DisplayName("a cancellation and a no-show release the door: nobody used it")
    void cancelledAndNoShowRelease() {
        assertThat(AppointmentStatus.CANCELLED.occupiesTheDoor()).isFalse();
        assertThat(AppointmentStatus.NO_SHOW.occupiesTheDoor()).isFalse();
    }

    @Test
    @DisplayName("a vehicle that arrived can never be marked a no-show: somebody was there")
    void arrivedCannotBecomeNoShow() {
        assertThat(AppointmentStatus.ARRIVED.canTransitionTo(AppointmentStatus.NO_SHOW)).isFalse();
    }

    @Test
    @DisplayName("a vehicle that arrived cannot un-arrive")
    void arrivalIsNotUndone() {
        assertThat(AppointmentStatus.ARRIVED.canTransitionTo(AppointmentStatus.CONFIRMED)).isFalse();
        assertThat(AppointmentStatus.ARRIVED.canTransitionTo(AppointmentStatus.REQUESTED)).isFalse();
        assertThat(AppointmentStatus.ARRIVED.allowedTransitions())
                .containsExactlyInAnyOrder(AppointmentStatus.COMPLETED, AppointmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("a moved booking can be agreed to again, or moved again")
    void rescheduledIsLive() {
        assertThat(AppointmentStatus.RESCHEDULED.isReschedulable()).isTrue();
        assertThat(AppointmentStatus.RESCHEDULED.canTransitionTo(AppointmentStatus.CONFIRMED)).isTrue();
        assertThat(AppointmentStatus.RESCHEDULED.occupiesTheDoor()).isTrue();
    }

    @Test
    @DisplayName("only the three live states may be moved")
    void reschedulability() {
        assertThat(EnumSet.allOf(AppointmentStatus.class).stream()
                .filter(AppointmentStatus::isReschedulable))
                .containsExactlyInAnyOrder(AppointmentStatus.REQUESTED, AppointmentStatus.CONFIRMED,
                        AppointmentStatus.RESCHEDULED);
    }

    @Test
    @DisplayName("the three ways it ends are terminal and nothing else is")
    void terminalStates() {
        assertThat(EnumSet.allOf(AppointmentStatus.class).stream().filter(AppointmentStatus::isTerminal))
                .containsExactlyInAnyOrder(AppointmentStatus.COMPLETED, AppointmentStatus.CANCELLED,
                        AppointmentStatus.NO_SHOW);
        assertThat(AppointmentStatus.COMPLETED.allowedTransitions()).isEmpty();
        assertThat(AppointmentStatus.CANCELLED.allowedTransitions()).isEmpty();
        assertThat(AppointmentStatus.NO_SHOW.allowedTransitions()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(AppointmentStatus.class)
    @DisplayName("no state transitions to itself: a reflexive move is not a transition")
    void noReflexiveMoves(AppointmentStatus status) {
        assertThat(status.canTransitionTo(status)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"COMPLETED", "CANCELLED", "NO_SHOW"})
    @DisplayName("no live state is a dead end")
    void noDeadEnds(AppointmentStatus status) {
        assertThat(status.allowedTransitions()).isNotEmpty();
    }

    @ParameterizedTest
    @EnumSource(AppointmentStatus.class)
    @DisplayName("every state has a rule, so a new one cannot be added without one")
    void everyStateIsInTheTable(AppointmentStatus status) {
        assertThat(status.allowedTransitions()).isNotNull();
    }
}
