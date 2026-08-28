package com.ebim.tms.appointments.application;

import com.ebim.tms.appointments.domain.LocationResource;
import com.ebim.tms.appointments.domain.ResourceCalendarEntry;
import com.ebim.tms.appointments.domain.ResourceType;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A door and its week (migration V41).
 *
 * @param openingHours empty means the door has no calendar, which the booking rules read as
 *     <em>open</em>: a company that has not configured hours has not said the door is shut, and
 *     refusing every booking until somebody fills in a form would make the feature unusable on day
 *     one
 */
public record LocationResourceView(
        UUID id,
        UUID locationId,
        String code,
        String name,
        ResourceType resourceType,
        int defaultSlotMinutes,
        boolean active,
        List<DayHoursView> openingHours,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public record DayHoursView(DayOfWeek day, LocalTime opensAt, LocalTime closesAt) {
    }

    static LocationResourceView from(LocationResource resource, List<ResourceCalendarEntry> calendar) {
        return new LocationResourceView(
                resource.id(),
                resource.locationId(),
                resource.code(),
                resource.name(),
                resource.resourceType(),
                resource.defaultSlotMinutes(),
                resource.active(),
                calendar.stream()
                        .map(entry -> new DayHoursView(entry.day(), entry.opensAt(), entry.closesAt()))
                        .toList(),
                resource.createdAt(),
                resource.updatedAt());
    }
}
