package com.ebim.tms.settlement.application;

/**
 * Whether the tax authority (SUNAT, the SRI...) knows a carrier's fiscal document (V66, ADR-024 part 2
 * item 4, ADR-027).
 *
 * <p>TMS <b>receives</b> carrier documents and checks their shape locally (tax-id check digit, number
 * format, uniqueness); it issues, signs or sends nothing to a tax authority, and it has no credentials
 * to consult one. The only implementation is {@link DisabledFiscalDocumentValidationAdapter}, which
 * answers {@link FiscalDocumentValidationStatus#EXTERNAL_DEPENDENCY} - <b>never "valid"</b>. A screen
 * shows that answer as it is.
 */
public interface FiscalDocumentValidationPort {

    FiscalDocumentValidationStatus validate(String fiscalCountry, String issuerTaxId, String fiscalDocumentCode,
            String fiscalDocumentNumber);

    enum FiscalDocumentValidationStatus {
        /** Nobody asked the tax authority: the check needs a service TMS does not have. */
        EXTERNAL_DEPENDENCY,
        /** The authority accepted it - only an online implementation may ever answer this. */
        ACCEPTED,
        /** The authority does not know it, or reports it annulled. */
        NOT_ACCEPTED
    }
}
