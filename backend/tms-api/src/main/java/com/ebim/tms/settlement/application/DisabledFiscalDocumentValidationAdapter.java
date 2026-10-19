package com.ebim.tms.settlement.application;

import org.springframework.stereotype.Component;

/**
 * The only {@link FiscalDocumentValidationPort}: there is no connection to any tax authority, and it
 * says so. It never answers {@code ACCEPTED} - a disabled check that reported success would be the one
 * lie a freight audit cannot afford (ADR-024 part 2, item 4; ADR-027).
 */
@Component
class DisabledFiscalDocumentValidationAdapter implements FiscalDocumentValidationPort {

    @Override
    public FiscalDocumentValidationStatus validate(String fiscalCountry, String issuerTaxId,
            String fiscalDocumentCode, String fiscalDocumentNumber) {
        return FiscalDocumentValidationStatus.EXTERNAL_DEPENDENCY;
    }
}
