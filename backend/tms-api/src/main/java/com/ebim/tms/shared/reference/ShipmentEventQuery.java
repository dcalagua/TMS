package com.ebim.tms.shared.reference;

import java.time.OffsetDateTime;
import java.util.UUID;

/** The filter half of {@link ShipmentPublicationPort#searchEvents} - a company and a watermark. */
public record ShipmentEventQuery(UUID companyId, OffsetDateTime since) {
}
