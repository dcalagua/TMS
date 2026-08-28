package com.ebim.tms.appointments.domain;

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
 * When one door is open, on one weekday (migration V41).
 *
 * <p><b>Local times, against the location's own zone.</b> A dock in Arequipa opens at 07:00 in
 * Arequipa. Storing that as an instant would move it twice a year in any country that shifts its
 * clocks, and would make "07:00" unreadable to the person who typed it.
 *
 * <p>No overnight windows: a door open 22:00-06:00 is two rows on two days, which is what a reader
 * means anyway. Allowing {@code closesAt < opensAt} would put a wrap-around branch in every
 * "is this inside the window" check, and that branch is the one nobody tests.
 */
@Entity
@Table(name = "resource_calendar")
public class ResourceCalendarEntry {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "resource_id", updatable = false, nullable = false)
    private UUID resourceId;

    /** ISO-8601: 1 = Monday .. 7 = Sunday, matching {@link DayOfWeek#getValue()}. */
    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;

    /**
     * Minutes since local midnight, not a {@link LocalTime} column.
     *
     * <p>The application sets {@code hibernate.jdbc.time_zone: UTC}, which normalises temporal
     * values on write - and a {@code time} column went through it, turning "this door opens at
     * 07:00 <em>here</em>" into 12:00 and silently moving every site's hours by its own offset. An
     * integer cannot be zone-shifted by any configuration, which is exactly the property this
     * needs: it is a quantity of minutes into the site's day, not an instant.
     *
     * <p>{@link LocalTime} stays the type every caller sees - see {@link #opensAt()}.
     */
    @Column(name = "opens_at_minutes", nullable = false)
    private int opensAtMinutes;

    @Column(name = "closes_at_minutes", nullable = false)
    private int closesAtMinutes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ResourceCalendarEntry() {}

    public ResourceCalendarEntry(UUID companyId, UUID resourceId, DayOfWeek day, LocalTime opensAt,
            LocalTime closesAt) {
        this.companyId = companyId;
        this.resourceId = resourceId;
        this.dayOfWeek = day.getValue();
        this.opensAtMinutes = minutesOf(opensAt);
        this.closesAtMinutes = minutesOf(closesAt);
    }

    /** Whether {@code from}-{@code to}, both local times on this day, fall inside the opening hours. */
    public boolean covers(LocalTime from, LocalTime to) {
        return minutesOf(from) >= opensAtMinutes && minutesOf(to) <= closesAtMinutes;
    }

    /**
     * Minutes since midnight. Seconds are dropped deliberately: a dock does not open at 07:00:30,
     * and keeping a precision the domain does not have would make two equal opening times compare
     * unequal.
     */
    private static int minutesOf(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    public UUID id() {
        return id;
    }

    public UUID resourceId() {
        return resourceId;
    }

    public DayOfWeek day() {
        return DayOfWeek.of(dayOfWeek);
    }

    /** The local opening time, reconstructed from the stored minutes. */
    public LocalTime opensAt() {
        return LocalTime.MIDNIGHT.plusMinutes(opensAtMinutes);
    }

    /**
     * The local closing time. {@code 1440} - the end of the day - comes back as 23:59, because
     * {@link LocalTime} has no 24:00 and a door open "all day" is not open into tomorrow.
     */
    public LocalTime closesAt() {
        return closesAtMinutes >= 1440 ? LocalTime.of(23, 59) : LocalTime.MIDNIGHT.plusMinutes(closesAtMinutes);
    }
}
