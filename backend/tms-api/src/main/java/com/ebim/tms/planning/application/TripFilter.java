package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.TripStatus;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The optional narrowing of {@code GET /planning/trips} - the execution board's own filters, not
 * the planning board's.
 *
 * <p>Company is deliberately absent: it comes from the {@code CompanyScope} resolved server-side
 * and is never a filter a caller can widen. Everything here can only narrow what that scope
 * already allows.
 *
 * @param shipmentNumber matched case-insensitively as a substring, so typing "142" finds
 *     {@code SH-00000142} - a dispatcher reads the tail of the number off a manifest, not the
 *     padding
 * @param originId the trip's origin, which lives on its planning run rather than on the trip
 *     itself; {@code TripSpecifications} resolves it with a subquery instead of denormalizing a
 *     column that would then need keeping in step
 * @param driverId "where is Ana today" - the question the driver master exists to make answerable
 *     from the trip board, cancelled trips included (unlike the double-booking index, which is
 *     partial on non-cancelled because it is asking something else)
 */
public record TripFilter(
        String shipmentNumber,
        TripStatus status,
        UUID originId,
        UUID carrierId,
        UUID vehicleId,
        UUID driverId,
        LocalDate planningDateFrom,
        LocalDate planningDateTo) {
}
