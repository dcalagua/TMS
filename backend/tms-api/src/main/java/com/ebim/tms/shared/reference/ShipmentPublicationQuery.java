package com.ebim.tms.shared.reference;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * The filter half of {@link ShipmentPublicationPort#search}.
 *
 * @param companyId    the tenant, resolved server-side from the integration credential - never a
 *                      value a partner supplies (see {@code docs/integrations/INBOUND_API_V1.md}
 *                      section 1, the same rule this outbound side follows)
 * @param statuses      the publishable statuses to include, as plain codes
 *                      ({@code "CONFIRMED"}/{@code "CANCELLED"}) rather than
 *                      {@code planning.domain.TripStatus} - the same reason
 *                      {@link PlannableOrder#priority()} is a plain string. Never empty; the
 *                      caller decides the default.
 * @param updatedSince  only shipments touched at or after this instant, or null for no lower
 *                      bound - the watermark half of "confirmed shipments filtered by
 *                      updated-since/id/status" (job 08's brief)
 */
public record ShipmentPublicationQuery(UUID companyId, Set<String> statuses, OffsetDateTime updatedSince) {

    public ShipmentPublicationQuery {
        statuses = Set.copyOf(statuses);
    }
}
