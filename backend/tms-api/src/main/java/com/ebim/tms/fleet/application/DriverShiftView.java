package com.ebim.tms.fleet.application;

import com.ebim.tms.fleet.domain.DriverShift;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

/** A driver's normal hours on one day (migration V42). */
public record DriverShiftView(UUID id, UUID driverId, DayOfWeek dayOfWeek, LocalTime startsAt, LocalTime endsAt) {

    public static DriverShiftView of(DriverShift shift) {
        return new DriverShiftView(shift.id(), shift.driverId(), shift.dayOfWeek(), shift.startsAt(), shift.endsAt());
    }
}
