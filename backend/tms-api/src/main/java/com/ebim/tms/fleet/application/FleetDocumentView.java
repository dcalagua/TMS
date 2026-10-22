package com.ebim.tms.fleet.application;

import com.ebim.tms.fleet.domain.FleetDocument;
import com.ebim.tms.shared.reference.ComplianceEnforcement;
import com.ebim.tms.shared.reference.FleetDocumentStatus;
import com.ebim.tms.shared.reference.FleetDocumentSubject;
import com.ebim.tms.shared.reference.FleetDocumentType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A fleet document with its status on the company's today (migration V72).
 *
 * @param subjectCode  the vehicle's plate or the driver's code
 * @param subjectName  the vehicle's code or the driver's full name
 * @param status       derived, never stored
 * @param daysToExpiry days from today to the last valid day: negative once expired, null when undated
 * @param mandatory    the company's rule for the type, resolved against the default
 * @param enforcement  what this type does when expired, resolved the same way
 */
public record FleetDocumentView(
        UUID id,
        FleetDocumentSubject subjectType,
        UUID subjectId,
        String subjectCode,
        String subjectName,
        FleetDocumentType documentType,
        String documentNumber,
        String issuer,
        LocalDate issuedOn,
        LocalDate expiresOn,
        String notes,
        boolean active,
        FleetDocumentStatus status,
        Long daysToExpiry,
        boolean mandatory,
        ComplianceEnforcement enforcement,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long version) {

    static FleetDocumentView of(FleetDocument document, String subjectCode, String subjectName, LocalDate today,
            int alertDays, boolean mandatory, ComplianceEnforcement enforcement) {
        return new FleetDocumentView(document.id(), document.subjectType(), document.subjectId(), subjectCode,
                subjectName, document.documentType(), document.documentNumber(), document.issuer(),
                document.issuedOn(), document.expiresOn(), document.notes(), document.active(),
                document.statusOn(today, alertDays),
                document.expiresOn() == null ? null : ChronoUnit.DAYS.between(today, document.expiresOn()),
                mandatory, enforcement, document.createdAt(), document.updatedAt(), document.version());
    }
}
