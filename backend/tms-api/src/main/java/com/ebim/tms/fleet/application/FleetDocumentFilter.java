package com.ebim.tms.fleet.application;

import com.ebim.tms.shared.reference.FleetDocumentStatus;
import com.ebim.tms.shared.reference.FleetDocumentSubject;
import com.ebim.tms.shared.reference.FleetDocumentType;
import java.util.UUID;

/**
 * The optional list filters for {@code GET /fleet/documents}. {@code status} is judged against the
 * company's today and its alert window, the same way the status column is.
 */
public record FleetDocumentFilter(
        FleetDocumentSubject subjectType,
        UUID vehicleId,
        UUID driverId,
        FleetDocumentType documentType,
        FleetDocumentStatus status,
        Boolean active) {
}
