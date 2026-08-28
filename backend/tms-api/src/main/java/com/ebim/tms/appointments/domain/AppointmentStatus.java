package com.ebim.tms.appointments.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The life of a dock booking (migration V41), and <em>the</em> definition of which moves between
 * its states are legal.
 *
 * <pre>
 *   REQUESTED ─▶ CONFIRMED ─▶ ARRIVED ─▶ COMPLETED
 *        │            │  ▲        │
 *        │            ▼  │        ▼
 *        └────▶ RESCHEDULED   CANCELLED
 *                     │
 *                     ▼
 *                  NO_SHOW
 * </pre>
 *
 * <ul>
 *   <li>{@link #REQUESTED} - somebody asked for the slot. It already holds the door: an unconfirmed
 *       request that did not block the slot would let two trucks be promised one door, which is the
 *       whole failure this feature exists to prevent.</li>
 *   <li>{@link #CONFIRMED} - the site agreed.</li>
 *   <li>{@link #RESCHEDULED} - the window moved and the other party has not re-agreed yet. A live
 *       state, not a historical one: the booking still holds its (new) slot.</li>
 *   <li>{@link #ARRIVED} - the vehicle is at the door.</li>
 *   <li>{@link #COMPLETED} - terminal. Loaded or unloaded and gone.</li>
 *   <li>{@link #CANCELLED} - terminal. The slot is released.</li>
 *   <li>{@link #NO_SHOW} - terminal. Nobody came, and the slot is released - but the record stays,
 *       because a no-show is what a demurrage conversation is argued from.</li>
 * </ul>
 *
 * <p><b>Why RESCHEDULED is a state and not just an event.</b> The alternative is closing the old
 * booking and opening a new one, which loses the fact that this is the <em>same</em> commitment
 * moved - and a site that has agreed to a slot twice has a different relationship with a carrier
 * than one that has been asked twice. The old window is kept on the row.
 *
 * <p><b>Why ARRIVED cannot become NO_SHOW.</b> Somebody was there. Migration V41 says the same
 * through {@code ck_appointment_no_show_never_arrived}; this is the sentence a user reads.
 */
public enum AppointmentStatus {
    REQUESTED,
    CONFIRMED,
    RESCHEDULED,
    ARRIVED,
    COMPLETED,
    CANCELLED,
    NO_SHOW;

    /**
     * The states in which the booking holds its slot against everyone else.
     *
     * <p>Mirrors the {@code WHERE} clause of {@code ex_appointment_no_double_booking} exactly, and
     * that is not a coincidence to be maintained by hand - {@code AppointmentStatusTest} asserts the
     * two agree, because a Java set that drifted from the database's would let the application
     * believe a door was free that the database would then refuse.
     */
    private static final Set<AppointmentStatus> OCCUPIES_THE_DOOR =
            EnumSet.of(REQUESTED, CONFIRMED, RESCHEDULED, ARRIVED, COMPLETED);

    private static final Set<AppointmentStatus> TERMINAL = EnumSet.of(COMPLETED, CANCELLED, NO_SHOW);

    private static final Map<AppointmentStatus, Set<AppointmentStatus>> TRANSITIONS = Map.of(
            REQUESTED, EnumSet.of(CONFIRMED, RESCHEDULED, CANCELLED, NO_SHOW),
            CONFIRMED, EnumSet.of(RESCHEDULED, ARRIVED, CANCELLED, NO_SHOW),
            RESCHEDULED, EnumSet.of(CONFIRMED, ARRIVED, CANCELLED, NO_SHOW),
            // No NO_SHOW: somebody was there. No back to CONFIRMED: a vehicle that arrived cannot
            // un-arrive, for the reason TripStatus gives about undoing a departure.
            ARRIVED, EnumSet.of(COMPLETED, CANCELLED),
            COMPLETED, EnumSet.noneOf(AppointmentStatus.class),
            CANCELLED, EnumSet.noneOf(AppointmentStatus.class),
            NO_SHOW, EnumSet.noneOf(AppointmentStatus.class));

    /** Whether {@code target} may be reached from this state. Reflexive moves are not transitions. */
    public boolean canTransitionTo(AppointmentStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }

    /** The states reachable from this one, for a UI that renders only the buttons that work. */
    public Set<AppointmentStatus> allowedTransitions() {
        return Set.copyOf(TRANSITIONS.get(this));
    }

    /** Whether this booking is holding its slot against every other booking. */
    public boolean occupiesTheDoor() {
        return OCCUPIES_THE_DOOR.contains(this);
    }

    /** Whether anything further can happen to it. */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** Whether the window may still be moved. */
    public boolean isReschedulable() {
        return this == REQUESTED || this == CONFIRMED || this == RESCHEDULED;
    }
}
