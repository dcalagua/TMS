package com.ebim.tms.fleet.application;

import com.ebim.tms.shared.reference.FleetDocumentSubject;
import com.ebim.tms.shared.reference.FleetDocumentType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Create and update share one shape (migration V72). The subject is fixed at creation: an update
 * that names a different one is refused rather than moving a paper to another truck.
 *
 * @param expiresOn the last valid day, inclusive. Optional: an undated paper is "unrecorded", which
 *                  warns when its type is mandatory and never blocks
 */
public record FleetDocumentRequest(
        @NotNull FleetDocumentSubject subjectType,
        @NotNull UUID subjectId,
        @NotNull FleetDocumentType documentType,
        @Size(max = 60) String documentNumber,
        @Size(max = 200) String issuer,
        LocalDate issuedOn,
        LocalDate expiresOn,
        @Size(max = 500) String notes) {
}
