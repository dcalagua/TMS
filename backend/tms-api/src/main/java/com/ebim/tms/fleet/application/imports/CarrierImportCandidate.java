package com.ebim.tms.fleet.application.imports;

import com.ebim.tms.shared.imports.ImportOutcome;

/** One carrier as a validated file describes it. Mirrors {@code LocationImportCandidate}. */
public record CarrierImportCandidate(
        String code,
        ImportOutcome outcome,
        int rowNumber,
        String businessName,
        String taxIdType,
        String taxIdValue,
        String contactName,
        String phone,
        String email,
        String externalReference) {

    public boolean isCreatable() {
        return outcome == ImportOutcome.CREATE;
    }
}
