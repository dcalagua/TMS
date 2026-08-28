package com.ebim.tms.appointments.application;

import com.ebim.tms.appointments.domain.Appointment;
import com.ebim.tms.appointments.domain.AppointmentPurpose;
import com.ebim.tms.appointments.domain.AppointmentStatus;
import com.ebim.tms.appointments.domain.LocationResource;
import com.ebim.tms.appointments.domain.ResourceType;
import com.ebim.tms.shared.reference.MasterReference;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * A dock booking as a screen reads it (migration V41).
 *
 * @param allowedTransitions what may happen to it next, so a UI renders only the buttons that work
 *     rather than offering actions the server will refuse
 * @param rescheduledFromStart where it originally stood, or null if it has never moved. Carried so
 *     that "this slot was changed" is visible on the row rather than only in the audit trail
 */
public record AppointmentView(
        UUID id,
        UUID resourceId,
        String resourceCode,
        String resourceName,
        ResourceType resourceType,
        UUID locationId,
        String locationCode,
        String locationName,
        UUID tripId,
        UUID tripStopId,
        AppointmentPurpose purpose,
        AppointmentStatus status,
        Set<AppointmentStatus> allowedTransitions,
        OffsetDateTime windowStart,
        OffsetDateTime windowEnd,
        long durationMinutes,
        String reference,
        String notes,
        OffsetDateTime arrivedAt,
        OffsetDateTime completedAt,
        OffsetDateTime cancelledAt,
        String cancelReason,
        OffsetDateTime rescheduledFromStart,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    static AppointmentView from(Appointment appointment, LocationResource resource, MasterReference location) {
        return new AppointmentView(
                appointment.id(),
                appointment.resourceId(),
                resource == null ? null : resource.code(),
                resource == null ? null : resource.name(),
                resource == null ? null : resource.resourceType(),
                appointment.locationId(),
                location == null ? null : location.code(),
                location == null ? null : location.name(),
                appointment.tripId(),
                appointment.tripStopId(),
                appointment.purpose(),
                appointment.status(),
                appointment.status().allowedTransitions(),
                appointment.windowStart(),
                appointment.windowEnd(),
                appointment.duration().toMinutes(),
                appointment.reference(),
                appointment.notes(),
                appointment.arrivedAt(),
                appointment.completedAt(),
                appointment.cancelledAt(),
                appointment.cancelReason(),
                appointment.rescheduledFromStart(),
                appointment.createdAt(),
                appointment.updatedAt());
    }
}
