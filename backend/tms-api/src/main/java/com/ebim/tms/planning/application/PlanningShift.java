package com.ebim.tms.planning.application;

import java.time.Duration;
import java.time.LocalTime;

/**
 * The working day a proposed trip has to fit inside (JOB 05).
 *
 * <p><b>This is the hard time constraint, and lateness is not.</b> The distinction is deliberate.
 * A shipment that cannot physically be driven in a shift is not a plan - proposing one produces a
 * board that looks full and a driver who runs out of hours at the fourth stop. A shipment that
 * arrives after a customer's requested window is a different thing entirely: it is a real delivery,
 * usually the best available answer, and refusing to plan it would leave the customer with nothing
 * instead of with something late. So the shift refuses and the window is counted.
 *
 * <p>Departure and length are input rather than domain constants because they are a company's
 * operating decision, and defaults exist so a deployment that has not configured them still plans.
 *
 * @param departureAt  when the vehicle leaves the origin
 * @param maxDuration  driving plus service time a single trip may take. Not a legal driving-hours
 *                     model: this product does not hold the rules of any jurisdiction, and
 *                     pretending it did would be worse than a configurable ceiling that a planner
 *                     sets to what their own operation actually does
 */
public record PlanningShift(LocalTime departureAt, Duration maxDuration) {

    public static final LocalTime DEFAULT_DEPARTURE = LocalTime.of(6, 0);
    public static final Duration DEFAULT_MAX_DURATION = Duration.ofHours(10);

    /** 06:00, ten hours. What a deployment gets before anybody configures anything. */
    public static final PlanningShift DEFAULT = new PlanningShift(DEFAULT_DEPARTURE, DEFAULT_MAX_DURATION);

    public PlanningShift {
        departureAt = departureAt == null ? DEFAULT_DEPARTURE : departureAt;
        maxDuration = maxDuration == null || maxDuration.isNegative() || maxDuration.isZero()
                ? DEFAULT_MAX_DURATION
                : maxDuration;
    }

    public long maxMinutes() {
        return maxDuration.toMinutes();
    }

    /** The clock time {@code minutesFromDeparture} into the run, for a window comparison. */
    public LocalTime clockAfter(long minutesFromDeparture) {
        return departureAt.plusMinutes(minutesFromDeparture);
    }

    /** Whether a run of {@code minutes} fits the shift. */
    public boolean accommodates(long minutes) {
        return minutes <= maxMinutes();
    }
}
