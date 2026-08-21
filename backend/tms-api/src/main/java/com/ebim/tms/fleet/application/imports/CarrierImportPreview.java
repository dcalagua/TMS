package com.ebim.tms.fleet.application.imports;

import com.ebim.tms.shared.imports.ImportOutcome;

/** One carrier's row in an import report, as the operator reviews it. */
public record CarrierImportPreview(
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

    static CarrierImportPreview from(CarrierImportCandidate candidate) {
        return new CarrierImportPreview(candidate.code(), candidate.outcome(), candidate.rowNumber(),
                candidate.businessName(), candidate.taxIdType(), candidate.taxIdValue(), candidate.contactName(),
                candidate.phone(), candidate.email(), candidate.externalReference());
    }
}
