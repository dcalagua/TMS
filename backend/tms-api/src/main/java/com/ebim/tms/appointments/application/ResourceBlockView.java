package com.ebim.tms.appointments.application;

import com.ebim.tms.appointments.domain.ResourceBlockedSlot;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A closure on one door (migration V41), as the screen that registers them lists it.
 *
 * <p>Absolute instants, unlike the weekly calendar: "closed next Tuesday" is not "closed on
 * Tuesdays".
 */
public record ResourceBlockView(UUID id, UUID resourceId, OffsetDateTime startsAt, OffsetDateTime endsAt,
        String reason) {

    static ResourceBlockView from(ResourceBlockedSlot slot) {
        return new ResourceBlockView(slot.id(), slot.resourceId(), slot.startsAt(), slot.endsAt(), slot.reason());
    }
}
