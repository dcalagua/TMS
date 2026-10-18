package com.ebim.tms.settlement.application;

/**
 * Whether SUNAT knows a carrier's CPE (V66, ADR-024 part 2, item 4).
 *
 * <p>TMS has no SUNAT credentials, so the only implementation is {@link DisabledCpeValidationAdapter},
 * which answers {@link CpeValidationStatus#EXTERNAL_DEPENDENCY} - <b>never "valid"</b>. A screen shows
 * that answer as it is: what TMS checked locally (RUC check digit, series, correlative, uniqueness)
 * is not what SUNAT would say.
 */
public interface CpeValidationPort {

    CpeValidationStatus validate(String issuerRuc, String documentTypeCode, String series, String correlative);

    enum CpeValidationStatus {
        /** Nobody asked SUNAT: the check needs a service TMS does not have. */
        EXTERNAL_DEPENDENCY,
        /** SUNAT accepted it - only an online implementation may ever answer this. */
        ACCEPTED,
        /** SUNAT does not know it, or reports it annulled. */
        NOT_ACCEPTED
    }
}
