package com.ebim.tms.fleet.application;

import jakarta.validation.constraints.NotNull;
import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * A driver's normal hours on one day (migration V42).
 *
 * <p>{@link LocalTime} on the wire and minutes in the database: what a person types is a wall-clock
 * time at the depot, and what is stored cannot be zone-shifted. {@code DriverShift} does the
 * conversion in one place.
 */
public record DriverShiftRequest(
        @NotNull DayOfWeek dayOfWeek,
        @NotNull LocalTime startsAt,
        @NotNull LocalTime endsAt) {
}
