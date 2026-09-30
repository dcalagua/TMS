package com.ebim.tms.integration.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * TMS's answer to a dispatch document (WAREHOUSE_EXECUTION_V1 §4.1). Every outcome here is a
 * successful delivery: a disagreement with the plan is information, never an error.
 */
public record DispatchConfirmationResponseV1(
        UUID id,
        String dispatchReference,
        int revision,
        String outcome,
        String transportReference,
        String verificationStatus,
        List<Discrepancy> discrepancies) {

    public record Discrepancy(String code, String severity, String orderReference, Integer lineNumber,
            BigDecimal planned, BigDecimal dispatched, String uom, String detail) {
    }
}
