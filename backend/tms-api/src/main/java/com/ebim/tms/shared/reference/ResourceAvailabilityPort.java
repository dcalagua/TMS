package com.ebim.tms.shared.reference;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Whether a vehicle or a driver may work at a given moment (migration V42).
 *
 * <p>The fleet module owns the answer; {@code planning} asks the question. The port exists for the
 * reason every other one in this package does - a shipment must not reach into
 * {@code tms.resource_unavailability}, and ArchUnit enforces that it cannot.
 *
 * <p>Deliberately narrow: one question, "is anything blocking these two right now", answered with
 * the first block found rather than all of them. A dispatcher stopped at the gate needs the reason
 * the truck is not going, and a list of every future workshop booking is not it.
 */
public interface ResourceAvailabilityPort {

    /**
     * The block stopping this vehicle or this driver at {@code at}, if there is one.
     *
     * @param vehicleId may be null - a shipment without one is checked for its driver alone
     * @param driverId  may be null, which is the ordinary case before a driver is named
     * @return empty when both are free, or when both are null
     */
    Optional<ResourceBlock> findBlock(UUID companyId, UUID vehicleId, UUID driverId, OffsetDateTime at);
}
