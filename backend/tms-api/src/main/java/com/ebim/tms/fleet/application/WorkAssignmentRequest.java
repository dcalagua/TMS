package com.ebim.tms.fleet.application;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A day's work for one driver and one vehicle (migration V47).
 *
 * <p>The shipments travel as an <b>ordered list</b> and the whole list is always sent, never a
 * patch. Adding, removing and reordering are the same operation from the server's point of view -
 * and they have to be, because every one of them revalidates the entire sequence: moving a shipment
 * breaks the leg into it and the leg out of it.
 */
public record WorkAssignmentRequest(
        @NotNull(message = "is required") LocalDate operationalDate,
        @NotNull(message = "is required") UUID vehicleId,
        /** Optional: a truck is committed before the person is confirmed, which is how a yard plans. */
        UUID driverId,
        @Size(max = 1000) String notes,
        /** In the order they will be run. Empty is legal - a day can be opened before it is filled. */
        List<UUID> tripIds) {

    public List<UUID> tripIds() {
        return tripIds == null ? List.of() : List.copyOf(tripIds);
    }
}
