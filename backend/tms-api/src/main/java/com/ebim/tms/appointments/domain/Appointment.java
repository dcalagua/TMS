package com.ebim.tms.appointments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A vehicle booked into a door for a window of time (migration V41).
 *
 * <p><b>The window is absolute.</b> The location's time zone is how it is displayed and how the
 * door's local opening hours are read; it is not how it is stored, because a moment two parties
 * agreed on does not have a time zone. Storing "09:00" and a zone would make the booking move when
 * somebody edited the site's zone, which is a fact about a record and not about an agreement.
 *
 * <p><b>The trip and the stop are both optional</b>, and that is deliberate in both directions: a
 * customer may book a slot before a shipment exists, and a shipment's stop may never need a booked
 * door. The database allows a stop only with a trip
 * ({@code ck_appointment_stop_needs_trip}), because a stop without its trip points at half a
 * shipment.
 *
 * <p><b>The transition table is {@link AppointmentStatus}'s.</b> This entity asserts it again as a
 * last line of defense, in the two-layer shape {@code Trip} and {@code TransportOrder} use, with
 * V41's CHECK constraints beneath both. Overlap is the one rule this class does <em>not</em> assert:
 * it cannot see the other bookings, and {@code ex_appointment_no_double_booking} is the only place
 * two racing transactions can both be refused.
 */
@Entity
@Table(name = "appointment")
public class Appointment {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "resource_id", updatable = false, nullable = false)
    private UUID resourceId;

    @Column(name = "location_id", updatable = false, nullable = false)
    private UUID locationId;

    @Column(name = "trip_id")
    private UUID tripId;

    @Column(name = "trip_stop_id")
    private UUID tripStopId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", updatable = false, nullable = false)
    private AppointmentPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AppointmentStatus status = AppointmentStatus.REQUESTED;

    @Column(name = "window_start", nullable = false)
    private OffsetDateTime windowStart;

    @Column(name = "window_end", nullable = false)
    private OffsetDateTime windowEnd;

    @Column(name = "reference")
    private String reference;

    @Column(name = "notes")
    private String notes;

    @Column(name = "arrived_at")
    private OffsetDateTime arrivedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "rescheduled_from_start")
    private OffsetDateTime rescheduledFromStart;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected Appointment() {}

    public Appointment(UUID companyId, UUID resourceId, UUID locationId, UUID tripId, UUID tripStopId,
            AppointmentPurpose purpose, OffsetDateTime windowStart, OffsetDateTime windowEnd,
            String reference, String notes, UUID actorId) {
        if (!windowEnd.isAfter(windowStart)) {
            throw new IllegalArgumentException("an appointment must end after it starts");
        }
        this.companyId = companyId;
        this.resourceId = resourceId;
        this.locationId = locationId;
        this.tripId = tripId;
        this.tripStopId = tripStopId;
        this.purpose = purpose;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.reference = reference;
        this.notes = notes;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    /** The site agreed to the slot. */
    public void confirm(UUID actorId) {
        transitionTo(AppointmentStatus.CONFIRMED);
        this.updatedBy = actorId;
    }

    /**
     * Moves the window, keeping the same booking.
     *
     * <p>Not a cancel-and-rebook: this is the <em>same</em> commitment moved, and a site that has
     * agreed to a slot twice has a different relationship with a carrier than one that has been
     * asked twice. The previous start is kept so "this was changed, from when" is answerable from
     * the row rather than only from the audit trail.
     */
    public void reschedule(OffsetDateTime newStart, OffsetDateTime newEnd, UUID actorId) {
        if (!status.isReschedulable()) {
            throw new IllegalStateException("an appointment that is " + status + " cannot be moved");
        }
        if (!newEnd.isAfter(newStart)) {
            throw new IllegalArgumentException("an appointment must end after it starts");
        }
        // Only the first move is remembered here: the column answers "was this moved, and from
        // where did it originally stand", and overwriting it on every move would answer neither.
        if (rescheduledFromStart == null) {
            this.rescheduledFromStart = windowStart;
        }
        this.windowStart = newStart;
        this.windowEnd = newEnd;
        this.status = AppointmentStatus.RESCHEDULED;
        this.updatedBy = actorId;
    }

    /** The vehicle is at the door. */
    public void arrive(OffsetDateTime at, UUID actorId) {
        transitionTo(AppointmentStatus.ARRIVED);
        this.arrivedAt = at;
        this.updatedBy = actorId;
    }

    /** Loaded or unloaded and gone. */
    public void complete(OffsetDateTime at, UUID actorId) {
        transitionTo(AppointmentStatus.COMPLETED);
        this.completedAt = at;
        this.updatedBy = actorId;
    }

    /** The slot is released. The record stays. */
    public void cancel(OffsetDateTime at, String reason, UUID actorId) {
        transitionTo(AppointmentStatus.CANCELLED);
        this.cancelledAt = at;
        this.cancelReason = reason;
        this.updatedBy = actorId;
    }

    /**
     * Nobody came.
     *
     * <p>The slot is released and the record stays, because a no-show is what a demurrage or a
     * missed-slot conversation is argued from - deleting it would lose the only evidence the site
     * has. A vehicle that arrived can never be marked no-show; V41 says the same through
     * {@code ck_appointment_no_show_never_arrived}.
     */
    public void markNoShow(UUID actorId) {
        transitionTo(AppointmentStatus.NO_SHOW);
        this.updatedBy = actorId;
    }

    /** Attaches the booking to a shipment's stop after the fact - a slot booked before the trip existed. */
    public void attachTo(UUID tripId, UUID tripStopId, UUID actorId) {
        if (tripStopId != null && tripId == null) {
            throw new IllegalArgumentException("a stop without its trip points at half a shipment");
        }
        this.tripId = tripId;
        this.tripStopId = tripStopId;
        this.updatedBy = actorId;
    }

    private void transitionTo(AppointmentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "an appointment cannot move from " + status + " to " + target);
        }
        this.status = target;
    }

    /** How long the door is held. */
    public Duration duration() {
        return Duration.between(windowStart, windowEnd);
    }

    /** Whether this booking overlaps the given window - the same half-open convention as {@code &&}. */
    public boolean overlaps(OffsetDateTime start, OffsetDateTime end) {
        return windowStart.isBefore(end) && windowEnd.isAfter(start);
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public UUID resourceId() {
        return resourceId;
    }

    public UUID locationId() {
        return locationId;
    }

    public UUID tripId() {
        return tripId;
    }

    public UUID tripStopId() {
        return tripStopId;
    }

    public AppointmentPurpose purpose() {
        return purpose;
    }

    public AppointmentStatus status() {
        return status;
    }

    public OffsetDateTime windowStart() {
        return windowStart;
    }

    public OffsetDateTime windowEnd() {
        return windowEnd;
    }

    public String reference() {
        return reference;
    }

    public String notes() {
        return notes;
    }

    public OffsetDateTime arrivedAt() {
        return arrivedAt;
    }

    public OffsetDateTime completedAt() {
        return completedAt;
    }

    public OffsetDateTime cancelledAt() {
        return cancelledAt;
    }

    public String cancelReason() {
        return cancelReason;
    }

    public OffsetDateTime rescheduledFromStart() {
        return rescheduledFromStart;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }
}
