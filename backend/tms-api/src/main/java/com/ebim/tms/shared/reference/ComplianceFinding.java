package com.ebim.tms.shared.reference;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One thing wrong with a vehicle's or a driver's papers on a given day (migration V72).
 *
 * <p>Only two statuses produce a finding: {@link FleetDocumentStatus#EXPIRED} (blocking when the
 * company's policy for the type says {@link ComplianceEnforcement#BLOCK}, a warning otherwise) and
 * {@link FleetDocumentStatus#UNRECORDED} for a mandatory type (always a warning). A document about to
 * expire is still valid on the day, and is shown on the documents screen rather than as a conflict.
 *
 * @param subjectCode  the vehicle's plate or the driver's code, so a message can name it
 * @param expiresOn    the expiry that passed; null for an unrecorded document
 * @param documentId   the document behind the finding; null when none is on file
 * @param onFile       whether any document of the type is on file - false is "missing", true with an
 *                     unrecorded status is "on file without an expiry date"
 * @param blocking     true only for an expired document whose policy blocks
 */
public record ComplianceFinding(
        FleetDocumentSubject subject,
        UUID subjectId,
        String subjectCode,
        FleetDocumentType documentType,
        FleetDocumentStatus status,
        LocalDate expiresOn,
        UUID documentId,
        String documentNumber,
        boolean onFile,
        boolean blocking) {

    /** The sentence an API message uses, in English like every other message of the API. */
    public String describe() {
        String whose = (subject == FleetDocumentSubject.VEHICLE ? "Vehicle " : "Driver ") + subjectCode;
        if (status == FleetDocumentStatus.EXPIRED) {
            return whose + ": the " + documentType.label()
                    + (documentNumber == null ? "" : " " + documentNumber) + " expired on " + expiresOn + ".";
        }
        return whose + ": the mandatory " + documentType.label()
                + (onFile ? " has no expiry date on file." : " is not on file.");
    }
}
