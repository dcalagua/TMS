package com.ebim.tms.fleet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * When a driver normally works, on one day of the week (migration V42).
 *
 * <p><b>Stored as minutes since local midnight</b>, and that is the whole point of the shape.
 * This application sets {@code hibernate.jdbc.time_zone: UTC}, which normalises temporal values on
 * write - a {@code LocalTime} of 06:00 goes to the database as {@code 11:00+00} in Lima and comes
 * back as 06:00 only because the same offset is applied in reverse. JOB 08 found that the hard way
 * on dock opening hours, where a CHECK constraint caught close-before-open; without the constraint
 * every site's hours would have shifted silently by its own offset. An integer count of minutes
 * cannot be shifted by a driver, a dialect or a deployment's clock.
 *
 * <p>{@link #startsAt()} and {@link #endsAt()} give the same value back as a {@link LocalTime} for
 * a caller that wants to display it. Nothing persists that type.
 *
 * <p>No overnight shifts in V1: {@code ends_at_minutes > starts_at_minutes}. A shift running
 * 22:00-06:00 is two rows on two days, which is more typing and less arithmetic than the
 * wrap-around branch every containment check would otherwise need.
 */
@Entity
@Table(name = "driver_shift")
public class DriverShift {

    private static final int MINUTES_IN_A_DAY = 1440;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "driver_id", updatable = false, nullable = false)
    private UUID driverId;

    /** ISO-8601, matching {@link DayOfWeek#getValue()}: 1 is Monday, 7 is Sunday. */
    @Column(name = "day_of_week", updatable = false, nullable = false)
    private int dayOfWeek;

    @Column(name = "starts_at_minutes", nullable = false)
    private int startsAtMinutes;

    @Column(name = "ends_at_minutes", nullable = false)
    private int endsAtMinutes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected DriverShift() {
    }

    public DriverShift(UUID companyId, UUID driverId, DayOfWeek dayOfWeek, LocalTime startsAt, LocalTime endsAt) {
        this.companyId = companyId;
        this.driverId = driverId;
        this.dayOfWeek = dayOfWeek.getValue();
        this.startsAtMinutes = minutesOf(startsAt);
        this.endsAtMinutes = minutesOf(endsAt);
        assertWindow();
    }

    public void moveTo(LocalTime startsAt, LocalTime endsAt) {
        this.startsAtMinutes = minutesOf(startsAt);
        this.endsAtMinutes = minutesOf(endsAt);
        assertWindow();
    }

    /** Whether this shift covers a local time on its day. Half-open: a shift ending at 18:00 is over at 18:00. */
    public boolean covers(LocalTime localTime) {
        int minutes = minutesOf(localTime);
        return minutes >= startsAtMinutes && minutes < endsAtMinutes;
    }

    private void assertWindow() {
        if (endsAtMinutes <= startsAtMinutes) {
            throw new IllegalStateException("a shift must end after it starts; an overnight shift is two shifts");
        }
        if (startsAtMinutes < 0 || startsAtMinutes >= MINUTES_IN_A_DAY
                || endsAtMinutes < 1 || endsAtMinutes > MINUTES_IN_A_DAY) {
            throw new IllegalStateException("a shift must lie within one day");
        }
    }

    private static int minutesOf(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public UUID driverId() {
        return driverId;
    }

    public DayOfWeek dayOfWeek() {
        return DayOfWeek.of(dayOfWeek);
    }

    public LocalTime startsAt() {
        return LocalTime.MIDNIGHT.plusMinutes(startsAtMinutes);
    }

    /**
     * A shift ending at midnight ends at 23:59 as a {@link LocalTime}, because 1440 minutes is the
     * end of the day and {@code LocalTime.MIDNIGHT} is the start of it. Same reason
     * {@code ResourceCalendarEntry.closesAt} does it - the stored 1440 is the truth, and this is a
     * label.
     */
    public LocalTime endsAt() {
        return endsAtMinutes >= MINUTES_IN_A_DAY ? LocalTime.of(23, 59) : LocalTime.MIDNIGHT.plusMinutes(endsAtMinutes);
    }

    public int startsAtMinutes() {
        return startsAtMinutes;
    }

    public int endsAtMinutes() {
        return endsAtMinutes;
    }
}
