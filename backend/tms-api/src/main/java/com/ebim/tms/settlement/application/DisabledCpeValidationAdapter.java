package com.ebim.tms.settlement.application;

import org.springframework.stereotype.Component;

/**
 * The only {@link CpeValidationPort}: there is no SUNAT connection, and it says so. It never answers
 * {@code ACCEPTED} - a disabled check that reported success would be the one lie a freight audit
 * cannot afford (ADR-024 part 2, item 4).
 */
@Component
class DisabledCpeValidationAdapter implements CpeValidationPort {

    @Override
    public CpeValidationStatus validate(String issuerRuc, String documentTypeCode, String series,
            String correlative) {
        return CpeValidationStatus.EXTERNAL_DEPENDENCY;
    }
}
