package com.ebim.tms.appointments.application;

import jakarta.validation.constraints.NotNull;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

/**
 * A door's whole week, replaced in one call (migration V41).
 *
 * <p>The whole week rather than one day at a time: opening hours are read as a set - "we are open
 * Monday to Friday, 07:00 to 19:00" - and a per-day endpoint would let a caller half-apply a change
 * and leave the door open on a day the site meant to close.
 *
 * <p>Times are <b>local to the site</b>. See {@code ResourceCalendarEntry}.
 */
public record ResourceCalendarRequest(@NotNull List<DayHours> days) {

    /** One weekday's opening hours. {@code closesAt} must be after {@code opensAt}: no overnight windows. */
    public record DayHours(
            @NotNull(message = "is required") DayOfWeek day,
            @NotNull(message = "is required") LocalTime opensAt,
            @NotNull(message = "is required") LocalTime closesAt) {
    }
}
